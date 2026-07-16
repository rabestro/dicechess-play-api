package dicechess.play.rating

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.store.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import scala.concurrent.duration.*

/** The rating batch against a real PostgreSQL (#119): the claim queue, the exactly-once transaction, the skip paths,
  * and the property the design exists for — a "restart" (a brand-new batch instance over the same store) neither
  * re-applies old games nor misses new ones, because all state lives in the database.
  *
  * The suite shares one database across its tests (`TestContainerForAll`, no per-test reset), so each test uses its own
  * team namespace and asserts on its own game ids, never on global queue emptiness.
  */
class RatingBatchSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def batch(db: PgGameStore): RatingBatch = new RatingBatch(db, db, RatingBatch.Config.Default)

  private def endedFixture(
      white: Principal,
      black: Principal,
      rated: Boolean,
      result: GameResult = GameResult.Win(Side.White)
  ): GameSnapshot =
    GameSnapshot(
      version = 3L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> white, Seat.Black -> black),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map.empty,
      started = true,
      ply = 2L,
      pending = false,
      status = GameStatus.Ended(GameOver(result, Termination.Resign)),
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 1000L, Seat.Black -> 1000L),
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(rated)
    )

  private def registerPair(db: PgGameStore, team: String): IO[(Principal.Bot, Principal.Bot)] =
    for
      _ <- db.register(team, "alice", s"hash-$team-alice")
      _ <- db.register(team, "bob", s"hash-$team-bob")
    yield (Principal.Bot(team, "alice"), Principal.Bot(team, "bob"))

  private def stillQueued(db: PgGameStore, id: GameId): IO[Boolean] =
    db.unappliedRatedGames(1000).map(_.exists(_.gameId.value == id.value))

  test("a rated game shifts both ratings, shrinks both deviations, and is applied exactly once"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb1")
          id           <- GameId.random
          _            <- db.save(id, endedFixture(alice, bob, rated = true)) // alice (White) wins
          _            <- batch(db).tick
          aliceR       <- db.ratingOf("rb1", "alice").map(_.getOrElse(fail("alice missing")))
          bobR         <- db.ratingOf("rb1", "bob").map(_.getOrElse(fail("bob missing")))
          queued       <- stillQueued(db, id)
          _       <- batch(db).tick // second tick: nothing left to apply for this game
          aliceR2 <- db.ratingOf("rb1", "alice").map(_.getOrElse(fail("alice missing")))
        yield
          assert(aliceR.glickoRating > 1500.0, s"the winner must gain: ${aliceR.glickoRating}")
          assert(bobR.glickoRating < 1500.0, s"the loser must lose: ${bobR.glickoRating}")
          assert(aliceR.glickoRd < 350.0 && bobR.glickoRd < 350.0, "playing must shrink both RDs")
          assert(!queued, "an applied game must leave the queue")
          assertEquals(aliceR2, aliceR, "a second tick must not re-apply the same game")
      }
    }

  test("a casual game never enters the queue and changes no rating"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb2")
          id           <- GameId.random
          _            <- db.save(id, endedFixture(alice, bob, rated = false))
          queued       <- stillQueued(db, id)
          _            <- batch(db).tick
          aliceR       <- db.ratingOf("rb2", "alice")
        yield
          assert(!queued, "a casual game must not be queued for rating")
          assertEquals(aliceR, Some(BotRating.initial), "a casual game must leave the rating untouched")
      }
    }

  test("restart safety: a fresh batch instance re-applies nothing and picks up new games (#119)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb3")
          id1          <- GameId.random
          _            <- db.save(id1, endedFixture(alice, bob, rated = true))
          _            <- batch(db).tick // "before the restart"
          afterFirst   <- db.ratingOf("rb3", "alice").map(_.getOrElse(fail("alice missing")))
          // The "restart": a brand-new batch over the same store — no in-memory cursor to lose (#119's design).
          restarted = batch(db)
          _        <- restarted.tick
          replayed <- db.ratingOf("rb3", "alice").map(_.getOrElse(fail("alice missing")))
          _ = assertEquals(replayed, afterFirst, "a restarted batch must not re-apply an already-stamped game")
          id2    <- GameId.random
          _      <- db.save(id2, endedFixture(bob, alice, rated = true)) // bob (White) wins the second game
          _      <- restarted.tick
          queued <- stillQueued(db, id2)
          bobR   <- db.ratingOf("rb3", "bob").map(_.getOrElse(fail("bob missing")))
        yield
          assert(!queued, "the restarted batch must apply the new game")
          assert(
            bobR.glickoRating > 1500.0,
            s"bob won one of two: his second result must lift him: ${bobR.glickoRating}"
          )
      }
    }

  test("a rated game with a non-bot participant is stamped applied without touching any rating"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, _) <- registerPair(db, "rb4")
          id         <- GameId.random
          _          <- db.save(id, endedFixture(Principal.User("rb4-human"), alice, rated = true))
          _          <- batch(db).tick
          queued     <- stillQueued(db, id)
          aliceR     <- db.ratingOf("rb4", "alice")
        yield
          assert(!queued, "an unappliable game must still be stamped, or it clogs the queue head forever")
          assertEquals(aliceR, Some(BotRating.initial), "no rating may change on a skipped game")
      }
    }

  test("a rated game between UNREGISTERED bots is stamped applied without crashing"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id <- GameId.random
          _ <- db.save(id, endedFixture(Principal.Bot("rb5-ghost", "x"), Principal.Bot("rb5-ghost", "y"), rated = true))
          _ <- batch(db).tick
          queued <- stillQueued(db, id)
        yield assert(!queued, "a game between unregistered bots must be stamped and skipped")
      }
    }

  test("rated self-play is stamped applied without a rating change"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, _) <- registerPair(db, "rb6")
          id         <- GameId.random
          _          <- db.save(id, endedFixture(alice, alice, rated = true))
          _          <- batch(db).tick
          queued     <- stillQueued(db, id)
          aliceR     <- db.ratingOf("rb6", "alice")
        yield
          assert(!queued, "self-play must be stamped and skipped")
          assertEquals(aliceR, Some(BotRating.initial), "self-play carries no rating information")
      }
    }

  test("unappliedRatedGames returns only rated, unapplied rows, oldest finished first"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb7")
          idOld        <- GameId.random
          _            <- db.save(idOld, endedFixture(alice, bob, rated = true))
          _            <- IO.sleep(20.millis) // distinguishable finished_at (DB-generated)
          idNew        <- GameId.random
          _            <- db.save(idNew, endedFixture(bob, alice, rated = true))
          idCasual     <- GameId.random
          _            <- db.save(idCasual, endedFixture(alice, bob, rated = false))
          _            <- db.markRatingApplied(idOld)
          queue        <- db
            .unappliedRatedGames(1000)
            .map(_.map(_.gameId.value).filter(Set(idOld, idNew, idCasual).map(_.value)))
        yield assertEquals(queue, List(idNew.value), "applied and casual rows must be excluded")
      }
    }

/** Pure parsing/config logic — no container. */
class RatingBatchPureSuite extends munit.FunSuite:

  test("parseBot accepts only the canonical bot:team:<team>:<name> shape"):
    assertEquals(
      RatingBatch.parseBot("bot:team:acme:alice"),
      Some(Principal.Bot("acme", "alice")): Option[Principal.Bot]
    )
    assertEquals(RatingBatch.parseBot("guest:0198-uuid"), None)
    assertEquals(RatingBatch.parseBot("user:42"), None)
    assertEquals(RatingBatch.parseBot("bot:greedy"), None, "legacy bot:<algorithm> ids are not registered identities")
    assertEquals(RatingBatch.parseBot("bot:team:acme:"), None, "an empty name must not parse")
    assertEquals(RatingBatch.parseBot("bot:team::alice"), None, "an empty team must not parse")

  test("scores maps the white-POV result vocabulary and nothing else"):
    assertEquals(RatingBatch.scores(1), Some((1.0, 0.0)))
    assertEquals(RatingBatch.scores(0), Some((0.5, 0.5)))
    assertEquals(RatingBatch.scores(-1), Some((0.0, 1.0)))
    assertEquals(RatingBatch.scores(2), None)

  test("a non-positive or unparseable interval disables the batch; a bad batch size falls back to the default"):
    assertEquals(RatingBatch.Config.fromValues(Some("0"), None), None)
    assertEquals(RatingBatch.Config.fromValues(Some("-5"), None), None)
    assertEquals(RatingBatch.Config.fromValues(Some("junk"), None), None)
    assertEquals(RatingBatch.Config.fromValues(None, Some("50")), None)
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), Some("0")).map(_.batchSize),
      Some(RatingBatch.Config.DefaultBatchSize)
    )
    assertEquals(RatingBatch.Config.fromValues(Some("45"), Some("7")), Some(RatingBatch.Config(45.seconds, 7)))
