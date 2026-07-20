package dicechess.play.server

import cats.effect.{IO, Ref, Resource}
import cats.effect.std.{Console, Supervisor}
import cats.syntax.all.*
import dicechess.play.core.{GameEvent, GameId, GameStatus, Principal, PublicGameState, Seat}
import dicechess.play.game.GameRoom
import dicechess.play.store.{BotWebhook, WebhookStore}
import dicechess.play.wire.Codecs.given
import io.circe.Codec
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.headers.`Content-Type`
import org.http4s.{Header, MediaType, Method, Request, Uri}
import org.http4s.client.Client
import org.typelevel.ci.CIString

import java.time.Instant
import scala.concurrent.duration.*

/** The turn envelope POSTed to a bot's webhook: the existing wire vocabulary verbatim — `state` is the same
  * `PublicGameState` every snapshot and `GET /games/{id}` serve (dfen with the pending dice pool, clocks, inline
  * `legalMoves` under the same cap — `null` means fetch `GET /games/{id}/moves`), plus which seat the bot holds. The
  * expected response is the move-endpoint's own request shape: `{"moves":["e2e4",...]}`.
  */
final case class WebhookEnvelope(`type`: String, gameId: String, seat: Seat, state: PublicGameState)
    derives Codec.AsObject

/** The ownership handshake POSTed at registration: the endpoint must answer `200 {"nonce":"<same value>"}` before any
  * game data is ever sent to the URL.
  */
final case class WebhookVerification(`type`: String, nonce: String) derives Codec.AsObject

final private case class WebhookNonceEcho(nonce: String) derives Codec.AsObject

/** Synchronous webhook delivery (F.2, #104; design: ADR-0013): when it is a registered bot's turn, the server POSTs the
  * game state to the bot's verified callback URL and applies the HTTP response body as the move — one component
  * covering the ownership handshake (`register`) and the delivery loop (`loop`).
  *
  * '''Single-writer respected''': the per-game runner is an ordinary room subscriber that feeds `submitTurn` — a
  * command source exactly like a WebSocket player or a polling bot, never a second writer.
  *
  * '''Reliability is the clock''': delivery is single-attempt with a bounded timeout (`min(config, the mover's
  * remaining clock)`); on timeout / non-200 / garbage the runner does nothing — the room's own deadline forfeits the
  * game exactly as it would for a polling bot that stopped polling. No retries, no dead-letter, no dispatcher state.
  *
  * '''Delivery rate is structurally bounded''': a bot receives at most one POST per turn of a game it is seated in,
  * games are bounded by the scheduler's pair cap and the challenge flow — there is no queue an attacker could pump. The
  * registration endpoint (the only caller-triggered outbound POST) carries its own per-IP limiter in the routes.
  *
  * The scan loop discovers rooms via `registry.list` (cheap: an in-memory map), so webhook runners attach for games
  * however they started — challenge, seek, ladder scheduler — and re-attach automatically after a restart's `resume`.
  * Deliveries re-read the registration per turn, so a `DELETE /bot/webhook` or a re-register (new URL/secret) takes
  * effect at the next turn, not the next game.
  */
final class Webhooks private (
    registry: GameRegistry,
    store: WebhookStore,
    client: Client[IO],
    checkUrl: String => IO[Either[String, Uri]],
    config: Webhooks.Config,
    attached: Ref[IO, Set[(GameId, Seat)]],
    runners: Supervisor[IO]
):
  import Webhooks.*

  // ── registration (ownership handshake) ──────────────────────────────────────

  /** Verify ownership of `url` and store the registration: mint a fresh secret and nonce, POST the verification
    * envelope (signed — the shape every future delivery will have), and require `200 {"nonce": <echo>}` back. Only then
    * is the webhook stored; the secret is returned to the caller exactly once. Errors are values for the routes to
    * answer 422 with.
    */
  def register(bot: Principal.Bot, url: String): IO[Either[String, BotWebhook]] =
    checkUrl(url).flatMap:
      case Left(reason) => IO.pure(Left(reason))
      case Right(_)     =>
        for
          secret <- WebhookSecurity.randomHex(SecretBytes)
          nonce  <- WebhookSecurity.randomHex(NonceBytes)
          body = WebhookVerification("verification", nonce).asJson.noSpaces
          answer <- post(url, secret, body, config.timeout)
          stored <- answer match
            case Left(reason)  => IO.pure(Left(s"verification failed: $reason"))
            case Right(echoed) =>
              decode[WebhookNonceEcho](echoed) match
                case Right(WebhookNonceEcho(`nonce`)) =>
                  IO.realTime.flatMap { now =>
                    val hook = BotWebhook(bot.team, bot.name, url, secret, Instant.ofEpochMilli(now.toMillis))
                    store.put(hook).as(Right(hook))
                  }
                case Right(_) => IO.pure(Left("verification failed: endpoint echoed a different nonce"))
                case Left(_)  => IO.pure(Left("verification failed: endpoint did not answer {\"nonce\": ...}"))
        yield stored

  def info(bot: Principal.Bot): IO[Option[BotWebhook]] = store.get(bot.team, bot.name)

  def remove(bot: Principal.Bot): IO[Boolean] = store.delete(bot.team, bot.name)

  // ── delivery ────────────────────────────────────────────────────────────────

  /** Scan → attach → sleep, forever. Scoped to the server by the caller (`.background`), like the other loops. */
  def loop: IO[Nothing] =
    (attachSweep.handleErrorWith(e => Console[IO].errorln(s"[play][webhook] sweep failed: $e")) *>
      IO.sleep(config.scanEvery)).foreverM

  /** One sweep (exposed for tests): attach a runner for every (live game, seat) held by a bot with a registered webhook
    * that doesn't have one yet. The loop is the only caller, so attachment never races itself.
    */
  def attachSweep: IO[Unit] =
    registry.list.flatMap(_.traverse_ { (id, room) =>
      room.hasEnded.flatMap:
        case true  => IO.unit
        case false =>
          room.seating.flatMap(_.toList.traverse_ {
            case (seat, bot: Principal.Bot) =>
              attached.get.flatMap: live =>
                if live.contains((id, seat)) then IO.unit
                else
                  store.get(bot.team, bot.name).flatMap {
                    case None    => IO.unit
                    case Some(_) =>
                      // Supervised, not `.start`-detached (review): the runners belong to the service's own
                      // lifecycle, so releasing the `Webhooks` resource cancels every in-flight runner instead
                      // of leaving them running after shutdown — the same "nothing silently detached" doctrine
                      // the other background loops follow.
                      attached.update(_ + ((id, seat))) *>
                        runners
                          .supervise(run(id, room, seat, bot).guarantee(attached.update(_ - ((id, seat)))))
                          .void
                  }
            case _ => IO.unit
          })
    })

  /** The per-(game, seat) runner: an ordinary subscriber that reacts to "your move" events until the game ends. The
    * subscription's snapshot-then-live overlap can show one version twice — `lastVersion` dedupes, and it advances to
    * whatever state each delivery actually saw, so a turn that was already answered from a fresher snapshot isn't
    * re-answered when its own event arrives.
    */
  private def run(id: GameId, room: GameRoom, seat: Seat, bot: Principal.Bot): IO[Unit] =
    Ref.of[IO, Long](-1L).flatMap { lastVersion =>
      room.subscribe
        .evalMap { event =>
          val actionable = event match
            case GameEvent.Snapshot(v, state, _) =>
              Option.when(state.status == GameStatus.Active && state.dicePending && state.activeSeat == seat)(v)
            case GameEvent.DiceRolled(v, rolledFor, _, _, _, _) =>
              Option.when(rolledFor == seat)(v)
            case _ => None
          actionable match
            case None    => IO.unit
            case Some(v) =>
              lastVersion.get.flatMap: last =>
                if v <= last then IO.unit
                else
                  deliverTurn(id, room, seat, bot, lastVersion)
                    .handleErrorWith(e => Console[IO].errorln(s"[play][webhook] game ${id.value}: delivery died: $e"))
        }
        .compile
        .drain
    }

  /** One turn's single delivery attempt: re-read the registration (rotation-aware), re-check against a FRESH snapshot
    * that it is still this seat's move (the triggering event may be stale), POST the envelope, and feed the answered
    * moves to the room. Every failure path only logs — the clock is the reliability mechanism.
    */
  private def deliverTurn(
      id: GameId,
      room: GameRoom,
      seat: Seat,
      bot: Principal.Bot,
      lastVersion: Ref[IO, Long]
  ): IO[Unit] =
    (store.get(bot.team, bot.name), room.snapshot).flatMapN {
      case (None, _)           => IO.unit // deleted mid-game: stop delivering, exactly as documented on DELETE
      case (Some(hook), state) =>
        val stillOurMove = state.status == GameStatus.Active && state.dicePending && state.activeSeat == seat
        if !stillOurMove then IO.unit
        else
          // The delivery answers whatever roll the fresh snapshot carries — possibly newer than the triggering
          // event — so the dedupe cursor advances to the state actually sent, not the event that woke us.
          lastVersion.set(state.version) *> {
            val body    = WebhookEnvelope("yourTurn", id.value, seat, state).asJson.noSpaces
            val budget  = state.clocks.map(c => (if seat == Seat.White then c.white else c.black).millis)
            val timeout = budget.fold(config.timeout)(_.min(config.timeout)).max(1.millisecond)
            post(hook.url, hook.secret, body, timeout).flatMap {
              case Left(reason) =>
                Console[IO].errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: $reason (clock decides)")
              case Right(answer) =>
                decode[BotMove](answer) match
                  case Left(_) =>
                    Console[IO].errorln(
                      s"[play][webhook] game ${id.value} ${bot.externalId}: unparseable response (clock decides)"
                    )
                  case Right(BotMove(Nil)) =>
                    // An explicit empty answer: the bot declines to move. There is no voluntary pass in the rules
                    // (forced passes are played by the server before delivery), so this simply leaves the clock
                    // running — same outcome as not answering, but it closes the connection promptly.
                    Console[IO].errorln(s"[play][webhook] game ${id.value} ${bot.externalId}: declined (empty moves)")
                  case Right(BotMove(moves)) =>
                    room
                      .submitTurn(seat, moves)
                      .flatMap:
                        case GameRoom.TurnVerdict.Applied(_)      => IO.unit
                        case GameRoom.TurnVerdict.Refused(reason) =>
                          Console[IO].errorln(
                            s"[play][webhook] game ${id.value} ${bot.externalId}: refused: $reason (clock decides)"
                          )
            }
          }
    }

  /** One signed POST with the full security posture: the URL re-passes the guard (fresh resolve at send time — the
    * anti-rebinding property), the body is signed with the per-bot secret, redirects are never followed (no redirect
    * middleware on the client), and the response read is size-capped. Left is a short reason for logs/422s.
    */
  private def post(url: String, secret: String, body: String, timeout: FiniteDuration): IO[Either[String, String]] =
    checkUrl(url).flatMap:
      case Left(reason) => IO.pure(Left(reason))
      case Right(uri)   =>
        IO.realTime.map(_.toSeconds).flatMap { ts =>
          val request = Request[IO](Method.POST, uri)
            .withEntity(body)
            .withContentType(`Content-Type`(MediaType.application.json))
            .putHeaders(
              Header.Raw(CIString(WebhookSecurity.SignatureHeader), WebhookSecurity.sign(secret, ts, body)),
              Header.Raw(CIString(WebhookSecurity.TimestampHeader), ts.toString)
            )
          client
            .run(request)
            .use { response =>
              // One byte past the cap is read so an oversized body is REJECTED, not silently truncated
              // (review): a truncated prefix that happens to parse must never pass for the real answer.
              response.body
                .take(MaxResponseBytes + 1)
                .compile
                .to(Array)
                .map(bytes => (response.status.code, bytes))
            }
            .timeout(timeout)
            .attempt
            .flatMap:
              case Right((200, bytes)) if bytes.length > MaxResponseBytes =>
                IO.pure(Left("endpoint answered with an oversized body"))
              case Right((200, bytes)) => IO.pure(Right(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
              case Right((code, _))    => IO.pure(Left(s"endpoint answered HTTP $code"))
              case Left(error)         =>
                // The transport detail (exception messages embed resolved addresses and distinguish refused
                // from timed-out) goes to the server log only; the caller-visible reason stays generic so a
                // 422 can't be used as a connectivity oracle against internal hosts (review).
                Console[IO]
                  .errorln(s"[play][webhook] POST failed: ${error.toString.take(200)}")
                  .as(Left("could not reach the endpoint"))
        }

object Webhooks:

  /** Webhook secrets are HMAC keys: 32 bytes matches the SHA-256 block-derived key advice. Nonces only need to be
    * unguessable within one handshake.
    */
  private val SecretBytes = 32
  private val NonceBytes  = 16

  /** Response-read cap: a `{"moves":[...]}` answer is bytes, not megabytes — the cap bounds what a hostile endpoint can
    * make the server buffer. A truncated body simply fails JSON decoding and is treated as garbage.
    */
  private val MaxResponseBytes = 65536L

  final case class Config(timeout: FiniteDuration, scanEvery: FiniteDuration = 2.seconds)

  object Config:
    /** Same split as `LadderScheduler.Config.fromValues`: the raw value comes in, only a strictly positive integer
      * enables the feature — a zero/negative/garbled timeout is treated as absent rather than busy-looping or disabling
      * deliveries silently at runtime.
      */
    def fromValues(timeoutSecondsRaw: Option[String]): Option[Config] =
      timeoutSecondsRaw.filter(_.nonEmpty).flatMap(_.toIntOption).filter(_ > 0).map(s => Config(s.seconds))

  /** Opt-in by env, the same "absence disables" idiom as ingest/ladder/rating: `WEBHOOK_TIMEOUT_SECONDS` both enables
    * webhooks (routes + dispatcher) and bounds each delivery attempt (10–30 s is the sensible range; the effective
    * per-turn timeout is additionally capped by the mover's remaining clock).
    */
  def configFromEnv: Option[Config] = Config.fromValues(sys.env.get("WEBHOOK_TIMEOUT_SECONDS"))

  /** A `Resource` because the service OWNS its per-game runner fibers (a `Supervisor`): releasing it cancels every
    * in-flight runner, so webhook delivery can never outlive the server that started it.
    */
  def create(
      registry: GameRegistry,
      store: WebhookStore,
      client: Client[IO],
      config: Config,
      checkUrl: String => IO[Either[String, Uri]] = WebhookSecurity.checkPublicHttps
  ): Resource[IO, Webhooks] =
    Supervisor[IO](await = false).evalMap { runners =>
      Ref
        .of[IO, Set[(GameId, Seat)]](Set.empty)
        .map(new Webhooks(registry, store, client, checkUrl, config, _, runners))
    }
