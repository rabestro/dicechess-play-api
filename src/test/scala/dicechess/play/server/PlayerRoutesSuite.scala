package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{GameId, Principal}
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status, Uri}

import java.time.Instant

/** The "my games" wire (#151) over a stub store — the SQL behind `recentResultsFor` is covered against real Postgres in
  * `PgGameStoreSuite`; here the subject is the HTTP layer: identity validation, response shape (pinned as JSON), POV
  * result mapping, opponent anonymisation, `limit` bounds, and the no-enumeration-signal privacy property.
  */
class PlayerRoutesSuite extends munit.CatsEffectSuite:

  private val bob = Principal.Bot("acme", "bob")

  private def stubResults(recent: Map[String, List[GameResultRow]]): GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
      IO.pure(recent.getOrElse(externalId, Nil).take(limit))
    def finishedRatedSince(since: Instant): IO[List[GameResultRow]] = IO.pure(Nil)
    def pairFor(pairingId: String): IO[List[GameResultRow]]         = IO.pure(Nil)

  private def app(recent: Map[String, List[GameResultRow]] = Map.empty): HttpApp[IO] =
    PlayerRoutes(stubResults(recent)).orNotFound

  private val at = Instant.parse("2026-07-16T12:00:00Z")

  private def row(
      id: String,
      white: String,
      black: String,
      result: Option[Int],
      rated: Boolean = false,
      termination: String = "resign",
      timeControl: String = "Fischer(300,3)"
  ): GameResultRow =
    GameResultRow(GameId(id), white, black, result, termination, rated, timeControl, "ab12", None, at)

  test("GET /players/{guestId}/games returns the requester's recent games with POV results and anonymised opponents"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000001"
    val guestExt = s"guest:$guestId"
    val games    = List(
      row("g-1", guestExt, bob.externalId, result = Some(1)),      // the guest wins as White vs a bot
      row("g-2", bob.externalId, guestExt, result = Some(1)),      // bob wins as White: the guest loses as Black
      row("g-3", "guest:other-uuid", guestExt, result = Some(-1)), // the guest wins as Black vs another HUMAN
      row("g-4", guestExt, bob.externalId, result = Some(0), termination = "draw_agreement")
    )
    val service  = app(recent = Map(guestExt -> games))
    val expected = parse(
      s"""{"games":[
             {"gameId":"g-1","seat":"White","opponent":{"kind":"Bot","name":"acme bob"},"result":"win",
              "rated":false,"termination":"resign","timeControl":"Fischer(300,3)","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-2","seat":"Black","opponent":{"kind":"Bot","name":"acme bob"},"result":"loss",
              "rated":false,"termination":"resign","timeControl":"Fischer(300,3)","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-3","seat":"Black","opponent":{"kind":"Human","name":null},"result":"win",
              "rated":false,"termination":"resign","timeControl":"Fischer(300,3)","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-4","seat":"White","opponent":{"kind":"Bot","name":"acme bob"},"result":"draw",
              "rated":false,"termination":"draw_agreement","timeControl":"Fischer(300,3)",
              "finishedAt":"2026-07-16T12:00:00Z"}
           ]}"""
    ).toOption.get
    service.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/players/$guestId/games"))).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map { body =>
        assertEquals(body, expected, "no raw external id may appear for the human opponent")
      }
    }

  test("GET /players/{guestId}/games is 400 for a malformed guest id"):
    app()
      .run(Request[IO](Method.GET, uri"/players/not-a-uuid/games"))
      .map(resp => assertEquals(resp.status, Status.BadRequest))

  test("GET /players/{guestId}/games is 200 with an empty list for a well-formed but unknown guest id"):
    // Deliberate: an unknown-but-valid uuid must be indistinguishable from a known one with zero games — this
    // endpoint leaks no signal about which guest ids have ever played.
    val guestId = "0197f0a0-0000-7000-8000-000000000002"
    app()
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/players/$guestId/games")))
      .flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map(body => assertEquals(body, parse("""{"games":[]}""").toOption.get))
      }

  test("GET /players/{guestId}/games clamps an over-large `limit` instead of trusting the caller"):
    val guestId  = "0197f0a0-0000-7000-8000-000000000003"
    val guestExt = s"guest:$guestId"
    val games    = (1 to 250).map(n => row(s"g-$n", guestExt, bob.externalId, result = Some(0))).toList
    app(recent = Map(guestExt -> games))
      .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/players/$guestId/games?limit=99999")))
      .flatMap { resp =>
        assertEquals(resp.status, Status.Ok)
        resp.as[Json].map { body =>
          assertEquals(body.hcursor.downField("games").values.map(_.size), Some(200), "clamped to the hard cap")
        }
      }
