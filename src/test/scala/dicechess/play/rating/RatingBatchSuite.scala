package dicechess.play.rating

import cats.effect.{IO, Ref}
import cats.syntax.all.*
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

  private def batch(db: PgGameStore): IO[RatingBatch] =
    StrengthCache.create.map(new RatingBatch(db, db, db, RatingBatch.Config.Default, _))

  private def endedFixture(
      white: Principal,
      black: Principal,
      rated: Boolean,
      result: GameResult = GameResult.Win(Side.White),
      termination: Termination = Termination.Resign,
      ladder: Boolean = false
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
      ladder = Some(ladder)
    )

  /** One ladder-scheduler game (#190) that `loser` loses — the shape the auto-park streak counts (`ladder = true`, the
    * marker that replaced CRN pairing's `pairingId` for this purpose).
    */
  private def saveLostLadderGame(
      db: PgGameStore,
      loser: Principal.Bot,
      winner: Principal.Bot,
      termination: Termination
  ): IO[Unit] =
    GameId.random.flatMap(id =>
      db.save(id, endedFixture(loser, winner, rated = true, GameResult.Win(Side.Black), termination, ladder = true))
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
          _            <- batch(db).flatMap(_.tick)
          aliceR       <- db.ratingOf("rb1", "alice").map(_.getOrElse(fail("alice missing")))
          bobR         <- db.ratingOf("rb1", "bob").map(_.getOrElse(fail("bob missing")))
          queued       <- stillQueued(db, id)
          _       <- batch(db).flatMap(_.tick) // second tick: nothing left to apply for this game
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
          _            <- batch(db).flatMap(_.tick)
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
          _            <- batch(db).flatMap(_.tick) // "before the restart"
          afterFirst   <- db.ratingOf("rb3", "alice").map(_.getOrElse(fail("alice missing")))
          // The "restart": a brand-new batch over the same store — no in-memory cursor to lose (#119's design).
          restarted <- batch(db)
          _         <- restarted.tick
          replayed  <- db.ratingOf("rb3", "alice").map(_.getOrElse(fail("alice missing")))
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
          _          <- batch(db).flatMap(_.tick)
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
          _ <- batch(db).flatMap(_.tick)
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
          _          <- batch(db).flatMap(_.tick)
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

  test("the batch parks a bot that lost its last several ladder games on the clock (#150)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb8")
          _            <- db.setOnLadder("rb8", "alice", onLadder = true)
          _            <- db.setOnLadder("rb8", "bob", onLadder = true)
          _            <- List
            .fill(RatingBatch.Config.DefaultLadderTimeoutParkGames)(())
            .traverse_(_ => saveLostLadderGame(db, loser = alice, winner = bob, Termination.Timeout))
          _           <- batch(db).flatMap(_.tick)
          aliceRating <- db.ratingOf("rb8", "alice")
          bobRating   <- db.ratingOf("rb8", "bob")
        yield
          assertEquals(aliceRating.map(_.onLadder), Some(false), "alice timed out every game in the window")
          assertEquals(bobRating.map(_.onLadder), Some(true), "banking timeout wins must never park the winner")
      }
    }

  test("the batch forgives fewer timeouts than the threshold — a transient blip must not park a bot"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb9")
          _            <- db.setOnLadder("rb9", "alice", onLadder = true)
          _            <- List
            .fill(RatingBatch.Config.DefaultLadderTimeoutParkGames - 1)(())
            .traverse_(_ => saveLostLadderGame(db, loser = alice, winner = bob, Termination.Timeout))
          _           <- batch(db).flatMap(_.tick)
          aliceRating <- db.ratingOf("rb9", "alice")
        yield assertEquals(
          aliceRating.map(_.onLadder),
          Some(true),
          s"the default threshold is ${RatingBatch.Config.DefaultLadderTimeoutParkGames} games, not one fewer"
        )
      }
    }

  test("the batch never parks a bot that keeps answering — repeated normal losses are not a timeout streak"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob) <- registerPair(db, "rb10")
          _            <- db.setOnLadder("rb10", "alice", onLadder = true)
          _            <- List
            .fill(RatingBatch.Config.DefaultLadderTimeoutParkGames)(())
            .traverse_(_ => saveLostLadderGame(db, loser = alice, winner = bob, Termination.KingCaptured))
          _           <- batch(db).flatMap(_.tick)
          aliceRating <- db.ratingOf("rb10", "alice")
        yield assertEquals(aliceRating.map(_.onLadder), Some(true), "a weak-but-live bot stays on the ladder")
      }
    }

  test("a tick that applies a rated game also warms the strength cache with a report that includes it (#181)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob)  <- registerPair(db, "rb11")
          id            <- GameId.random
          _             <- db.save(id, endedFixture(alice, bob, rated = true)) // alice (White) wins
          strengthCache <- StrengthCache.create
          before        <- strengthCache.get
          _             <- new RatingBatch(db, db, db, RatingBatch.Config.Default, strengthCache).tick
          after         <- strengthCache.get
        yield
          assertEquals(before, None, "the cache starts cold")
          assert(after.isDefined, "a tick that applied a game must warm the cache")
          assert(
            after.exists(_.pairwise.exists(p => p.perspective == "rb11/alice" || p.opponent == "rb11/alice")),
            "the refreshed report must include the game the same tick just applied"
          )
      }
    }

  test(
    "a batchSize of 1 forces drainQueue's multi-page recursion, and one outer tick still applies every page " +
      "and warms the cache with all of them (#181)"
  ):
    withContainers { pg =>
      store(pg).use { db =>
        for
          (alice, bob)  <- registerPair(db, "rb12")
          id1           <- GameId.random
          id2           <- GameId.random
          _             <- db.save(id1, endedFixture(alice, bob, rated = true))
          _             <- IO.sleep(20.millis) // distinguishable finished_at (DB-generated), same as the rb7 test
          _             <- db.save(id2, endedFixture(bob, alice, rated = true))
          strengthCache <- StrengthCache.create
          onePerPage = RatingBatch.Config.Default.copy(batchSize = 1)
          _       <- new RatingBatch(db, db, db, onePerPage, strengthCache).tick
          queued1 <- stillQueued(db, id1)
          queued2 <- stillQueued(db, id2)
          report  <- strengthCache.get
        yield
          assert(!queued1 && !queued2, "one outer tick must drain every page, not just the first")
          assert(
            report.exists(_.pairwise.exists(p => p.perspective == "rb12/alice" || p.opponent == "rb12/alice")),
            "the cache refresh must run once at the end, after every page's games are already applied"
          )
      }
    }

/** The auto-park check's failure handling, over fakes — no container. */
class RatingBatchResilienceSuite extends CatsEffectSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  private val aliceTimedOut: GameResultRow = GameResultRow(
    GameId("11111111-1111-1111-1111-111111111111"),
    alice.externalId,
    bob.externalId,
    Some(-1),
    "timeout",
    rated = true,
    timeControl = "Fischer(300,3)",
    serverSeed = "seed",
    pairingId = None,
    ladder = true,
    finishedAt = java.time.Instant.EPOCH
  )

  /** Hands the batch exactly one queued game, then reports the queue drained. */
  private def oneGameQueue(queue: Ref[IO, List[GameResultRow]]): RatingStore = new RatingStore:
    def unappliedRatedGames(limit: Int): IO[List[GameResultRow]] = queue.getAndSet(Nil)
    def markRatingApplied(gameId: GameId): IO[Unit]              = IO.unit
    def applyRatingUpdate(
        gameId: GameId,
        white: Principal.Bot,
        whiteGlicko: Glicko,
        black: Principal.Bot,
        blackGlicko: Glicko
    ): IO[Unit] = IO.unit

  private val unreachableResults: GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] =
      IO.raiseError(new RuntimeException("connection pool exhausted"))
    def finishedRatedSince(since: java.time.Instant): IO[List[GameResultRow]] = IO.pure(Nil)
    def playerGamesPage(
        externalId: String,
        before: Option[java.time.Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] = IO.pure(GameResultsStore.Page(Nil, hasMore = false))
    def opponentsFor(externalId: String): IO[List[OpponentAggregateRow]] = IO.pure(Nil)

  test("a history query that fails mid-check is logged and never aborts the tick around it"):
    for
      bots          <- BotStore.inMemory
      _             <- bots.register("acme", "alice", "hash-alice")
      _             <- bots.register("acme", "bob", "hash-bob")
      _             <- bots.setOnLadder("acme", "alice", onLadder = true)
      _             <- bots.setOnLadder("acme", "bob", onLadder = true)
      queue         <- Ref.of[IO, List[GameResultRow]](List(aliceTimedOut))
      strengthCache <- StrengthCache.create
      batch = new RatingBatch(bots, oneGameQueue(queue), unreachableResults, RatingBatch.Config.Default, strengthCache)
      // The rating for this row has already committed by the time the check runs, so raising here would abort the rest
      // of the page for unrelated bots and buy nothing — the row is stamped and never returns to the queue.
      _           <- batch.tick
      aliceRating <- bots.ratingOf("acme", "alice")
    yield assertEquals(aliceRating.map(_.onLadder), Some(true), "a check that never completed must park nobody")

  /** Counts calls to `finishedRatedSince` rather than serving real rows — the strength refresh's own cost the cache
    * exists to bound, made observable without a container (#181).
    */
  private def countingResults(counter: Ref[IO, Int]): GameResultsStore = new GameResultsStore:
    def recentResultsFor(externalId: String, limit: Int): IO[List[GameResultRow]] = IO.pure(Nil)
    def finishedRatedSince(since: java.time.Instant): IO[List[GameResultRow]]     = counter.update(_ + 1).as(Nil)
    def playerGamesPage(
        externalId: String,
        before: Option[java.time.Instant],
        opponent: Option[OpponentFilter],
        result: Option[PovResultFilter],
        limit: Int
    ): IO[GameResultsStore.Page] = IO.pure(GameResultsStore.Page(Nil, hasMore = false))
    def opponentsFor(externalId: String): IO[List[OpponentAggregateRow]] = IO.pure(Nil)

  test("a cold cache is warmed even with nothing to apply, but a warm one is not refreshed again for free (#181)"):
    for
      bots          <- BotStore.inMemory
      refreshCount  <- Ref.of[IO, Int](0)
      emptyQueue    <- Ref.of[IO, List[GameResultRow]](Nil)
      strengthCache <- StrengthCache.create
      batch = new RatingBatch(
        bots,
        oneGameQueue(emptyQueue),
        countingResults(refreshCount),
        RatingBatch.Config.Default,
        strengthCache
      )
      _      <- batch.tick
      countA <- refreshCount.get
      _      <- batch.tick
      countB <- refreshCount.get
    yield
      assertEquals(countA, 1, "a cold cache must be warmed even when the drain applied nothing")
      assertEquals(countB, 1, "a warm cache must not be refreshed again by a tick that applied nothing new")

/** Pure parsing/config/streak logic — no container. */
class RatingBatchPureSuite extends munit.FunSuite:

  private val alice: Principal.Bot = Principal.Bot("acme", "alice")
  private val bob: Principal.Bot   = Principal.Bot("acme", "bob")

  private def row(
      white: Principal.Bot,
      black: Principal.Bot,
      result: Int,
      termination: String,
      ladder: Boolean,
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
      pairingId = None,
      ladder = ladder,
      finishedAt = java.time.Instant.EPOCH.plusSeconds(at)
    )

  test("isTimeoutLossFor is seat-aware, ignores wins, and never matches a bot that did not play"):
    val carol: Principal.Bot = Principal.Bot("acme", "carol")
    assert(RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "timeout", ladder = true, 1, "g1"), alice))
    assert(RatingBatch.isTimeoutLossFor(row(bob, alice, 1, "timeout", ladder = true, 2, "g2"), alice))
    assert(
      !RatingBatch.isTimeoutLossFor(row(alice, bob, 1, "timeout", ladder = true, 3, "g3"), alice),
      "flagging the OPPONENT is a win, not a loss"
    )
    assert(!RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "king_captured", ladder = true, 4, "g4"), alice))
    assert(!RatingBatch.isTimeoutLossFor(row(alice, bob, -1, "timeout", ladder = true, 5, "g5"), carol))

  test("shouldPark needs the whole streak: one timeout loss is forgiven, two are not"):
    val one = List(row(bob, alice, result = 1, "timeout", ladder = true, at = 100, "g1"))
    val two = row(bob, alice, result = 1, "timeout", ladder = true, at = 200, "g2") :: one
    assert(!RatingBatch.shouldPark(one, alice, parkGames = 2), "a single loss is a blip, not a dead bot")
    assert(RatingBatch.shouldPark(two, alice, parkGames = 2))
    assert(!RatingBatch.shouldPark(two, bob, parkGames = 2), "the bot banking those wins must never be parked")

  test("shouldPark counts only losses on the clock — a game the bot actually answered breaks the streak"):
    val answered = row(bob, alice, result = 1, "king_captured", ladder = true, at = 300, "g3")
    val streak   = List(
      row(bob, alice, result = 1, "timeout", ladder = true, at = 200, "g2"),
      row(bob, alice, result = 1, "timeout", ladder = true, at = 100, "g1")
    )
    assert(RatingBatch.shouldPark(streak, alice, parkGames = 2))
    assert(
      !RatingBatch.shouldPark(answered :: streak, alice, parkGames = 2),
      "the newer answered game displaces the older timeout from the window"
    )

  test("shouldPark ignores non-ladder games — a casual or challenge timeout can never park a bot"):
    val casual = List(
      row(bob, alice, result = 1, "timeout", ladder = false, at = 401, "casual-1"),
      row(alice, bob, result = -1, "timeout", ladder = false, at = 400, "casual-2")
    )
    val ladderLoss = List(row(bob, alice, result = 1, "timeout", ladder = true, at = 100, "g1"))
    assert(
      !RatingBatch.shouldPark(casual ++ ladderLoss, alice, parkGames = 2),
      "three timeout losses, but only one of them from a ladder game"
    )

  test("parkScanLimit is bounded but never narrows to the bare ladder-only need"):
    assertEquals(
      RatingBatch.parkScanLimit(RatingBatch.Config.DefaultLadderTimeoutParkGames),
      GameResultsStore.DefaultRecentLimit
    )
    assertEquals(RatingBatch.parkScanLimit(100), 400)
    assert(
      RatingBatch.parkScanLimit(RatingBatch.Config.DefaultLadderTimeoutParkGames) >
        RatingBatch.Config.DefaultLadderTimeoutParkGames,
      "the window must leave room for casual games interleaved with the ladder games"
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
    "a non-positive or unparseable interval disables the batch; a bad batch size or park games falls back to the default"
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
      RatingBatch.Config.fromValues(Some("60"), None, Some("0")).map(_.ladderTimeoutParkGames),
      Some(RatingBatch.Config.DefaultLadderTimeoutParkGames)
    )
    assertEquals(
      RatingBatch.Config.fromValues(Some("45"), Some("7"), Some("3")),
      Some(RatingBatch.Config(45.seconds, 7, 3))
    )
