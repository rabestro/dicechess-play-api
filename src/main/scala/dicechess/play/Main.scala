package dicechess.play

import cats.effect.{IO, IOApp, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import dicechess.play.server.{
  AnonMintLimiter,
  BotAuth,
  BotEvents,
  BotRoutes,
  Challenges,
  Cors,
  GameRegistry,
  HealthRoutes,
  Lobby,
  LobbyRoutes,
  PlayRoutes
}
import dicechess.play.ingest.IngestDeliverer
import dicechess.play.store.{BotStore, GameStore, PgGameStore}
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*

/** Boots the authoritative HTTP/WebSocket server. */
object Main extends IOApp.Simple:

  private val host    = host"0.0.0.0"
  private val port    = port"8080"
  private val version = sys.env.getOrElse("APP_VERSION", "dev")

  // Persistence is opt-in by env: with PLAY_DB_URL set, games snapshot into Postgres, live games are resumed on boot,
  // and registered bot identities are durable; with INGEST_URL/INGEST_TOKEN also set, finished games are delivered to
  // analytics from the durable outbox. Without PLAY_DB_URL the server runs in-memory exactly as before (games and
  // registered bots die with the process).
  private def appResources: Resource[IO, (GameStore, BotStore, IO[Unit])] =
    PgGameStore.configFromEnv match
      case None           => Resource.eval(BotStore.inMemory).map(bots => (GameStore.noop, bots, IO.never))
      case Some(dbConfig) =>
        PgGameStore.resource(dbConfig).flatMap { store =>
          IngestDeliverer.configFromEnv match
            case None =>
              val warn = cats.effect.std
                .Console[IO]
                .errorln("[play][ingest] INGEST_URL/INGEST_TOKEN unset: finished games accumulate in the outbox")
              Resource.pure((store, store, warn *> IO.never))
            case Some(ingestConfig) =>
              EmberClientBuilder
                .default[IO]
                .build
                .map(http => (store, store, IngestDeliverer(store, http, ingestConfig).loop.void))
        }

  def run: IO[Unit] = appResources.use(serve)

  private def serve(resources: (GameStore, BotStore, IO[Unit])): IO[Unit] =
    val (store, botStore, deliverer) = resources
    for
      registry   <- GameRegistry.create(store = store)
      resumed    <- registry.resume
      _          <- IO.println(s"[play] resumed $resumed live game(s)").whenA(resumed > 0)
      botAuth    <- BotAuth.fromEnv(botStore)
      botEvents  <- BotEvents.create
      challenges <- Challenges.create(botEvents, registry)
      mintLimit  <- AnonMintLimiter.create()
      // Registration is rarer than anon minting by nature (one durable identity per team, not one per test session),
      // so it gets its own, much stricter per-IP budget.
      registerLimit <- AnonMintLimiter.create(limit = RegisterLimitPerHour)
      lobby         <- Lobby.create(registry)
      cors          <- Cors.fromEnv
      // The sweepers (seeks, pending challenges) and the ingest deliverer are scoped to the server: they run while it
      // runs and are cancelled with it, so a failure surfaces instead of being silently dropped by a detached fiber.
      _ <- (deliverer.background, lobby.sweeper().background, challenges.sweeper().background).tupled
        .surround:
          EmberServerBuilder
            .default[IO]
            .withHost(host)
            .withPort(port)
            .withHttpWebSocketApp(wsb =>
              cors(
                (HealthRoutes(version) <+> PlayRoutes(registry, wsb) <+> LobbyRoutes(lobby) <+> BotRoutes(
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
    yield ()

  /** Per-IP hourly budget for `POST /bot/register` — a team registers a handful of identities, not thirty. */
  private val RegisterLimitPerHour = 5
