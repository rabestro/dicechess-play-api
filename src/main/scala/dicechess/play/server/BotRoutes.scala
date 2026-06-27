package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.wire.Codecs.given
import fs2.Stream
import io.circe.{Codec, Encoder}
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `Content-Type`, `WWW-Authenticate`}
import org.http4s.{AuthScheme, Challenge, Credentials, HttpRoutes, MediaType, Request}

final case class BotAccount(team: String, name: String, id: String) derives Codec.AsObject
final case class ChallengeTarget(team: String, name: String) derives Codec.AsObject
final case class BotGame(gameId: String) derives Codec.AsObject

/** The third-party Bot API (Lichess-shaped): identity, the per-bot event stream, and the challenge lifecycle. The game
  * event stream and idempotent move/resign endpoints arrive in the next slice.
  */
object BotRoutes:

  private val bearerChallenge = `WWW-Authenticate`(Challenge("Bearer", "dicechess-bot"))
  private val ndjsonType      = MediaType.unsafeParse("application/x-ndjson")

  def apply(auth: BotAuth, challenges: Challenges, events: BotEvents): HttpRoutes[IO] =
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

  /** The authenticated bot for a request, if its Bearer token is valid. */
  private def asBot(auth: BotAuth, req: Request[IO]): Option[Principal.Bot] =
    bearer(req).flatMap(auth.authenticate).collect { case bot: Principal.Bot => bot }

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
      token
    }

  private def ndjson[A: Encoder](events: Stream[IO, A]): Stream[IO, Byte] =
    events.map(_.asJson.noSpaces + "\n").through(fs2.text.utf8.encode)
