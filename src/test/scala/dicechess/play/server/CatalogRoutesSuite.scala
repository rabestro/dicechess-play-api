package dicechess.play.server

import cats.effect.IO
import dicechess.play.store.{BotCatalogListing, BotCatalogStore}
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

/** The public bot-catalog wire (ADR-0014, E2) over a stub store — the SQL behind the seam is covered against real
  * Postgres in `PgGameStoreSuite`; here the subject is the HTTP layer: the response shape (pinned as JSON), the
  * provisional flag derived from RD, and that a bot open to humans stays visible even while its rating is provisional.
  */
class CatalogRoutesSuite extends munit.CatsEffectSuite:

  private def stubCatalog(listings: List[BotCatalogListing]): BotCatalogStore = new BotCatalogStore:
    def catalogBots: IO[List[BotCatalogListing]] = IO.pure(listings)

  private def app(listings: List[BotCatalogListing]): HttpApp[IO] =
    CatalogRoutes(stubCatalog(listings)).orNotFound

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
    app(listings).run(Request[IO](Method.GET, uri"/lobby/bots")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(assertEquals(_, expected, "the catalog shape is a contract — pin it"))
    }

  test("GET /lobby/bots is an empty list when no bot is open to humans"):
    app(Nil).run(Request[IO](Method.GET, uri"/lobby/bots")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(body => assertEquals(body.hcursor.downField("bots").values.map(_.size), Some(0)))
    }
