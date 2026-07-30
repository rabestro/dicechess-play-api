package dicechess.play.server

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.{GameSnapshot, PgGameStore, TurnRecord}
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.util.ExecutionContexts
import munit.CatsEffectSuite
import org.http4s.circe.CirceEntityCodec.given
import org.http4s.headers.`Cache-Control`
import org.http4s.implicits.*
import org.http4s.{CacheDirective, HttpApp, Method, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/** `GET /games/{id}/history` (#178) against a real Postgres: reveal gating (#115) for both the unpaired and CRN-paired
  * cases, anonymization, cache headers, and the two 404 paths.
  */
class HistoryRoutesSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def app(pg: PgGameStore): HttpApp[IO] = HistoryRoutes(pg, pg).orNotFound

  private def get(app: HttpApp[IO], id: String): IO[org.http4s.Response[IO]] =
    app.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/games/$id/history")))

  private def snapshotFixture(
      white: Principal,
      black: Principal,
      status: GameStatus,
      pairingId: Option[String] = None,
      partnerGameId: Option[String] = None
  ): GameSnapshot =
    GameSnapshot(
      version = 5L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> white, Seat.Black -> black),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map(Seat.White -> "white-seed", Seat.Black -> "black-seed"),
      started = true,
      ply = 2L,
      pending = false,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map.empty,
      lastRoll = List(2, 3, 6),
      turns = Vector(TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-after")),
      createdAtEpochMs = Some(1_782_000_000_000L),
      rated = Some(true),
      pairingId = pairingId,
      partnerGameId = partnerGameId
    )

  private def ended(result: GameResult = GameResult.Win(Side.White), termination: Termination = Termination.Resign) =
    GameStatus.Ended(GameOver(result, termination))

  test("an unpaired finished game reveals its fairness block immediately, cached as immutable"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("hr-unpaired-white")
        val black = Principal.Bot("hr-team", "hr-bot")
        for
          id   <- GameId.random
          _    <- db.save(id, snapshotFixture(white, black, ended()))
          resp <- get(app(db), id.value)
          json <- resp.as[io.circe.Json]
        yield
          assertEquals(resp.status, Status.Ok)
          val c = json.hcursor
          assertEquals(c.downField("players").downField("white").get[String]("kind").toOption, Some("Human"))
          assertEquals(c.downField("players").downField("white").get[Option[String]]("name").toOption, Some(None))
          assertEquals(c.downField("players").downField("black").get[String]("kind").toOption, Some("Bot"))
          assertEquals(
            c.downField("players").downField("black").get[Option[String]]("name").toOption,
            Some(Some("hr-team hr-bot"))
          )
          assertEquals(c.downField("timeControl").downField("Fischer").get[Int]("initialSeconds").toOption, Some(300))
          assertEquals(c.get[Int]("result").toOption, Some(1))
          assertEquals(c.get[String]("termination").toOption, Some("resign"))
          assertEquals(
            c.downField("turns").downN(0).get[List[String]]("moves").toOption,
            Some(List("e2e4"))
          )
          assertEquals(c.downField("turns").downN(0).get[String]("activeColor").toOption, Some("White"))
          assertEquals(c.downField("fairness").get[String]("seed").toOption, Some("ab12cd34"))
          assertEquals(
            c.downField("fairness").downField("clientSeeds").get[String]("white").toOption,
            Some("white-seed")
          )
          assertEquals(
            resp.headers.get[`Cache-Control`],
            Some(
              `Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(365.days), CacheDirective("immutable"))
            )
          )
      }
    }

  test("a CRN-paired game withholds seed/clientSeeds until the partner also concludes, then self-heals (#115)"):
    withContainers { pg =>
      store(pg).use { db =>
        val pairingId = "33333333-3333-3333-3333-333333333333"
        val whiteA    = Principal.Bot("hr-team", "hr-alpha")
        val blackA    = Principal.Bot("hr-team", "hr-beta")
        for
          idA <- GameId.random
          idB <- GameId.random
          _   <- db.save(
            idA,
            snapshotFixture(
              whiteA,
              blackA,
              ended(),
              pairingId = Some(pairingId),
              partnerGameId = Some(idB.value)
            )
          )
          withheldResp <- get(app(db), idA.value)
          withheldJson <- withheldResp.as[io.circe.Json]
          // The partner (idB) hasn't been saved as ended yet — game_results has no row for it.
          _ = assertEquals(withheldResp.status, Status.Ok)
          _ = assertEquals(withheldJson.hcursor.downField("fairness").get[Option[String]]("seed").toOption, Some(None))
          _ = assertEquals(
            withheldJson.hcursor.downField("fairness").get[Option[String]]("commit").toOption.map(_.isDefined),
            Some(true),
            "commit is never gated — only the reveal is"
          )
          _ = assertEquals(
            withheldResp.headers.get[`Cache-Control`],
            Some(`Cache-Control`(CacheDirective.public, CacheDirective.`max-age`(60.seconds)))
          )
          // Now the partner concludes too.
          _ <- db.save(
            idB,
            snapshotFixture(blackA, whiteA, ended(), pairingId = Some(pairingId), partnerGameId = Some(idA.value))
          )
          revealedResp <- get(app(db), idA.value)
          revealedJson <- revealedResp.as[io.circe.Json]
        yield assertEquals(
          revealedJson.hcursor.downField("fairness").get[String]("seed").toOption,
          Some("ab12cd34"),
          "the reveal must self-heal once the partner has also concluded, without any write to this game's own row"
        )
      }
    }

  test("an unknown game id 404s"):
    withContainers { pg =>
      store(pg).use { db =>
        get(app(db), "00000000-0000-0000-0000-000000000000").map(resp => assertEquals(resp.status, Status.NotFound))
      }
    }

  test("an active (not yet ended) game 404s — it has no archive row yet"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("hr-active-white")
        val black = Principal.Guest("hr-active-black")
        for
          id   <- GameId.random
          _    <- db.save(id, snapshotFixture(white, black, GameStatus.Active))
          resp <- get(app(db), id.value)
        yield assertEquals(resp.status, Status.NotFound)
      }
    }

  /** A second, unpooled connection to the SAME database — used only to simulate the pre-#177 backfill gap below by
    * deleting an archive row out from under `PgGameStore.save`'s usual atomicity (something no production code path
    * ever does; this is a test-only forgery of "a game that finished before the archive existed").
    */
  private def rawXa(pg: PostgreSQLContainer) =
    for
      connectEC <- ExecutionContexts.fixedThreadPool[IO](2)
      xa        <- HikariTransactor
        .newHikariTransactor[IO]("org.postgresql.Driver", pg.jdbcUrl, pg.username, pg.password, connectEC)
    yield xa

  test("a finished game with no archive row 404s (the pre-#177 backfill gap, #178)"):
    withContainers { pg =>
      (store(pg), rawXa(pg)).tupled.use { (db, xa) =>
        val white = Principal.Guest("hr-nobackfill-white")
        val black = Principal.Guest("hr-nobackfill-black")
        for
          id <- GameId.random
          _  <- db.save(id, snapshotFixture(white, black, ended()))
          // Simulate a pre-#177 game: game_results exists (the game plainly finished), but its archive row doesn't —
          // exactly the gap the design note calls out (the ~11k pre-migration games, never backfilled).
          _       <- sql"DELETE FROM play.game_archive WHERE game_id = ${id.value}::uuid".update.run.transact(xa)
          resp    <- get(app(db), id.value)
          results <- db.recentResultsFor(white.externalId)
        yield
          assertEquals(resp.status, Status.NotFound)
          assert(results.exists(_.gameId.value == id.value), "the game must still show as finished in game_results")
      }
    }
