package dicechess.play.server

import cats.effect.IO
import dicechess.play.core.{GameId, Principal}
import dicechess.play.store.*
import io.circe.Json
import io.circe.parser.parse
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.implicits.*
import org.http4s.{HttpApp, Method, Request, Status}

import java.time.Instant

/** The public leaderboard/profile wire (D.2, #103) over stub stores — the SQL behind the seams is covered against real
  * Postgres in `PgGameStoreSuite`; here the subject is the HTTP layer: response shapes (pinned as JSON), rank
  * assignment, provisional visibility policy (hidden on the board, flagged on the profile), POV result mapping, and the
  * anonymisation of human opponents.
  */
class LeaderboardRoutesSuite extends munit.CatsEffectSuite:

  private val alice = Principal.Bot("acme", "alice")
  private val bob   = Principal.Bot("acme", "bob")

  private def stubBots(known: Map[(String, String), BotRating]): BotStore = new BotStore:
    def register(team: String, name: String, tokenHash: String): IO[Boolean]        = IO.pure(false)
    def authenticate(tokenHash: String): IO[Option[Principal.Bot]]                  = IO.pure(None)
    def rotate(team: String, name: String, newTokenHash: String): IO[Boolean]       = IO.pure(false)
    def ratingOf(team: String, name: String): IO[Option[BotRating]]                 = IO.pure(known.get((team, name)))
    def setOnLadder(team: String, name: String, on: Boolean): IO[Option[BotRating]] = IO.pure(None)
    def onLadderBots: IO[List[Principal.Bot]]                                       = IO.pure(Nil)
    def openToHumans(team: String, name: String, description: Option[String]): IO[Option[BotCatalogState]] =
      IO.pure(None)
    def closeToHumans(team: String, name: String): IO[Option[BotCatalogState]] = IO.pure(None)
    def openToHumansBots: IO[List[Principal.Bot]]                              = IO.pure(Nil)

  private def stubBoard(entries: List[LeaderboardEntry], tallies: Map[String, ResultTally]): LeaderboardStore =
    new LeaderboardStore:
      def leaderboard(maxRd: Double): IO[List[LeaderboardEntry]] = IO.pure(entries.filter(_.rd <= maxRd))
      def resultTallyFor(externalId: String): IO[ResultTally]    =
        IO.pure(tallies.getOrElse(externalId, ResultTally.Empty))

  private def stubResults(recent: Map[String, List[GameResultRow]]): GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
      IO.pure(recent.getOrElse(externalId, Nil).take(limit))
    def finishedRatedSince(since: Instant): IO[List[GameResultRow]] = IO.pure(Nil)
    def pairFor(pairingId: String): IO[List[GameResultRow]]         = IO.pure(Nil)

  private def app(
      bots: Map[(String, String), BotRating] = Map.empty,
      entries: List[LeaderboardEntry] = Nil,
      tallies: Map[String, ResultTally] = Map.empty,
      recent: Map[String, List[GameResultRow]] = Map.empty
  ): HttpApp[IO] =
    LeaderboardRoutes(stubBots(bots), stubBoard(entries, tallies), stubResults(recent)).orNotFound

  private val at = Instant.parse("2026-07-16T12:00:00Z")

  private def row(
      id: String,
      white: String,
      black: String,
      result: Option[Int],
      rated: Boolean = true,
      termination: String = "resign"
  ): GameResultRow =
    GameResultRow(GameId(id), white, black, result, termination, rated, "Fischer(300,3)", "ab12", None, at)

  test("GET /leaderboard ranks converged bots and pins the wire shape"):
    val entries = List(
      LeaderboardEntry("acme", "alice", 1720.5, 85.2, onLadder = true, ResultTally(30, 2, 10)),
      LeaderboardEntry("acme", "bob", 1480.0, 100.0, onLadder = false, ResultTally(10, 2, 30)),
      LeaderboardEntry("acme", "fresh", 1500.0, 350.0, onLadder = true, ResultTally.Empty) // provisional: filtered
    )
    val service  = app(entries = entries)
    val expected = parse(
      """{"leaders":[
           {"rank":1,"team":"acme","name":"alice","rating":1720.5,"rd":85.2,"onLadder":true,
            "games":42,"wins":30,"draws":2,"losses":10},
           {"rank":2,"team":"acme","name":"bob","rating":1480.0,"rd":100.0,"onLadder":false,
            "games":42,"wins":10,"draws":2,"losses":30}
         ]}"""
    ).toOption.get
    service.run(Request[IO](Method.GET, uri"/leaderboard")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map(assertEquals(_, expected, "the public board shape is a contract — pin it"))
    }

  test("GET /bots/{team}/{name} returns the profile with POV results and an anonymised human opponent"):
    val aliceId = alice.externalId
    val games   = List(
      row("g-1", aliceId, bob.externalId, result = Some(1)), // alice wins as White vs a bot
      row("g-2", bob.externalId, aliceId, result = Some(1)), // bob wins as White: alice loses as Black
      row("g-3", "guest:secret-uuid", aliceId, result = Some(-1), rated = false), // alice wins as Black vs a HUMAN
      row("g-4", aliceId, bob.externalId, result = Some(0), termination = "draw_agreement")
    )
    val service = app(
      bots = Map(("acme", "alice") -> BotRating(1650.0, 95.0, 0.058, onLadder = true, None)),
      tallies = Map(aliceId -> ResultTally(20, 3, 7)),
      recent = Map(aliceId -> games)
    )
    val expected = parse(
      s"""{"team":"acme","name":"alice","rating":1650.0,"rd":95.0,"provisional":false,"onLadder":true,
           "games":30,"wins":20,"draws":3,"losses":7,
           "recent":[
             {"gameId":"g-1","seat":"White","opponent":{"kind":"Bot","name":"acme bob"},"result":"win",
              "rated":true,"termination":"resign","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-2","seat":"Black","opponent":{"kind":"Bot","name":"acme bob"},"result":"loss",
              "rated":true,"termination":"resign","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-3","seat":"Black","opponent":{"kind":"Human","name":null},"result":"win",
              "rated":false,"termination":"resign","finishedAt":"2026-07-16T12:00:00Z"},
             {"gameId":"g-4","seat":"White","opponent":{"kind":"Bot","name":"acme bob"},"result":"draw",
              "rated":true,"termination":"draw_agreement","finishedAt":"2026-07-16T12:00:00Z"}
           ]}"""
    ).toOption.get
    service.run(Request[IO](Method.GET, uri"/bots/acme/alice")).flatMap { resp =>
      assertEquals(resp.status, Status.Ok)
      resp.as[Json].map { body =>
        assertEquals(body, expected, "no raw external id may appear for the human opponent")
      }
    }

  test("a provisional bot is absent from the board but visible — flagged — on its own profile"):
    val service = app(
      bots = Map(("acme", "newbie") -> BotRating(1500.0, 350.0, 0.06, onLadder = true, None)),
      entries = List(LeaderboardEntry("acme", "newbie", 1500.0, 350.0, onLadder = true, ResultTally.Empty))
    )
    for
      board   <- service.run(Request[IO](Method.GET, uri"/leaderboard")).flatMap(_.as[Json])
      profile <- service.run(Request[IO](Method.GET, uri"/bots/acme/newbie")).flatMap(_.as[Json])
    yield
      assertEquals(board.hcursor.downField("leaders").values.map(_.size), Some(0), "provisional: off the board")
      assertEquals(profile.hcursor.get[Boolean]("provisional").toOption, Some(true), "but flagged on the profile")

  test("GET /bots/{team}/{name} is 404 for an unregistered identity"):
    app().run(Request[IO](Method.GET, uri"/bots/ghost/nobody")).map(resp => assertEquals(resp.status, Status.NotFound))
