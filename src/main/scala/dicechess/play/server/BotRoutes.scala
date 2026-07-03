package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{Challenge, Clocks, GameCommand, GameId, GameStatus, Principal, Seat, TimeControl}
import dicechess.play.game.GameRoom
import dicechess.play.wire.Codecs.given
import fs2.Stream
import io.circe.{Codec, Encoder}
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`, `Retry-After`, `WWW-Authenticate`}
import org.http4s.{AuthScheme, Challenge as HttpChallenge, Credentials, HttpRoutes, MediaType, Request, Response}
import org.typelevel.ci.*

import scala.concurrent.duration.*

final case class BotAccount(team: String, name: String, id: String) derives Codec.AsObject
final case class ChallengeTarget(team: String, name: String, timeControl: Option[TimeControl] = None)
    derives Codec.AsObject
final case class BotGame(gameId: String) derives Codec.AsObject
final case class BotMove(moves: List[String]) derives Codec.AsObject
final case class BotSeed(seed: String) derives Codec.AsObject

/** Credentials returned by `POST /bot/anon`: the Bearer token plus the minted anonymous identity. */
final case class AnonBot(token: String, team: String, name: String, id: String) derives Codec.AsObject

/** A registration request: the durable identity to claim. Both parts must be lowercase slugs. */
final case class RegisterBot(team: String, name: String) derives Codec.AsObject

/** Credentials returned by `POST /bot/register`: the Bearer token (shown exactly once — only its hash is stored) plus
  * the claimed durable identity.
  */
final case class BotRegistered(token: String, team: String, name: String, id: String) derives Codec.AsObject

/** The fresh token from `POST /bot/token` (rotation). Shown exactly once; the old token is already invalid. */
final case class RotatedToken(token: String) derives Codec.AsObject

/** The challenge-create response: the pending challenge's fields plus whether the target currently holds an account
  * stream. Advisory — an offline target can still discover the challenge via `GET /bot/challenges` until it expires.
  */
final case class ChallengeCreated(
    id: String,
    challenger: Principal,
    target: Principal,
    timeControl: TimeControl,
    targetOnline: Boolean
) derives Codec.AsObject

/** The caller's pending challenges: addressed to it (`in` — accept/decline by id) and created by it (`out`). */
final case class BotChallenges(in: List[Challenge], out: List[Challenge]) derives Codec.AsObject

/** A live game the caller is seated in — enough to decide whether to act; fetch `GET /games/{id}` for the position and
  * `GET /games/{id}/moves` for the legal-move tree.
  */
final case class BotActiveGame(
    gameId: String,
    seat: Seat,
    activeSeat: Seat,
    dicePending: Boolean,
    timeControl: TimeControl,
    clocks: Option[Clocks],
    version: Long
) derives Codec.AsObject

final case class BotGames(games: List[BotActiveGame]) derives Codec.AsObject

/** The synchronous verdict on a submitted turn: `applied` with the `TurnPlayed` version (200), or refused with the same
  * reason the stream's `Rejected` carries (409). A fire-and-forget bot simply ignores the body.
  */
final case class MoveOutcome(applied: Boolean, version: Option[Long] = None, reason: Option[String] = None)
    derives Codec.AsObject

/** The third-party Bot API (Lichess-shaped): identity, the per-bot event stream, the challenge lifecycle, and the game
  * play surface (game event stream + move/resign).
  */
object BotRoutes:

  private val bearerChallenge = `WWW-Authenticate`(HttpChallenge("Bearer", "dicechess-bot"))
  private val ndjsonType      = MediaType.unsafeParse("application/x-ndjson")

  /** ndjson keep-alive cadence — under the ember server's 60s read-idle so an idle stream (a bot waiting for a
    * challenge, or the gap between turns) isn't dropped at either end. The blank line is ignored by clients.
    */
  private val KeepAlive: FiniteDuration = 25.seconds

  /** How long a move submit waits for the writer's verdict before degrading to the legacy fire-and-forget 202. The
    * writer answers in microseconds; this only bites if the room fiber is wedged — never hang the HTTP call on it.
    */
  private val VerdictTimeout: FiniteDuration = 5.seconds

  private object NameParam extends OptionalQueryParamDecoderMatcher[String]("name")

  def apply(
      auth: BotAuth,
      challenges: Challenges,
      events: BotEvents,
      registry: GameRegistry,
      limiter: AnonMintLimiter,
      registerLimiter: AnonMintLimiter
  ): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      // Zero-registration self-service: mint an ephemeral, unranked anonymous bot token for testing.
      // The only un-gated route, so it carries its own per-IP rate limit.
      case req @ POST -> Root / "bot" / "anon" :? NameParam(name) =>
        limiter
          .attempt(clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("anonymous mint rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) =>
              auth.mintAnon(name).flatMap((token, bot) => Created(AnonBot(token, bot.team, bot.name, bot.externalId)))

      // Durable self-service identity (#70): unlike /bot/anon, the identity survives server restarts, so together
      // with GET /bot/games a registered bot resumes its games after a deploy instead of forfeiting them. Un-gated
      // like the anon mint, with its own (stricter) per-IP budget.
      case req @ POST -> Root / "bot" / "register" =>
        registerLimiter
          .attempt(clientIp(req))
          .flatMap:
            case Left(retryAfter) =>
              TooManyRequests("registration rate limit exceeded — retry later")
                .map(_.putHeaders(`Retry-After`.unsafeFromLong(math.max(1L, retryAfter.toSeconds))))
            case Right(()) =>
              req
                .attemptAs[RegisterBot]
                .value
                .flatMap:
                  case Left(failure) => BadRequest(failure.message)
                  case Right(body)   =>
                    auth
                      .register(body.team, body.name)
                      .flatMap:
                        case Right((token, bot)) =>
                          Created(BotRegistered(token, bot.team, bot.name, bot.externalId))
                        case Left(BotAuth.RegisterRejected.InvalidSlug) =>
                          BadRequest("team and name must be lowercase slugs: [a-z0-9][a-z0-9-]*, at most 32 chars")
                        case Left(BotAuth.RegisterRejected.ReservedTeam) =>
                          BadRequest("this team is reserved")
                        case Left(BotAuth.RegisterRejected.Taken) =>
                          Conflict("this bot identity is already taken")

      // Rotate the caller's token: the old one stops authenticating immediately, the new one is shown exactly once.
      // Registered bots only — anon tokens are re-minted, static ones live in the server env.
      case req @ POST -> Root / "bot" / "token" =>
        withBot(auth, req): bot =>
          auth
            .rotate(bot)
            .flatMap:
              case Some(token) => Ok(RotatedToken(token))
              case None        => Forbidden("only a registered bot can rotate its token")

      case req @ GET -> Root / "bot" / "account" =>
        withBot(auth, req)(bot => Ok(BotAccount(bot.team, bot.name, bot.externalId)))

      case req @ GET -> Root / "bot" / "stream" / "event" =>
        withBot(auth, req): bot =>
          Ok(ndjson(events.stream(bot))).map(_.withContentType(`Content-Type`(ndjsonType)))

      case req @ POST -> Root / "bot" / "challenge" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[ChallengeTarget]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(target) =>
                challenges
                  .create(
                    bot,
                    Principal.Bot(target.team, target.name),
                    target.timeControl.getOrElse(TimeControl.Unlimited)
                  )
                  .flatMap:
                    case Right(created) =>
                      val ch = created.challenge
                      Created(ChallengeCreated(ch.id, ch.challenger, ch.target, ch.timeControl, created.targetOnline))
                    case Left(Challenges.CreateRejected.SelfChallenge) =>
                      BadRequest("a bot cannot challenge itself")
                    case Left(Challenges.CreateRejected.TooManyPending) =>
                      TooManyRequests("too many pending challenges — accept, decline, or let them expire")

      // The polling counterpart of the account stream: pending challenges involving the caller. `in` entries are
      // claimable by id; an `out` entry vanishing means it was accepted (see GET /bot/games), declined, or expired.
      case req @ GET -> Root / "bot" / "challenges" =>
        withBot(auth, req): bot =>
          challenges.listFor(bot).flatMap((in, out) => Ok(BotChallenges(in, out)))

      // The polling counterpart of `GameStart` — and the post-restart recovery path: every live game the caller is
      // seated in, whether or not it ever saw the start event. The registry serves this from a per-player index, so
      // the cost is O(the caller's games), not O(every game on the node).
      case req @ GET -> Root / "bot" / "games" =>
        withBot(auth, req): bot =>
          registry
            .gamesFor(bot)
            .flatMap(_.traverse { (id, room) =>
              (seatOf(room, bot), room.snapshot).mapN: (seat, s) =>
                // A just-ended room can linger until the registry evicts it; a listing is for live games only.
                seat
                  .filter(_ => s.status == GameStatus.Active)
                  .map: st =>
                    BotActiveGame(id.value, st, s.activeSeat, s.dicePending, s.timeControl, s.clocks, s.version)
            })
            .flatMap(games => Ok(BotGames(games.flatten)))

      case req @ POST -> Root / "bot" / "challenge" / id / "accept" =>
        withBot(auth, req): bot =>
          challenges
            .accept(bot, id)
            .flatMap:
              case Right(gameId)                         => Created(BotGame(gameId))
              case Left(Challenges.Rejected.NotFound)    => NotFound()
              case Left(Challenges.Rejected.NotYours)    => Forbidden()
              case Left(Challenges.Rejected.Failed(why)) => InternalServerError(why)

      case req @ POST -> Root / "bot" / "challenge" / id / "decline" =>
        withBot(auth, req): bot =>
          challenges
            .decline(bot, id)
            .flatMap:
              case Right(())                             => Ok()
              case Left(Challenges.Rejected.NotFound)    => NotFound()
              case Left(Challenges.Rejected.NotYours)    => Forbidden()
              case Left(Challenges.Rejected.Failed(why)) => InternalServerError(why)

      case req @ GET -> Root / "bot" / "game" / "stream" / id =>
        withBot(auth, req): bot =>
          seated(registry, id, bot): (room, _) =>
            Ok(ndjson(room.subscribe)).map(_.withContentType(`Content-Type`(ndjsonType)))

      case req @ POST -> Root / "bot" / "game" / id / "seed" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[BotSeed]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(body)   =>
                // Provably-fair dice (#13): contribute post-commit entropy before the opening roll. Fire-and-forget;
                // a too-late, duplicate, or malformed seed is dropped (or surfaces as `Rejected` on the stream).
                seated(registry, id, bot)((room, seat) =>
                  room.submit(seat, GameCommand.SubmitSeed(body.seed)) *> Accepted()
                )

      case req @ POST -> Root / "bot" / "game" / id / "move" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[BotMove]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(move)   =>
                // Synchronous verdict (the writer processes a command in microseconds — validation is a cache lookup):
                // 200 applied with the TurnPlayed version, 409 refused with the stream Rejected's reason. The stream
                // events are unchanged; a wedged writer degrades to the old fire-and-forget 202 instead of hanging.
                seated(registry, id, bot)((room, seat) =>
                  room
                    .submitTurn(seat, move.moves)
                    .flatMap:
                      case GameRoom.TurnVerdict.Applied(version) =>
                        Ok(MoveOutcome(applied = true, version = Some(version)))
                      case GameRoom.TurnVerdict.Refused(reason) =>
                        Conflict(MoveOutcome(applied = false, reason = Some(reason)))
                    .timeoutTo(VerdictTimeout, Accepted())
                )

      case req @ POST -> Root / "bot" / "game" / id / "resign" =>
        withBot(auth, req): bot =>
          seated(registry, id, bot)((room, seat) => room.submit(seat, GameCommand.Resign) *> Accepted())

  /** Run `action` against the room and the caller's seat in it; 404 if no such game or the bot isn't seated there. */
  private def seated(registry: GameRegistry, id: String, bot: Principal.Bot)(
      action: (GameRoom, Seat) => IO[Response[IO]]
  ): IO[Response[IO]] =
    registry
      .get(GameId(id))
      .flatMap:
        case None       => NotFound()
        case Some(room) =>
          seatOf(room, bot).flatMap:
            case None       => NotFound()
            case Some(seat) => action(room, seat)

  private def seatOf(room: GameRoom, bot: Principal): IO[Option[Seat]] =
    room.seating.map(_.collectFirst { case (seat, principal) if principal == bot => seat })

  /** Run `f` with the authenticated bot, or answer 401 with a Bearer challenge. */
  private def withBot(auth: BotAuth, req: Request[IO])(f: Principal.Bot => IO[Response[IO]]): IO[Response[IO]] =
    asBot(auth, req).flatMap:
      case Some(bot) => f(bot)
      case None      => Unauthorized(bearerChallenge)

  /** The authenticated bot for a request, if its Bearer token is valid (static or live anonymous). */
  private def asBot(auth: BotAuth, req: Request[IO]): IO[Option[Principal.Bot]] =
    bearer(req) match
      case None        => IO.pure(None)
      case Some(token) => auth.authenticate(token).map(_.collect { case bot: Principal.Bot => bot })

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
      token
    }

  /** Client IP for rate-limiting. Behind the Cloudflare tunnel the socket peer is the tunnel, so the real client is in
    * `CF-Connecting-IP` (then `X-Forwarded-For`, then the direct peer for local/dev).
    */
  private def clientIp(req: Request[IO]): String =
    req.headers
      .get(ci"CF-Connecting-IP")
      .map(_.head.value)
      .orElse(req.headers.get(ci"X-Forwarded-For").map(_.head.value.split(',').head.trim))
      .orElse(req.remoteAddr.map(_.toString))
      .getOrElse("unknown")

  private[server] def ndjson[A: Encoder](
      events: Stream[IO, A],
      keepAlive: FiniteDuration = KeepAlive
  ): Stream[IO, Byte] =
    val lines      = events.map(_.asJson.noSpaces + "\n")
    val keepAlives = Stream.awakeEvery[IO](keepAlive).as("\n") // blank line; clients drop it
    lines.mergeHaltL(keepAlives).through(fs2.text.utf8.encode)
