package dicechess.play.server

import cats.effect.{IO, Ref}
import com.comcast.ip4s.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.game.BotConnection
import dicechess.play.store.{BotWebhook, GameStore, WebhookStore}
import io.circe.Json
import io.circe.parser.decode
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.{HttpApp, HttpRoutes, Response, Status, Uri}
import org.typelevel.ci.CIString

import java.time.Instant
import scala.concurrent.duration.*

/** The webhook service end-to-end (#104): the ownership handshake, and full games where the "bot" is an HTTP endpoint
  * that verifies every delivery's HMAC and answers with moves walked from the envelope's own `legalMoves`.
  *
  * The endpoint is an `HttpApp` served to the dispatcher through `Client.fromHttpApp` — real http4s request/response
  * semantics (headers, bodies, status codes) with zero sockets, so the games are deterministic and port-collision free.
  * One handshake test runs over a REAL ember server + ember client to smoke the actual network stack; the production
  * URL policy itself is covered by `WebhookSecuritySuite`, so these tests inject a parse-only `checkUrl`.
  */
class WebhooksSuite extends munit.CatsEffectSuite:

  override def munitIOTimeout: Duration = 3.minutes

  /** Parse-only URL check: the tests' endpoints live on fake hosts (`Client.fromHttpApp` never resolves anything). */
  private val allowAll: String => IO[Either[String, Uri]] =
    url => IO.pure(Uri.fromString(url).left.map(_ => "not a valid URL"))

  private val config = Webhooks.Config(timeout = 5.seconds, scanEvery = 50.millis)

  private def service(
      registry: GameRegistry,
      store: WebhookStore,
      client: Client[IO],
      checkUrl: String => IO[Either[String, Uri]] = allowAll
  ): cats.effect.Resource[IO, Webhooks] = Webhooks.create(registry, store, client, config, checkUrl)

  private val seed = "0123456789abcdef" // the 16-char minimum a seat must contribute

  /** Root-to-leaf walk of the legal-move tree: any such path is a complete legal turn (max-micro-moves rule). */
  private def firstPath(tree: MoveTree): List[String] =
    tree.children.toList.minByOption(_._1) match
      case None                => Nil
      case Some((move, child)) => move :: firstPath(child)

  /** The scripted bot endpoint: echoes verification nonces, and answers `yourTurn` envelopes with the first legal path
    * — after verifying the delivery's signature against the registered secrets (a bad MAC is a 401 and counted). When
    * the envelope's inline tree is elided (over the cap), it falls back to the room's full tree — the in-process
    * stand-in for the documented `GET /games/{id}/moves` fetch.
    */
  private def botEndpoint(
      secrets: Ref[IO, List[String]],
      registry: GameRegistry,
      delivered: Ref[IO, Int],
      badSignatures: Ref[IO, Int]
  ): HttpApp[IO] =
    HttpApp[IO] { req =>
      req.bodyText.compile.string.flatMap { body =>
        decode[WebhookVerification](body) match
          case Right(v) if v.`type` == "verification" =>
            // The registrant cannot know the secret yet (it is disclosed only after this handshake succeeds),
            // so the echo is unauthenticated by design.
            Ok(Json.obj("nonce" -> v.nonce.asJson))
          case _ =>
            val ts  = req.headers.get(CIString(WebhookSecurity.TimestampHeader)).map(_.head.value).getOrElse("")
            val sig = req.headers.get(CIString(WebhookSecurity.SignatureHeader)).map(_.head.value).getOrElse("")
            secrets.get.flatMap { keys =>
              val signedOk = ts.toLongOption.exists(t => keys.exists(k => WebhookSecurity.sign(k, t, body) == sig))
              if !signedOk then badSignatures.update(_ + 1).as(Response[IO](Status.Unauthorized))
              else
                decode[WebhookEnvelope](body) match
                  case Left(_)         => IO.pure(Response[IO](Status.BadRequest))
                  case Right(envelope) =>
                    val moves = envelope.state.legalMoves.filter(_.children.nonEmpty) match
                      case Some(tree) => IO.pure(firstPath(tree))
                      case None       =>
                        registry
                          .get(GameId(envelope.gameId))
                          .flatMap(_.fold(IO.pure(MoveTree.empty))(_.legalMoves.map(_.legalMoves)))
                          .map(firstPath)
                    delivered.update(_ + 1) *> moves.flatMap(m => Ok(BotMove(m).asJson))
            }
      }
    }

  // ── ownership handshake ──────────────────────────────────────────────────────

  test("register stores the webhook only after the endpoint echoes the nonce"):
    for
      registry  <- GameRegistry.create(store = GameStore.noop)
      store     <- WebhookStore.inMemory
      secrets   <- Ref.of[IO, List[String]](Nil)
      delivered <- Ref.of[IO, Int](0)
      badSig    <- Ref.of[IO, Int](0)
      bot: Principal.Bot = Principal.Bot("hooks", "alpha")
      result <- service(registry, store, Client.fromHttpApp(botEndpoint(secrets, registry, delivered, badSig)))
        .use(_.register(bot, "https://bot.example/hook"))
      stored <- store.get("hooks", "alpha")
    yield
      val hook = result.getOrElse(fail(s"registration must succeed, got $result"))
      assertEquals(hook.url, "https://bot.example/hook")
      assert(hook.secret.matches("[0-9a-f]{64}"), "the signing secret is 32 random bytes, hex")
      assertEquals(stored.map(_.secret), Some(hook.secret))

  test("an endpoint that echoes the wrong nonce (or none) is rejected and nothing is stored"):
    val wrongNonce = HttpApp[IO](_ => Ok(Json.obj("nonce" -> "not-what-was-sent".asJson)))
    val noJson     = HttpApp[IO](_ => Ok("pong"))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      bot: Principal.Bot = Principal.Bot("hooks", "alpha")
      first  <- service(registry, store, Client.fromHttpApp(wrongNonce)).use(_.register(bot, "https://x.example"))
      second <- service(registry, store, Client.fromHttpApp(noJson)).use(_.register(bot, "https://x.example"))
      stored <- store.get("hooks", "alpha")
    yield
      assert(first.left.exists(_.contains("nonce")), s"wrong echo must fail with the reason, got $first")
      assert(second.isLeft, s"a non-JSON echo must fail, got $second")
      assertEquals(stored, None)

  test("a dead endpoint fails registration with a reason, not an exception"):
    val dead = Client[IO](_ => cats.effect.Resource.eval(IO.raiseError(new java.net.ConnectException("refused"))))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      result   <- service(registry, store, dead).use(
        _.register(Principal.Bot("hooks", "alpha"), "https://gone.example/hook")
      )
      stored <- store.get("hooks", "alpha")
    yield
      assert(result.isLeft)
      assertEquals(stored, None)

  test("registration enforces the real URL policy when constructed with it — a private target never gets a POST"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      calls    <- Ref.of[IO, Int](0)
      counting = Client.fromHttpApp(HttpApp[IO](_ => calls.update(_ + 1) *> Ok("")))
      // production checkUrl by default
      result <- Webhooks
        .create(registry, store, counting, config)
        .use(_.register(Principal.Bot("hooks", "alpha"), "https://192.168.10.3/hook"))
      posted <- calls.get
    yield
      assert(result.left.exists(_.contains("non-public")), s"expected the SSRF reason, got $result")
      assertEquals(posted, 0, "the guard must reject BEFORE any request is made")

  test("the handshake round-trips over a real socket server and client (network-stack smoke)"):
    val endpoint = HttpRoutes
      .of[IO] { case req @ POST -> Root / "hook" =>
        req.bodyText.compile.string.flatMap { body =>
          decode[WebhookVerification](body) match
            case Right(v) => Ok(Json.obj("nonce" -> v.nonce.asJson))
            case Left(_)  => BadRequest()
        }
      }
      .orNotFound
    val resources = for
      server <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withShutdownTimeout(1.second)
        .withHttpApp(endpoint)
        .build
      client <- EmberClientBuilder.default[IO].build
    yield (server, client)
    resources.use { (server, client) =>
      for
        registry <- GameRegistry.create(store = GameStore.noop)
        store    <- WebhookStore.inMemory
        url = s"http://127.0.0.1:${server.address.getPort}/hook"
        result <- service(registry, store, client).use(_.register(Principal.Bot("hooks", "alpha"), url))
      yield assert(result.isRight, s"real-socket handshake must succeed, got $result")
    }

  // ── delivery: full games ─────────────────────────────────────────────────────

  test("two webhook-driven seats play a full game to a natural end, every delivery HMAC-verified"):
    for
      registry  <- GameRegistry.create(store = GameStore.noop)
      store     <- WebhookStore.inMemory
      secrets   <- Ref.of[IO, List[String]](Nil)
      delivered <- Ref.of[IO, Int](0)
      badSig    <- Ref.of[IO, Int](0)
      alpha: Principal.Bot = Principal.Bot("hooks", "alpha")
      beta: Principal.Bot  = Principal.Bot("hooks", "beta")
      // The service stays open for the whole game: its runners are supervised by the Resource now.
      over <- service(registry, store, Client.fromHttpApp(botEndpoint(secrets, registry, delivered, badSig)))
        .use: webhooks =>
          for
            hookA <- webhooks.register(alpha, "https://bots.example/hook").map(_.toOption.get)
            hookB <- webhooks.register(beta, "https://bots.example/hook").map(_.toOption.get)
            _     <- secrets.set(List(hookA.secret, hookB.secret))
            made  <- registry.create(alpha, beta, TimeControl.Unlimited)
            (_, room) = made.toOption.get
            _    <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
            _    <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
            _    <- webhooks.attachSweep // both seats get runners; the game then drives itself
            over <- room.result
              .timeoutTo(150.seconds, IO.raiseError(new RuntimeException("webhook game never ended")))
          yield over
      turns    <- delivered.get
      rejected <- badSig.get
    yield
      assert(turns >= 2, s"both seats must have been served turns over the webhook, got $turns")
      assertEquals(rejected, 0, "every delivery must carry a valid signature")
      assert(
        over.termination == Termination.KingCaptured || over.termination == Termination.Draw,
        s"a webhook-vs-webhook game must end on the board, got $over"
      )

  test("a dead endpoint forfeits on the clock without hanging the room"):
    val dead = Client[IO](_ => cats.effect.Resource.eval(IO.raiseError(new java.net.ConnectException("refused"))))
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      silent = Principal.Bot("hooks", "silent")
      // Registered directly at the store seam: `register` would (rightly) refuse a dead endpoint's handshake,
      // and this test is about a webhook that DIED AFTER registration.
      _ <- store.put(BotWebhook("hooks", "silent", "https://gone.example/hook", "s" * 64, Instant.EPOCH))
      opponent = Principal.Bot("acme", "greedy")
      made <- registry.create(silent, opponent, TimeControl.SuddenDeath(2))
      (_, room) = made.toOption.get
      _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
      driver = BotConnection(opponent, Seat.Black, BotRegistry.getAlgorithm("greedy").get)
      over <- service(registry, store, dead).use: webhooks =>
        driver
          .run(room)
          .background
          .use: _ =>
            webhooks.attachSweep *>
              room.result.timeoutTo(
                20.seconds,
                IO.raiseError(new RuntimeException("the room hung instead of flagging the dead webhook"))
              )
    yield
      assertEquals(over.termination, Termination.Timeout)
      assertEquals(over.result, GameResult.Win(Side.Black), "the webhook seat (White) must lose on time")

  test("garbage and non-200 responses submit nothing — the game stays untouched for the clock to decide"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      // The endpoint signals when it has answered, so the assertion waits for the delivery to have actually
      // happened instead of sleeping and racing it (review).
      answered <- cats.effect.Deferred[IO, Unit]
      garbage = Client.fromHttpApp(HttpApp[IO](_ => answered.complete(()).attempt *> Ok("this is not a move")))
      noisy   = Principal.Bot("hooks", "noisy")
      _    <- store.put(BotWebhook("hooks", "noisy", "https://noise.example/hook", "s" * 64, Instant.EPOCH))
      made <- registry.create(noisy, Principal.Bot("acme", "idle"), TimeControl.Unlimited)
      (_, room) = made.toOption.get
      state <- service(registry, store, garbage).use: webhooks =>
        for
          _ <- room.submit(Seat.White, GameCommand.SubmitSeed(seed))
          _ <- room.submit(Seat.Black, GameCommand.SubmitSeed(seed))
          _ <- webhooks.attachSweep
          // No local timeout here (#140): under the full `check` this runs with scoverage instrumentation AND
          // alongside the testcontainers suites, so the delivery fiber can be starved well past any tight wall-clock
          // bound without any logic being wrong — a 30s local race flaked under exactly that contention. There is
          // nothing here to virtualize (no designed sleep; the delay is real fiber-scheduling latency), so the fix
          // is to not race it at all: await the actual completion signal and let the class-level
          // `munitIOTimeout` (3 minutes — generous enough to survive contention) be the only ceiling, so a genuine
          // hang still fails loudly instead of hanging CI forever.
          _     <- answered.get
          state <- room.snapshot
          _     <- room.submit(Seat.White, GameCommand.Resign) // clean up: end the room's fibers
        yield state
    yield
      assertEquals(state.status, GameStatus.Active)
      assert(state.dicePending, "an unparseable answer must leave the pending roll unanswered")
