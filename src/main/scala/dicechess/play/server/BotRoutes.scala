package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{GameCommand, GameId, Principal, Seat}
import dicechess.play.game.GameRoom
import dicechess.play.wire.Codecs.given
import fs2.Stream
import io.circe.{Codec, Encoder}
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`, `WWW-Authenticate`}
import org.http4s.{AuthScheme, Challenge, Credentials, HttpRoutes, MediaType, Request, Response}

import scala.concurrent.duration.*

final case class BotAccount(team: String, name: String, id: String) derives Codec.AsObject
final case class ChallengeTarget(team: String, name: String) derives Codec.AsObject
final case class BotGame(gameId: String) derives Codec.AsObject
final case class BotMove(moves: List[String]) derives Codec.AsObject

/** Credentials returned by `POST /bot/anon`: the Bearer token plus the minted anonymous identity. */
final case class AnonBot(token: String, team: String, name: String, id: String) derives Codec.AsObject

/** The third-party Bot API (Lichess-shaped): identity, the per-bot event stream, the challenge lifecycle, and the game
  * play surface (game event stream + move/resign).
  */
object BotRoutes:

  private val bearerChallenge = `WWW-Authenticate`(Challenge("Bearer", "dicechess-bot"))
  private val ndjsonType      = MediaType.unsafeParse("application/x-ndjson")

  /** ndjson keep-alive cadence — under the ember server's 60s read-idle so an idle stream (a bot waiting for a
    * challenge, or the gap between turns) isn't dropped at either end. The blank line is ignored by clients.
    */
  private val KeepAlive: FiniteDuration = 25.seconds

  private object NameParam extends OptionalQueryParamDecoderMatcher[String]("name")

  def apply(auth: BotAuth, challenges: Challenges, events: BotEvents, registry: GameRegistry): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      // Zero-registration self-service: mint an ephemeral, unranked anonymous bot token for testing.
      case POST -> Root / "bot" / "anon" :? NameParam(name) =>
        auth.mintAnon(name).flatMap((token, bot) => Created(AnonBot(token, bot.team, bot.name, bot.externalId)))

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
              case Right(target) => challenges.create(bot, Principal.Bot(target.team, target.name)).flatMap(Created(_))

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

      case req @ POST -> Root / "bot" / "game" / id / "move" =>
        withBot(auth, req): bot =>
          req
            .attemptAs[BotMove]
            .value
            .flatMap:
              case Left(failure) => BadRequest(failure.message)
              case Right(move)   =>
                // Fire-and-forget: the outcome (TurnPlayed / Rejected / GameEnded) arrives on the event stream.
                seated(registry, id, bot)((room, seat) =>
                  room.submit(seat, GameCommand.SubmitTurn(move.moves)) *> Accepted()
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

  private[server] def ndjson[A: Encoder](
      events: Stream[IO, A],
      keepAlive: FiniteDuration = KeepAlive
  ): Stream[IO, Byte] =
    val lines      = events.map(_.asJson.noSpaces + "\n")
    val keepAlives = Stream.awakeEvery[IO](keepAlive).as("\n") // blank line; clients drop it
    lines.mergeHaltL(keepAlives).through(fs2.text.utf8.encode)
