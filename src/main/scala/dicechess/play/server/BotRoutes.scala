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

final case class BotAccount(team: String, name: String, id: String) derives Codec.AsObject
final case class ChallengeTarget(team: String, name: String) derives Codec.AsObject
final case class BotGame(gameId: String) derives Codec.AsObject
final case class BotMove(moves: List[String]) derives Codec.AsObject

/** The third-party Bot API (Lichess-shaped): identity, the per-bot event stream, the challenge lifecycle, and the game
  * play surface (game event stream + move/resign).
  */
object BotRoutes:

  private val bearerChallenge = `WWW-Authenticate`(Challenge("Bearer", "dicechess-bot"))
  private val ndjsonType      = MediaType.unsafeParse("application/x-ndjson")

  def apply(auth: BotAuth, challenges: Challenges, events: BotEvents, registry: GameRegistry): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ GET -> Root / "bot" / "account" =>
        asBot(auth, req) match
          case Some(bot) => Ok(BotAccount(bot.team, bot.name, bot.externalId))
          case None      => Unauthorized(bearerChallenge)

      case req @ GET -> Root / "bot" / "stream" / "event" =>
        asBot(auth, req) match
          case Some(bot) => Ok(ndjson(events.stream(bot))).map(_.withContentType(`Content-Type`(ndjsonType)))
          case None      => Unauthorized(bearerChallenge)

      case req @ POST -> Root / "bot" / "challenge" =>
        asBot(auth, req) match
          case None      => Unauthorized(bearerChallenge)
          case Some(bot) =>
            req
              .attemptAs[ChallengeTarget]
              .value
              .flatMap:
                case Left(failure) => BadRequest(failure.message)
                case Right(target) =>
                  challenges.create(bot, Principal.Bot(target.team, target.name)).flatMap(Created(_))

      case req @ POST -> Root / "bot" / "challenge" / id / "accept" =>
        asBot(auth, req) match
          case None      => Unauthorized(bearerChallenge)
          case Some(bot) =>
            challenges
              .accept(bot, id)
              .flatMap:
                case Right(gameId)                         => Created(BotGame(gameId))
                case Left(Challenges.Rejected.NotFound)    => NotFound()
                case Left(Challenges.Rejected.NotYours)    => Forbidden()
                case Left(Challenges.Rejected.Failed(why)) => InternalServerError(why)

      case req @ POST -> Root / "bot" / "challenge" / id / "decline" =>
        asBot(auth, req) match
          case None      => Unauthorized(bearerChallenge)
          case Some(bot) =>
            challenges
              .decline(bot, id)
              .flatMap:
                case Right(())                             => Ok()
                case Left(Challenges.Rejected.NotFound)    => NotFound()
                case Left(Challenges.Rejected.NotYours)    => Forbidden()
                case Left(Challenges.Rejected.Failed(why)) => InternalServerError(why)

      case req @ GET -> Root / "bot" / "game" / "stream" / id =>
        asBot(auth, req) match
          case None      => Unauthorized(bearerChallenge)
          case Some(bot) =>
            seated(registry, id, bot): (room, _) =>
              Ok(ndjson(room.subscribe)).map(_.withContentType(`Content-Type`(ndjsonType)))

      case req @ POST -> Root / "bot" / "game" / id / "move" =>
        asBot(auth, req) match
          case None      => Unauthorized(bearerChallenge)
          case Some(bot) =>
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
        asBot(auth, req) match
          case None      => Unauthorized(bearerChallenge)
          case Some(bot) =>
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

  /** The authenticated bot for a request, if its Bearer token is valid. */
  private def asBot(auth: BotAuth, req: Request[IO]): Option[Principal.Bot] =
    bearer(req).flatMap(auth.authenticate).collect { case bot: Principal.Bot => bot }

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
      token
    }

  private def ndjson[A: Encoder](events: Stream[IO, A]): Stream[IO, Byte] =
    events.map(_.asJson.noSpaces + "\n").through(fs2.text.utf8.encode)
