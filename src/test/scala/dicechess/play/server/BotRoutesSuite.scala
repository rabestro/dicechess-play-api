package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{Challenge, Principal}
import dicechess.play.wire.Codecs.given
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}

class BotRoutesSuite extends munit.CatsEffectSuite:

  private val auth = BotAuth.parse("acme|alice|tok-alice,acme|bob|tok-bob")

  private def app: IO[HttpApp[IO]] =
    for
      events     <- BotEvents.create
      registry   <- GameRegistry.create
      challenges <- Challenges.create(events, registry)
    yield BotRoutes(auth, challenges, events).orNotFound

  private def request(method: Method, uri: Uri, token: Option[String]): Request[IO] =
    val base = Request[IO](method, uri)
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))

  private def challengeBobAsAlice(service: HttpApp[IO]): IO[Challenge] =
    service
      .run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "bob")))
      .flatMap(_.as[Challenge])

  test("GET /bot/account returns the bot identity for a valid token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", Some("tok-alice"))))
      .flatMap: resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[BotAccount].map(a => assertEquals(a, BotAccount("acme", "alice", "bot:team:acme:alice")))

  test("GET /bot/account is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/account", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("GET /bot/stream/event is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/stream/event", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("POST /bot/challenge creates a challenge from the authenticated bot"):
    app
      .flatMap(challengeBobAsAlice)
      .map: challenge =>
        assertEquals(challenge.challenger, Principal.Bot("acme", "alice"))
        assertEquals(challenge.target, Principal.Bot("acme", "bob"))
        assert(challenge.id.nonEmpty)

  test("POST /bot/challenge is 401 without a token"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("the challenged bot accepts and receives a game id"):
    app.flatMap: service =>
      for
        challenge <- challengeBobAsAlice(service)
        accepted  <- service.run(request(Method.POST, uri"/bot/challenge" / challenge.id / "accept", Some("tok-bob")))
        _ = assertEquals(accepted.status, Status.Created)
        game <- accepted.as[BotGame]
      yield assert(game.gameId.nonEmpty)

  test("a non-target accepting is forbidden"):
    app.flatMap: service =>
      for
        challenge <- challengeBobAsAlice(service)
        // Alice is the challenger, not the challenged bot — she cannot accept.
        resp <- service.run(request(Method.POST, uri"/bot/challenge" / challenge.id / "accept", Some("tok-alice")))
      yield assertEquals(resp.status, Status.Forbidden)

  test("accepting an unknown challenge is 404"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge" / "nope" / "accept", Some("tok-bob"))))
      .map(r => assertEquals(r.status, Status.NotFound))

  test("accepting without a token is 401"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge" / "x" / "accept", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))
