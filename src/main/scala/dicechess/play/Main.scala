package dicechess.play

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.server.{
  AnonMintLimiter,
  BotAuth,
  BotEvents,
  BotRoutes,
  CatalogRoster,
  CatalogRoutes,
  Challenges,
  Cors,
  GameRegistry,
  HealthRoutes,
  HistoryRoutes,
  LadderScheduler,
  LeaderboardRoutes,
  Lobby,
  LobbyRoutes,
  PlayerRoutes,
  PlayRoutes,
  StrengthRoutes,
  WebhookRoutes,
  Webhooks
}
import dicechess.play.ingest.IngestDeliverer
import dicechess.play.rating.{RatingBatch, StrengthCache, StrengthReport}
import dicechess.play.store.{BotStore, GameStore, PgGameStore, Retention, WebhookStore}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host    = host"0.0.0.0"
  private val port    = port"8080"
  private val version = sys.env.getOrElse("APP_VERSION", "dev")

  /** The shared outbound client, with deadlines that clear the webhook window.
    *
    * On the plain `default` builder Ember's own timeouts (45 s header-receive, 60 s idle) are both shorter than a turn
    * a bot may legitimately spend, so they — not `WEBHOOK_TIMEOUT_SECONDS` — decided when a delivery died: a configured
    * 210 s was silently a 45 s cut in production (#188). Ingest is indifferent to the wider window: `IngestDeliverer`
    * bounds every request with its own 15 s timeout.
    */
  private[play] def outboundClientBuilder(webhooks: Option[Webhooks.Config]): EmberClientBuilder[IO] =
    webhooks.fold(EmberClientBuilder.default[IO]): config =>
      EmberClientBuilder
        .default[IO]
        .withTimeout(config.clientTimeout)
        .withIdleConnectionTime(config.clientIdleTimeout)

  // Persistence is opt-in by env: with PLAY_DB_URL set, games snapshot into Postgres, live games are resumed on boot,
  // and registered bot identities are durable; with INGEST_URL/INGEST_TOKEN also set, finished games are delivered to
  // analytics from the durable outbox. Without PLAY_DB_URL the server runs in-memory exactly as before (games and
  // registered bots die with the process).
  // The third slot is the concrete Postgres store when persistence is on: the rating batch and the public
  // leaderboard/profile routes both need its DB-only seams (RatingStore, LeaderboardStore) and are simply absent
  // without a database. The outbound HTTP client is shared by every outbound feature (ingest delivery, webhook
  // push) and is built from the webhook window above — an unused pool holds no connections.
  private def appResources: Resource[IO, (GameStore, BotStore, Option[PgGameStore], Client[IO], IO[Unit])] =
    outboundClientBuilder(Webhooks.configFromEnv).build.flatMap { http =>
      PgGameStore.configFromEnv match
        case None => Resource.eval(BotStore.inMemory).map(bots => (GameStore.noop, bots, None, http, IO.never))
        case Some(dbConfig) =>
          PgGameStore.resource(dbConfig).map { store =>
            val deliverer = IngestDeliverer.configFromEnv match
              case None =>
                cats.effect.std
                  .Console[IO]
                  .errorln("[play][ingest] INGEST_URL/INGEST_TOKEN unset: finished games accumulate in the outbox")
                  *> IO.never
              case Some(ingestConfig) => IngestDeliverer(store, http, ingestConfig).loop.void
            (store, store, Some(store), http, deliverer)
          }
    }

  def run: IO[Unit] = appResources.use(serve)

  private def serve(resources: (GameStore, BotStore, Option[PgGameStore], Client[IO], IO[Unit])): IO[Unit] =
    val (store, botStore, pgStore, httpClient, deliverer) = resources
    for
      registry <- GameRegistry.create(store = store)
      resumed  <- registry.resume
      _        <- IO.println(s"[play] resumed $resumed live game(s)").whenA(resumed > 0)
      botAuth  <- BotAuth.fromEnv(botStore)
      // Admin/env catalog roster (ADR-0014): open configured bots to human games at startup — the path for a bot that
      // can't self-flag via POST /bot/open-to-humans (e.g. a lost token). Persistence-only, like the catalog it feeds.
      _          <- pgStore.fold(IO.unit)(pg => CatalogRoster.applyFromEnv(pg).void)
      botEvents  <- BotEvents.create
      challenges <- Challenges.create(botEvents, registry)
      mintLimit  <- AnonMintLimiter.create()
      // Registration is rarer than anon minting by nature (one durable identity per team, not one per test session),
      // so it gets its own, much stricter per-IP budget.
      registerLimit <- AnonMintLimiter.create(limit = RegisterLimitPerHour)
      lobby         <- Lobby.create(registry)
      cors          <- Cors.fromEnv
      // The ladder scheduler is opt-in by env (LADDER_INTERVAL_SECONDS) — same "absence disables" idiom as
      // persistence/ingest above. Unset, the ladder never starts games on its own even if bots are on_ladder.
      ladderLoop <- LadderScheduler.configFromEnv match
        case None =>
          IO.println("[play][ladder] LADDER_INTERVAL_SECONDS unset: no automatic ladder pairings")
            .as(IO.never: IO[Unit])
        case Some(ladderConfig) =>
          LadderScheduler.create(botStore, registry, botEvents, ladderConfig).map(_.scheduler())
      // The strength cache (#181) is created unconditionally: StrengthRoutes below is mounted whenever persistence
      // is configured at all, independent of whether the rating batch (its only writer) ever actually runs.
      strengthCache <- StrengthCache.create
      // The rating batch (#119) is opt-in the same way (RATING_INTERVAL_SECONDS) — and additionally needs the
      // database: without PLAY_DB_URL there is no game_results queue to drain, so a set-but-useless env var gets a
      // loud warning instead of a silent no-op. It also owns refreshing `strengthCache` (#181): with the batch off,
      // GET /strength stays "not ready" forever — the same coupling rating updates and ladder auto-park already have.
      ratingLoop <- (RatingBatch.configFromEnv, pgStore) match
        case (None, _) =>
          IO.println("[play][rating] RATING_INTERVAL_SECONDS unset: no automatic rating updates")
            .as(IO.never: IO[Unit])
        case (Some(_), None) =>
          cats.effect.std
            .Console[IO]
            .errorln("[play][rating] RATING_INTERVAL_SECONDS set but PLAY_DB_URL unset: rating batch disabled")
            .as(IO.never: IO[Unit])
        case (Some(ratingConfig), Some(pg)) =>
          val batch =
            new RatingBatch(botStore, pg, pg, ratingConfig, strengthCache, StrengthReport.Config.configFromEnv)
          IO.pure(batch.scheduler())
      // Retention (#179) follows the same opt-in shape, and for this one the shape is a safety property, not just
      // consistency: it is the only scheduled task that DELETES, so leaving RETENTION_INTERVAL_SECONDS unset must be
      // the state that does nothing. It also needs the database for the obvious reason — nothing to prune in memory.
      retentionLoop <- (Retention.configFromEnv, pgStore) match
        case (None, _) =>
          IO.println("[play][retention] RETENTION_INTERVAL_SECONDS unset: ended snapshots are kept indefinitely")
            .as(IO.never: IO[Unit])
        case (Some(_), None) =>
          cats.effect.std
            .Console[IO]
            .errorln("[play][retention] RETENTION_INTERVAL_SECONDS set but PLAY_DB_URL unset: retention disabled")
            .as(IO.never: IO[Unit])
        case (Some(retentionConfig), Some(pg)) =>
          IO.println(
            s"[play][retention] enabled: every ${retentionConfig.interval}, pruning operational rows older than " +
              s"${retentionConfig.retentionDays} day(s)"
          ).as(new Retention(pg, retentionConfig).scheduler())
      // Registration triggers an outbound verification POST, so it shares the strict per-IP budget of /bot/register.
      webhookLimit <- AnonMintLimiter.create(limit = RegisterLimitPerHour)
      // The catalog wake probe (E3) also POSTs outward (the same unauthenticated handshake), but a visitor browsing
      // the catalog may reasonably click several bots — the generous anon-mint budget, not the strict register one.
      wakeLimit <- AnonMintLimiter.create()
      // Starting a catalog game (E4) is a heavier action than a mere wake ping, but playing several bot games in an
      // hour is completely normal usage — the same generous budget, not the strict register one.
      playBotLimit <- AnonMintLimiter.create()
      // Webhook push (F.2, #104) is opt-in the same way (WEBHOOK_TIMEOUT_SECONDS). Unlike the rating batch it does
      // NOT require the database: in-memory mode registers webhooks for the process's lifetime, matching how
      // registered-bot identities behave there. The service is a Resource because it owns its per-game runner
      // fibers (a Supervisor) — releasing it cancels them all. It is threaded to the routes as an Option — absent,
      // the /bot/webhook endpoints answer 503 and no delivery loop runs.
      webhookResource = Webhooks.configFromEnv match
        case None =>
          Resource
            .eval(IO.println("[play][webhook] WEBHOOK_TIMEOUT_SECONDS unset: webhook push disabled"))
            .as(None: Option[Webhooks])
        case Some(webhookConfig) =>
          Resource
            .eval(
              // The effective window, said out loud at boot: the client's deadlines are derived from it and used to
              // undercut it silently (#188), and a bot author cannot size their time management against a number
              // nobody prints.
              IO.println(
                s"[play][webhook] per-turn window ${webhookConfig.timeout.toSeconds}s " +
                  s"(client cut ${webhookConfig.clientTimeout.toSeconds}s, idle ${webhookConfig.clientIdleTimeout.toSeconds}s)"
              ) *> pgStore.fold(WebhookStore.inMemory)(pg => IO.pure(pg: WebhookStore))
            )
            .flatMap(webhookStore => Webhooks.create(registry, webhookStore, httpClient, webhookConfig))
            .map(Some(_))
      // The sweepers (seeks, pending challenges), the ladder scheduler, the rating batch, the webhook loop, and the
      // ingest deliverer are scoped to the server: they run while it runs and are cancelled with it, so a failure
      // surfaces instead of being silently dropped by a detached fiber.
      _ <- webhookResource.use { webhookService =>
        val loops = (
          deliverer.background,
          lobby.sweeper().background,
          challenges.sweeper().background,
          ladderLoop.background,
          ratingLoop.background,
          retentionLoop.background,
          webhookService.fold(IO.never: IO[Unit])(_.loop.void).background
        ).tupled
        loops.surround {
          // The leaderboard/profile API reads bots + game_results — DB-only seams, so without persistence the
          // routes are simply not mounted (404), same spirit as the rating batch above.
          val leaderboard =
            pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => LeaderboardRoutes(botStore, pg, pg))
          // Same DB-only gating: the human catalog reads the bots table's rating + description columns (ADR-0014).
          val catalog = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg =>
            CatalogRoutes(pg, botStore, webhookService, registry, wakeLimit, playBotLimit)
          )
          // A visitor's own finished games (#151) — same DB-only-seam idiom: no game_results projection without a
          // database, so the route is simply not mounted.
          val playerGames = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => PlayerRoutes(pg))
          // Same DB-only gating again (#181): `strengthCache` exists either way, but with no persistence there is no
          // rating batch to ever populate it, so mounting the route would just mean an eternal 503 instead of a 404.
          val strength = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(_ => StrengthRoutes(botStore, strengthCache))
          // The durable replay endpoint (#178) reads game_archive + game_results (the CRN reveal gate) — DB-only
          // seams again, same idiom as every route above.
          val history = pgStore.fold(org.http4s.HttpRoutes.empty[IO])(pg => HistoryRoutes(pg, pg))
          EmberServerBuilder
            .default[IO]
            .withHost(host)
            .withPort(port)
            .withHttpWebSocketApp(wsb =>
              cors(
                (HealthRoutes(version) <+> PlayRoutes(registry, wsb) <+> LobbyRoutes(lobby) <+> leaderboard <+>
                  catalog <+> playerGames <+> strength <+> history <+>
                  WebhookRoutes(botAuth, webhookService, webhookLimit) <+>
                  BotRoutes(
                    botAuth,
                    challenges,
                    botEvents,
                    registry,
                    lobby,
                    mintLimit,
                    registerLimit
                  )).orNotFound
              )
            )
            .build
            .useForever
        }
      }
    yield ()

  /** Per-IP hourly budget for `POST /bot/register` — a team registers a handful of identities, not thirty. */
  private val RegisterLimitPerHour = 5
