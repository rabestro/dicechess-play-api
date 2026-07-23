package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.Principal
import dicechess.play.store.{
  BotCatalogListing,
  BotCatalogState,
  BotCatalogStore,
  BotRating,
  BotStore,
  GameStore,
  WebhookStore
}
import io.circe.Json
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.http4s.{HttpApp, HttpRoutes, Method, Request, Status, Uri}

import scala.concurrent.duration.*

/** The public bot-catalog wire (ADR-0014, E2/E3) over stub stores — the SQL behind the listing is covered against real
  * Postgres in `PgGameStoreSuite`, and the webhook delivery mechanics (signing, timeouts, size caps) in
  * `WebhooksSuite`; here the subject is the HTTP layer: the response shapes (pinned as JSON), the provisional flag
  * derived from RD, that a bot open to humans stays visible even while its rating is provisional, and the wake route's
  * gating (catalog membership, feature-disabled, rate limit) plus its liveness outcome against a scripted endpoint.
  */
class CatalogRoutesSuite extends munit.CatsEffectSuite:

  private val allowAll: String => IO[Either[String, Uri]] =
    url => IO.pure(Uri.fromString(url).left.map(_ => "not a valid URL"))

  private val webhookConfig = Webhooks.Config(timeout = 5.seconds)

  private def stubBots(open: Set[(String, String)]): BotStore = new BotStore:
    def register(team: String, name: String, tokenHash: String): IO[Boolean]              = IO.pure(false)
    def authenticate(tokenHash: String): IO[Option[Principal.Bot]]                        = IO.pure(None)
    def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]             = IO.pure(false)
    def ratingOf(team: String, name: String): IO[Option[BotRating]]                       = IO.pure(None)
    def setOnLadder(team: String, name: String, onLadder: Boolean): IO[Option[BotRating]] = IO.pure(None)
    def onLadderBots: IO[List[Principal.Bot]]                                             = IO.pure(Nil)
    def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
      IO.pure(None)
    def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] = IO.pure(None)
    def openToHumansBots: IO[List[Principal.Bot]] = IO.pure(open.toList.map(Principal.Bot(_, _)))

  private def stubCatalog(listings: List[BotCatalogListing]): BotCatalogStore = new BotCatalogStore:
    def catalogBots: IO[List[BotCatalogListing]] = IO.pure(listings)

  private def request(method: Method, uri: String): Request[IO] = Request[IO](method, Uri.unsafeFromString(uri))

  private def app(
      listings: List[BotCatalogListing] = Nil,
      open: Set[(String, String)] = Set.empty,
      webhooks: Option[Webhooks] = None,
      limiter: Option[AnonMintLimiter] = None
  ): IO[HttpRoutes[IO]] =
    limiter
      .fold(AnonMintLimiter.create())(IO.pure)
      .map(lim => CatalogRoutes(stubCatalog(listings), stubBots(open), webhooks, lim))

  /** A fake bot endpoint that only ever answers the ownership/wake handshake — echoes whatever nonce it is sent, real
    * `yourTurn` delivery is out of scope here (covered end-to-end in `WebhooksSuite`).
    */
  private val echoingEndpoint: HttpApp[IO] = HttpApp[IO] { req =>
    req.bodyText.compile.string.flatMap { body =>
      decode[WebhookVerification](body) match
        case Right(v) => Ok(Json.obj("nonce" -> v.nonce.asJson))
        case Left(_)  => BadRequest()
    }
  }

  private val deadEndpoint: HttpApp[IO] = HttpApp[IO](_ => InternalServerError())

  test("GET /lobby/bots returns catalog cards, derives provisional from RD, and pins the wire shape"):
    val listings = List(
      BotCatalogListing("acme", "alice", 1720.5, 85.0, Some("aggressive + book")),
      BotCatalogListing("acme", "fresh", 1500.0, 350.0, None) // RD above the threshold: provisional, but still listed
    )
    val expected = parse(
      """{"bots":[
           {"team":"acme","name":"alice","rating":1720.5,"rd":85.0,"provisional":false,"description":"aggressive + book"},
           {"team":"acme","name":"fresh","rating":1500.0,"rd":350.0,"provisional":true,"description":null}
         ]}"""
    ).toOption.get
    app(listings).flatMap(_.orNotFound.run(request(Method.GET, "/lobby/bots"))).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(assertEquals(_, expected, "the catalog shape is a contract — pin it"))
    }

  test("GET /lobby/bots is an empty list when no bot is open to humans"):
    app().flatMap(_.orNotFound.run(request(Method.GET, "/lobby/bots"))).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(body => assertEquals(body.hcursor.downField("bots").values.map(_.size), Some(0)))
    }

  test("POST /lobby/bots/{team}/{name}/wake is 404 for a name outside the catalog"):
    app(open = Set(("acme", "alice")))
      .flatMap(_.orNotFound.run(request(Method.POST, "/lobby/bots/acme/ghost/wake")))
      .map(resp => assertEquals(resp.status, Status.NotFound))

  test("POST /lobby/bots/{team}/{name}/wake is 503 when webhooks are disabled on the server"):
    app(open = Set(("acme", "alice")), webhooks = None)
      .flatMap(_.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake")))
      .map(resp => assertEquals(resp.status, Status.ServiceUnavailable))

  test("POST /lobby/bots/{team}/{name}/wake is alive:true when the endpoint echoes the nonce"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(echoingEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          for
            _      <- webhooks.register(Principal.Bot("acme", "alice"), "https://bot.example/hook")
            routes <- app(open = Set(("acme", "alice")), webhooks = Some(webhooks))
            resp   <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            body   <- resp.as[Wake]
          yield (resp.status, body)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Wake(alive = true))

  test("POST /lobby/bots/{team}/{name}/wake is alive:false for a catalog bot with no registered webhook"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(deadEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          // Deliberately not registered: a catalog-eligible bot whose webhook was never (or no longer) wired.
          for
            routes <- app(open = Set(("acme", "alice")), webhooks = Some(webhooks))
            resp   <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            body   <- resp.as[Wake]
          yield (resp.status, body)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Wake(alive = false))

  test("POST /lobby/bots/{team}/{name}/wake is 429 once the rate limit is spent"):
    for
      registry <- GameRegistry.create(store = GameStore.noop)
      store    <- WebhookStore.inMemory
      outcome  <- Webhooks.create(registry, store, Client.fromHttpApp(echoingEndpoint), webhookConfig, allowAll).use {
        webhooks =>
          for
            _       <- webhooks.register(Principal.Bot("acme", "alice"), "https://bot.example/hook")
            limiter <- AnonMintLimiter.create(limit = 1)
            routes  <- app(open = Set(("acme", "alice")), webhooks = Some(webhooks), limiter = Some(limiter))
            first   <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
            second  <- routes.orNotFound.run(request(Method.POST, "/lobby/bots/acme/alice/wake"))
          yield (first.status, second.status)
      }
    yield
      assertEquals(outcome._1, Status.Ok)
      assertEquals(outcome._2, Status.TooManyRequests)
