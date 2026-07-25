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

  private def batch(db: PgGameStore): RatingBatch = new RatingBatch(db, db, db, RatingBatch.Config.Default)

  private def endedFixture(
      white: Principal,
      black: Principal,
      rated: Boolean,
      result: GameResult = GameResult.Win(Side.White),
      termination: Termination = Termination.Resign,
      pairingId: Option[GameId] = None
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
      status = GameStatus.Ended(GameOver(result, termination)),
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 1000L, Seat.Black -> 1000L),
      lastRoll = Nil,
      turns = Vector.empty,
      rated = Some(rated),
      pairingId = pairingId.map(_.value)
    )

  /** One mirrored ladder pairing (#101) that `loser` loses BOTH halves of — the shape the auto-park streak counts.
    * `loser` sits White in one game and Black in the other, so the stored white-POV result flips between them.
    */
  private def saveLostPairing(
      db: PgGameStore,
      loser: Principal.Bot,
      winner: Principal.Bot,
      termination: Termination
  ): IO[Unit] =
    for
      pairingId  <- GameId.random
      loserWhite <- GameId.random
      loserBlack <- GameId.random
      _          <- db.save(
        loserWhite,
        endedFixture(loser, winner, rated = true, GameResult.Win(Side.Black), termination, Some(pairingId))
      )
      _ <- db.save(
        loserBlack,
        endedFixture(winner, loser, rated = true, GameResult.Win(Side.White), termination, Some(pairingId))
      )
    yield ()

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

  test("the batch parks a bot that lost both games of two consecutive ladder pairings on the clock (#150)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb8")
          _            <- db.setOnLadder("rb8", "alice", onLadder = true)
          _            <- db.setOnLadder("rb8", "bob", onLadder = true)
          _            <- saveLostPairing(db, loser = alice, winner = bob, Termination.Timeout)
          _            <- saveLostPairing(db, loser = alice, winner = bob, Termination.Timeout)
          _            <- batch(db).tick
          aliceRating  <- db.ratingOf("rb8", "alice")
          bobRating    <- db.ratingOf("rb8", "bob")
        yield
          assertEquals(aliceRating.map(_.onLadder), Some(false), "alice flagged in all four games")
          assertEquals(bobRating.map(_.onLadder), Some(true), "banking timeout wins must never park the winner")
      }
    }

  test("the batch forgives a single fully-timed-out pairing — one transient blip must not park a bot"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb9")
          _            <- db.setOnLadder("rb9", "alice", onLadder = true)
          _            <- saveLostPairing(db, loser = alice, winner = bob, Termination.Timeout)
          _            <- batch(db).tick
          aliceRating  <- db.ratingOf("rb9", "alice")
        yield assertEquals(aliceRating.map(_.onLadder), Some(true), "the default threshold is two pairings, not one")
      }
    }

  test("the batch never parks a bot that keeps answering — repeated normal losses are not a timeout streak"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb10")
          _            <- db.setOnLadder("rb10", "alice", onLadder = true)
          _            <- saveLostPairing(db, loser = alice, winner = bob, Termination.KingCaptured)
          _            <- saveLostPairing(db, loser = alice, winner = bob, Termination.KingCaptured)
          _            <- batch(db).tick
          aliceRating  <- db.ratingOf("rb10", "alice")
        yield assertEquals(aliceRating.map(_.onLadder), Some(true), "a weak-but-live bot stays on the ladder")
      }
    }

/** Pure parsing/config/streak logic — no container. */
class RatingBatchPureSuite extends munit.FunSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  private def row(
      white: Principal.Bot,
      black: Principal.Bot,
      result: Int,
      termination: String,
      pairingId: Option[String],
      at: Long,
      gameId: String
  ): GameResultRow =
    GameResultRow(
      GameId(gameId),
      white.externalId,
      black.externalId,
      Some(result),
      termination,
      rated = true,
      timeControl = "Fischer(300,3)",
      serverSeed = "seed",
      pairingId = pairingId,
      finishedAt = java.time.Instant.EPOCH.plusSeconds(at)
    )

  /** One mirrored pairing that `loser` lost both halves of, newest game first — the order the store hands back. */
  private def lostPairing(
      loser: Principal.Bot,
      winner: Principal.Bot,
      termination: String,
      pairing: String,
      at: Long
  ): List[GameResultRow] =
    List(
      row(winner, loser, result = 1, termination, Some(pairing), at + 1, s"$pairing-b"),
      row(loser, winner, result = -1, termination, Some(pairing), at, s"$pairing-a")
    )

  test("isTimeoutLossFor is seat-aware, ignores wins, and never matches a bot that did not play"):
    val carol: Principal.Bot = Principal.Bot("acme", "carol")
    assert(RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "timeout", Some("p"), 1, "g1"), alice))
    assert(RatingBatch.isTimeoutLossFor(row(bob, alice, 1, "timeout", Some("p"), 2, "g2"), alice))
    assert(
      !RatingBatch.isTimeoutLossFor(row(alice, bob, 1, "timeout", Some("p"), 3, "g3"), alice),
      "flagging the OPPONENT is a win, not a loss"
    )
    assert(!RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "king_captured", Some("p"), 4, "g4"), alice))
    assert(!RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "timeout", Some("p"), 5, "g5"), carol))

  test("shouldPark needs the whole streak: one fully-timed-out pairing is forgiven, two are not"):
    val one = lostPairing(alice, bob, "timeout", "p1", at = 100)
    val two = lostPairing(alice, bob, "timeout", "p2", at = 200) ++ one
    assert(!RatingBatch.shouldPark(one, alice, parkPairs = 2), "a single bad pairing is a blip, not a dead bot")
    assert(RatingBatch.shouldPark(two, alice, parkPairs = 2))
    assert(!RatingBatch.shouldPark(two, bob, parkPairs = 2), "the bot banking those wins must never be parked")

  test("shouldPark counts only losses on the clock — a game the bot actually answered breaks the streak"):
    val answered = lostPairing(alice, bob, "king_captured", "p3", at = 300)
    val streak   =
      lostPairing(alice, bob, "timeout", "p2", at = 200) ++ lostPairing(alice, bob, "timeout", "p1", at = 100)
    assert(RatingBatch.shouldPark(streak, alice, parkPairs = 2))
    assert(
      !RatingBatch.shouldPark(answered ++ streak, alice, parkPairs = 2),
      "the newer answered pairing displaces the older timeout from the window"
    )

  test("shouldPark ignores games with no pairing id — a casual or challenge timeout can never park a bot"):
    val casual = List(
      row(bob, alice, result = 1, "timeout", pairingId = None, 401, "casual-1"),
      row(alice, bob, result = -1, "timeout", pairingId = None, 400, "casual-2")
    )
    assert(
      !RatingBatch.shouldPark(casual ++ lostPairing(alice, bob, "timeout", "p1", at = 100), alice, parkPairs = 2),
      "four timeout losses, but only one of them from a ladder pairing"
    )

  test("shouldPark skips a pairing whose mirror game has not been recorded yet"):
    val halfDone = lostPairing(alice, bob, "timeout", "p9", at = 900).take(1)
    val complete =
      lostPairing(alice, bob, "timeout", "p2", at = 200) ++ lostPairing(alice, bob, "timeout", "p1", at = 100)
    assert(
      RatingBatch.shouldPark(halfDone ++ complete, alice, parkPairs = 2),
      "an in-flight pairing is evidence neither way; the two completed ones still decide"
    )
    assert(
      !RatingBatch.shouldPark(halfDone ++ complete.take(2), alice, parkPairs = 2),
      "with only one completed pairing left, the threshold is not met"
    )

  test("shouldPark orders pairings by finished_at, not by the order the store happened to return them"):
    val older   = lostPairing(alice, bob, "timeout", "p1", at = 100)
    val newer   = lostPairing(alice, bob, "king_captured", "p2", at = 200)
    val jumbled = older ++ newer // deliberately oldest-first, unlike the store
    assert(RatingBatch.shouldPark(older, alice, parkPairs = 1))
    assert(
      !RatingBatch.shouldPark(jumbled, alice, parkPairs = 1),
      "the newest pairing was answered, so a one-pairing threshold must not fire"
    )

  test("parkScanLimit is bounded but never narrows to the bare ladder-only need"):
    assertEquals(RatingBatch.parkScanLimit(2), GameResultsStore.DefaultRecentLimit)
    assertEquals(RatingBatch.parkScanLimit(100), 800)
    assert(
      RatingBatch.parkScanLimit(RatingBatch.Config.DefaultLadderTimeoutParkPairs) > 2 * 2,
      "the window must leave room for casual games interleaved with the ladder pairings"
    )

  test("Principal.fromBotExternalId accepts only the canonical bot:team:<team>:<name> shape"):
    assertEquals(
      Principal.fromBotExternalId("bot:team:acme:alice"),
      Some(Principal.Bot("acme", "alice")): Option[Principal.Bot]
    )
    assertEquals(Principal.fromBotExternalId("guest:0198-uuid"), None)
    assertEquals(Principal.fromBotExternalId("user:42"), None)
    assertEquals(
      Principal.fromBotExternalId("bot:greedy"),
      None,
      "legacy bot:<algorithm> ids are not registered identities"
    )
    assertEquals(Principal.fromBotExternalId("bot:team:acme:"), None, "an empty name must not parse")
    assertEquals(Principal.fromBotExternalId("bot:team::alice"), None, "an empty team must not parse")

  test("scores maps the white-POV result vocabulary and nothing else"):
    assertEquals(RatingBatch.scores(1), Some((1.0, 0.0)))
    assertEquals(RatingBatch.scores(0), Some((0.5, 0.5)))
    assertEquals(RatingBatch.scores(-1), Some((0.0, 1.0)))
    assertEquals(RatingBatch.scores(2), None)

  test(
    "a non-positive or unparseable interval disables the batch; a bad batch size or park pairs falls back to the default"
  ):
    assertEquals(RatingBatch.Config.fromValues(Some("0"), None, None), None)
    assertEquals(RatingBatch.Config.fromValues(Some("-5"), None, None), None)
    assertEquals(RatingBatch.Config.fromValues(Some("junk"), None, None), None)
    assertEquals(RatingBatch.Config.fromValues(None, Some("50"), None), None)
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), Some("0"), None).map(_.batchSize),
      Some(RatingBatch.Config.DefaultBatchSize)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("60"), None, Some("0")).map(_.ladderTimeoutParkPairs),
      Some(RatingBatch.Config.DefaultLadderTimeoutParkPairs)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("45"), Some("7"), Some("3")),
      Some(RatingBatch.Config(45.seconds, 7, 3))
    )
