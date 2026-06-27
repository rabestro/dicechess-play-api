package dicechess.play.server

import cats.effect.IO
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, Method, Request, Status}

class BotRoutesSuite extends munit.CatsEffectSuite:

  private val routes = BotRoutes(BotAuth.parse("acme|greedy|tok-123")).orNotFound

  private def get(token: Option[String]): Request[IO] =
    val base = Request[IO](Method.GET, uri"/bot/account")
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))

  test("GET /bot/account returns the bot identity for a valid token"):
    routes
      .run(get(Some("tok-123")))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp
          .as[BotAccount]
          .map: account =>
            assertEquals(account.team, "acme")
            assertEquals(account.name, "greedy")
            assertEquals(account.id, "bot:team:acme:greedy")

  test("GET /bot/account is 401 without a token"):
    routes.run(get(None)).map(resp => assertEquals(resp.status, Status.Unauthorized))

  test("GET /bot/account is 401 for an unknown token"):
    routes.run(get(Some("nope"))).map(resp => assertEquals(resp.status, Status.Unauthorized))
