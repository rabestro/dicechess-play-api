package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import io.circe.Codec
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.dsl.io.*
import org.http4s.headers.{Authorization, `WWW-Authenticate`}
import org.http4s.{AuthScheme, Challenge, Credentials, HttpRoutes, Request}

final case class BotAccount(team: String, name: String, id: String) derives Codec.AsObject

/** The third-party Bot API (Lichess-shaped). For now just identity; the game event stream and idempotent move/resign
  * endpoints arrive in later slices.
  */
object BotRoutes:

  private val challenge = `WWW-Authenticate`(Challenge("Bearer", "dicechess-bot"))

  def apply(auth: BotAuth): HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case req @ GET -> Root / "bot" / "account" =>
        bearer(req).flatMap(auth.authenticate) match
          case Some(bot: Principal.Bot) => Ok(BotAccount(bot.team, bot.name, bot.externalId))
          case _                        => Unauthorized(challenge)

  private def bearer(req: Request[IO]): Option[String] =
    req.headers.get[Authorization].collect { case Authorization(Credentials.Token(AuthScheme.Bearer, token)) =>
      token
    }
