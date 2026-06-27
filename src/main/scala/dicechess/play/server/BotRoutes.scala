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

/** The third-party Bot API (Lichess-shaped): identity, the per-bot event stream, and challenge creation. Accepting a
  * challenge (which seats a game) and the game play endpoints arrive in later slices.
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

      case req @ POST -> Root / "bot" / "challenge" / team / name =>
        asBot(auth, req) match
          case Some(bot) => challenges.create(bot, Principal.Bot(team, name)).flatMap(Created(_))
          case None      => Unauthorized(bearerChallenge)

  /** The authenticated bot for a request, if its Bearer token is valid. */
  private def asBot(auth: BotAuth, req: Request[IO]): Option[Principal.Bot] =
    bearer(req).flatMap(auth.authenticate).collect { case bot: Principal.Bot => bot }

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
      token
    }

  private def ndjson[A: Encoder](events: Stream[IO, A]): Stream[IO, Byte] =
    events.map(_.asJson.noSpaces + "\n").through(fs2.text.utf8.encode)
