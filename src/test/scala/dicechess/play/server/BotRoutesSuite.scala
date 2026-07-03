package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import dicechess.play.core.{BotEvent, GameId, MoveTree, Principal, Seat}
import dicechess.play.wire.Codecs.given
import fs2.Stream
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.{Authorization, `Retry-After`}
import org.http4s.implicits.*
import org.http4s.{AuthScheme, Credentials, HttpApp, Method, Request, Status, Uri}

import scala.concurrent.duration.*

class BotRoutesSuite extends munit.CatsEffectSuite:

  private def appWith(
      limiter: AnonMintLimiter,
      maxPendingPerBot: Int = Challenges.DefaultMaxPendingPerBot
  ): IO[(HttpApp[IO], GameRegistry)] =
    for
      auth       <- BotAuth.fromSpec("acme|alice|tok-alice,acme|bob|tok-bob,acme|carol|tok-carol")
      events     <- BotEvents.create
      registry   <- GameRegistry.create()
      challenges <- Challenges.create(events, registry, maxPendingPerBot = maxPendingPerBot)
    yield (BotRoutes(auth, challenges, events, registry, limiter).orNotFound, registry)

  private def app: IO[HttpApp[IO]] = AnonMintLimiter.create(limit = 100).flatMap(appWith(_)).map(_._1)

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
      .flatMap(appWith(_))
      .map(_._1)
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

  private def challengeBobAsAlice(service: HttpApp[IO]): IO[ChallengeCreated] =
    service
      .run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "bob")))
      .flatMap(_.as[ChallengeCreated])

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
        // Nobody holds an account stream in this app — advisory only, the entry is still pending and pollable.
        assertEquals(challenge.targetOnline, false)

  test("POST /bot/challenge is 401 without a token"):
    app
      .flatMap(_.run(request(Method.POST, uri"/bot/challenge", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("a bot challenging itself is a 400"):
    app
      .flatMap(
        _.run(request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "alice")))
      )
      .map(r => assertEquals(r.status, Status.BadRequest))

  test("the pending-challenge cap answers 429"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_, maxPendingPerBot = 1))
      .map(_._1)
      .flatMap: service =>
        for
          first <- service.run(
            request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "bob"))
          )
          overflow <- service.run(
            request(Method.POST, uri"/bot/challenge", Some("tok-alice")).withEntity(ChallengeTarget("acme", "carol"))
          )
        yield
          assertEquals(first.status, Status.Created)
          assertEquals(overflow.status, Status.TooManyRequests)

  test("GET /bot/challenges lists pending challenges as in/out for the caller"):
    app.flatMap: service =>
      for
        created <- challengeBobAsAlice(service)
        alice <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-alice"))).flatMap(_.as[BotChallenges])
        bob   <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-bob"))).flatMap(_.as[BotChallenges])
        carol <- service.run(request(Method.GET, uri"/bot/challenges", Some("tok-carol"))).flatMap(_.as[BotChallenges])
      yield
        assertEquals(alice.out.map(_.id), List(created.id)) // the challenger watches its outgoing challenge
        assertEquals(alice.in, Nil)
        assertEquals(bob.in.map(_.id), List(created.id)) // the target discovers it by polling — no stream needed
        assertEquals(bob.out, Nil)
        assertEquals(carol, BotChallenges(Nil, Nil)) // uninvolved bots see nothing

  test("GET /bot/challenges is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/challenges", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("GET /bot/games lists only the games the caller is seated in"):
    app.flatMap: service =>
      for
        gameId <- seatedGame(service)
        alice  <- service.run(request(Method.GET, uri"/bot/games", Some("tok-alice"))).flatMap(_.as[BotGames])
        bob    <- service.run(request(Method.GET, uri"/bot/games", Some("tok-bob"))).flatMap(_.as[BotGames])
        carol  <- service.run(request(Method.GET, uri"/bot/games", Some("tok-carol"))).flatMap(_.as[BotGames])
      yield
        // Both players recover the game (and their seat) with no stream held — the post-restart resume path.
        assertEquals(alice.games.map(g => (g.gameId, g.seat)), List((gameId, Seat.White)))
        assertEquals(bob.games.map(g => (g.gameId, g.seat)), List((gameId, Seat.Black)))
        assertEquals(carol.games, Nil)

  test("GET /bot/games is 401 without a token"):
    app
      .flatMap(_.run(request(Method.GET, uri"/bot/games", None)))
      .map(r => assertEquals(r.status, Status.Unauthorized))

  test("a finished game leaves the listing (the player index is evicted with the room)"):
    app.flatMap: service =>
      def pollEmpty: IO[Unit] =
        service
          .run(request(Method.GET, uri"/bot/games", Some("tok-alice")))
          .flatMap(_.as[BotGames])
          .flatMap(listed => if listed.games.isEmpty then IO.unit else IO.sleep(50.millis) *> pollEmpty)
      for
        gameId <- seatedGame(service)
        before <- service.run(request(Method.GET, uri"/bot/games", Some("tok-alice"))).flatMap(_.as[BotGames])
        _ = assertEquals(before.games.map(_.gameId), List(gameId))
        _ <- service.run(request(Method.POST, uri"/bot/game" / gameId / "resign", Some("tok-alice")))
        // Eviction runs on the room-result fiber, so it lands shortly after the resign — poll until it does.
        _ <- pollEmpty.timeoutTo(5.seconds, IO.raiseError(RuntimeException("the finished game was never evicted")))
      yield ()

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

  test("a move before any roll is refused synchronously (409 not your turn)"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        // No seeds submitted, so the opening roll is still gated: no turn is pending for anyone.
        service
          .run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some("tok-alice")).withEntity(BotMove(List("e2e4")))
          )
          .flatMap: resp =>
            assertEquals(resp.status, Status.Conflict)
            resp.as[MoveOutcome].map(o => assertEquals(o, MoveOutcome(applied = false, reason = Some("not your turn"))))

  test("a move submit answers the verdict synchronously: 409 with the reason, then 200 with the version"):
    AnonMintLimiter
      .create(limit = 100)
      .flatMap(appWith(_))
      .flatMap: (service, registry) =>
        /** Any root-to-leaf walk of the tree — a complete legal turn by construction. */
        def leafPath(tree: MoveTree): List[String] =
          tree.children.headOption match
            case None              => Nil
            case Some((uci, next)) => uci :: leafPath(next)

        // Poll the room until a roll with a real decision is pending (auto-passes advance on their own).
        def movable(gameId: String): IO[(Seat, MoveTree)] =
          registry
            .get(GameId(gameId))
            .flatMap:
              case None       => IO.raiseError(RuntimeException("game vanished"))
              case Some(room) =>
                (room.snapshot, room.legalMoves).flatMapN: (snap, moves) =>
                  if moves.dicePending && moves.legalMoves.children.nonEmpty then
                    IO.pure((snap.activeSeat, moves.legalMoves))
                  else IO.sleep(50.millis) *> movable(gameId)

        def seed(gameId: String, token: String, seed: String): IO[Status] =
          service
            .run(request(Method.POST, uri"/bot/game" / gameId / "seed", Some(token)).withEntity(BotSeed(seed)))
            .map(_.status)

        for
          gameId <- seatedGame(service)
          // Both seats seed via the API — this opens the gate and rolls the first turn immediately.
          _                  <- seed(gameId, "tok-alice", "alice-client-seed-0001")
          _                  <- seed(gameId, "tok-bob", "bob-client-seed-00001")
          (activeSeat, tree) <- movable(gameId).timeoutTo(
            10.seconds,
            IO.raiseError(RuntimeException("no movable roll"))
          )
          // Alice challenged, so she is White; the mover's token follows the active seat.
          mover     = if activeSeat == Seat.White then "tok-alice" else "tok-bob"
          offTurner = if activeSeat == Seat.White then "tok-bob" else "tok-alice"
          offTurn <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(offTurner)).withEntity(BotMove(leafPath(tree)))
          )
          illegal <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover)).withEntity(BotMove(List("a1a1")))
          )
          applied <- service.run(
            request(Method.POST, uri"/bot/game" / gameId / "move", Some(mover)).withEntity(BotMove(leafPath(tree)))
          )
          offTurnBody <- offTurn.as[MoveOutcome]
          illegalBody <- illegal.as[MoveOutcome]
          appliedBody <- applied.as[MoveOutcome]
        yield
          assertEquals(offTurn.status, Status.Conflict)
          assertEquals(offTurnBody.reason, Some("not your turn"))
          assertEquals(illegal.status, Status.Conflict)
          assertEquals(illegalBody, MoveOutcome(applied = false, reason = Some("illegal turn")))
          assertEquals(applied.status, Status.Ok)
          assertEquals(appliedBody.applied, true)
          assert(
            appliedBody.version.exists(_ > 0L),
            s"the applied verdict must carry the TurnPlayed version: $appliedBody"
          )

  test("a seated bot can submit a dice seed (accepted; folded in before the opening roll)"):
    app.flatMap: service =>
      seatedGame(service).flatMap: gameId =>
        service
          .run(
            request(Method.POST, uri"/bot/game" / gameId / "seed", Some("tok-alice"))
              .withEntity(BotSeed("alice-client-seed-0001"))
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
