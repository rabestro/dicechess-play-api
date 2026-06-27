package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Challenge, Principal}
import dicechess.play.wire.Codecs.given
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}

class BotRoutesSuite extends munit.CatsEffectSuite:

  private val auth = BotAuth.parse("acme|alice|tok-alice")

  private def app: IO[HttpApp[IO]] =
    for
      events     <- BotEvents.create
      challenges <- Challenges.create(events)
    yield BotRoutes(auth, challenges, events).orNotFound

  private def request(method: Method, uri: Uri, token: Option[String]): Request[IO] =
    val base = Request[IO](method, uri)
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))

  test("GET /bot/account returns the bot identity for a valid token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", Some("tok-alice"))))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp
          .as[BotAccount]
          .map: account =>
            assertEquals(account.team, "acme")
            assertEquals(account.name, "alice")
            assertEquals(account.id, "bot:team:acme:alice")

  test("GET /bot/account is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("GET /bot/account is 401 for an unknown token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", Some("nope"))))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("GET /bot/stream/event is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/stream/event", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("POST /bot/challenge creates a challenge from the authenticated bot"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge/acme/bob", Some("tok-alice"))))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Created)
        resp
          .as[Challenge]
          .map: challenge =>
            assertEquals(challenge.challenger, Principal.Bot("acme", "alice"))
            assertEquals(challenge.target, Principal.Bot("acme", "bob"))
            assert(challenge.id.nonEmpty)

  test("POST /bot/challenge is 401 without a token"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge/acme/bob", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))
