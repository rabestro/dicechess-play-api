package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{BotEvent, Challenge, Principal}
import dicechess.play.wire.Codecs.given
import fs2.Stream
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.{Authorization, `Retry-After`}
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}

import scala.concurrent.duration.*

class BotRoutesSuite extends munit.CatsEffectSuite:

  private def appWith(limiter: AnonMintLimiter): IO[HttpApp[IO]] =
    for
      auth       <- BotAuth.fromSpec("acme|alice|tok-alice,acme|bob|tok-bob,acme|carol|tok-carol")
      events     <- BotEvents.create
      registry   <- GameRegistry.create()
      challenges <- Challenges.create(events, registry)
    yield BotRoutes(auth, challenges, events, registry, limiter).orNotFound

  private def app: IO[HttpApp[IO]] = AnonMintLimiter.create(limit = 100).flatMap(appWith)

  private def request(method: Method, uri: Uri, token: Option[String]): Request[IO] =
    val base = Request[IO](method, uri)
    token.fold(base)(t => base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t))))

  test("ndjson interleaves keep-alive newlines into an idle stream"):
    // An idle event stream (a bot waiting for challenges) must still produce periodic bytes so neither
    // the ember server's read-idle nor the client's timeout drops the long-lived stream.
    BotRoutes
      .ndjson[BotEvent](Stream.never[IO], keepAlive = 50.millis)
      .take(2)
      .compile
      .toList
      .timeoutTo(5.seconds, IO.raiseError(RuntimeException("no keep-alive within the deadline")))
      .map(bytes => assertEquals(new String(bytes.toArray, "UTF-8"), "\n\n"))

  test("POST /bot/anon mints a token that then authenticates"):
    app.flatMap: service =>
      for
        created <- service.run(Request[IO](Method.POST, uri"/bot/anon?name=Tester")).flatMap(_.as[AnonBot])
        account <- service.run(request(Method.GET, uri"/bot/account", Some(created.token))).flatMap(_.as[BotAccount])
      yield
        assertEquals(created.team, "anon")
        assert(created.id.startsWith("bot:team:anon:tester-"), created.id)
        assertEquals(account.id, created.id) // the minted token authenticates as the same identity

  test("POST /bot/anon is rate-limited per client (429 + Retry-After)"):
    AnonMintLimiter
      .create(limit = 2)
      .flatMap(appWith)
      .flatMap: service =>
        val mint = Request[IO](Method.POST, uri"/bot/anon")
        for
          s1 <- service.run(mint).map(_.status)
          s2 <- service.run(mint).map(_.status)
          r3 <- service.run(mint)
        yield
          assertEquals(s1, Status.Created)
          assertEquals(s2, Status.Created)
          assertEquals(r3.status, Status.TooManyRequests)
          assert(r3.headers.get[`Retry-After`].isDefined, "a 429 must carry Retry-After")

  test("an unknown / no Bearer token is unauthorized"):
    app.flatMap: service =>
      for
        noAuth   <- service.run(Request[IO](Method.GET, uri"/bot/account")).map(_.status)
        badToken <- service.run(request(Method.GET, uri"/bot/account", Some("nope"))).map(_.status)
      yield
        assertEquals(noAuth, Status.Unauthorized)
        assertEquals(badToken, Status.Unauthorized)

  private def challengeBobAsAlice(service: HttpApp[IO]): IO[Challenge] =
    service
      .run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "bob")))
      .flatMap(_.as[Challenge])

  /** Alice challenges Bob and Bob accepts; yields the seated game's id. */
  private def seatedGame(service: HttpApp[IO]): IO[String] =
    for
      challenge <- challengeBobAsAlice(service)
      accepted  <- service.run(request(Method.POST, uri"/bot/challenge" / challenge.id / "accept", Some("tok-bob")))
      game      <- accepted.as[BotGame]
    yield game.gameId

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

  test("a seated bot can open its game event stream"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(request(Method.GET, uri"/bot/game/stream" / gameId, Some("tok-alice")))
          .map(r => assertEquals(r.status, Status.Ok))

  test("a bot not seated in the game cannot stream it"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(request(Method.GET, uri"/bot/game/stream" / gameId, Some("tok-carol")))
          .map(r => assertEquals(r.status, Status.NotFound))

  test("streaming an unknown game is 404"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/game/stream" / "nope", Some("tok-alice"))))
      .map(r => assertEquals(r.status, Status.NotFound))

  test("a seated bot can submit a move (accepted; outcome arrives on the stream)"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some("tok-alice")).withEntity(BotMove(List("e2e4")))
          )
          .map(r => assertEquals(r.status, Status.Accepted))

  test("a seated bot can resign"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(request(Method.POST, uri"/bot/game" / gameId / "resign", Some("tok-bob")))
          .map(r => assertEquals(r.status, Status.Accepted))

  test("move on an unknown game is 404"):
    app
      .flatMap(
        _.run(
          request(Method.POST, uri"/bot/game" / "nope" / "move", Some("tok-alice")).withEntity(BotMove(List("e2e4")))
        )
      )
      .map(r => assertEquals(r.status, Status.NotFound))

  test("resign without a token is 401"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/game" / "x" / "resign", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))
