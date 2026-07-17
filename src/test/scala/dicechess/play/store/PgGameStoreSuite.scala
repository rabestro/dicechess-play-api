package dicechess.play.store

import cats.effect.{Deferred, IO}
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import dicechess.play.core.*
import dicechess.play.game.EngineOps
import dicechess.play.server.GameRegistry
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import java.security.MessageDigest
import scala.concurrent.duration.*

/** Persistence against a real PostgreSQL (testcontainers): the store round-trip, and the property the whole feature
  * exists for — a live game, its fixed roll included, survives a "crash" (a brand-new registry over the same store).
  */
class PgGameStoreSuite extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private def store(pg: PostgreSQLContainer) =
    PgGameStore.resource(PgGameStore.Config(pg.jdbcUrl, pg.username, pg.password))

  private def snapshotFixture(status: GameStatus): GameSnapshot =
    GameSnapshot(
      version = 3L,
      dfen = EngineOps.InitialDfen,
      players = Map(Seat.White -> Principal.Guest("w-1"), Seat.Black -> Principal.Bot("house", "greedy")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = Map(Seat.White -> "white-seed-0123456789ab"),
      started = true,
      ply = 2L,
      pending = true,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map(Seat.White -> 295000L, Seat.Black -> 300000L),
      lastRoll = List(2, 3, 6),
      turns = Vector(TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-after"))
    )

  /** An ended snapshot with a distinct pair of players — `game_results` (#98) tests need their own participant
    * namespace, since this suite shares one database across every test in the file (`TestContainerForAll`, no per-test
    * reset) and `finishedRatedSince` in particular scans every row, not just a chosen participant's.
    */
  private def endedResultFixture(
      white: Principal,
      black: Principal,
      rated: Boolean = false,
      pairingId: Option[String] = None,
      result: GameResult = GameResult.Win(Side.White),
      termination: Termination = Termination.Resign
  ): GameSnapshot =
    snapshotFixture(GameStatus.Ended(GameOver(result, termination)))
      .copy(players = Map(Seat.White -> white, Seat.Black -> black), rated = Some(rated), pairingId = pairingId)

  test("a snapshot round-trips through jsonb, and upserts replace by game id"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id  <- GameId.random
          _   <- db.save(id, snapshotFixture(GameStatus.Active))
          _   <- db.save(id, snapshotFixture(GameStatus.Active).copy(version = 4L, ply = 3L))
          all <- db.loadActive
        yield
          val (loadedId, snap) = all.find(_._1.value == id.value).getOrElse(fail("saved game not loaded"))
          assertEquals(loadedId.value, id.value)
          assertEquals(snap, snapshotFixture(GameStatus.Active).copy(version = 4L, ply = 3L))
      }
    }

  test("bot identities round-trip: register once, authenticate by hash, rotate atomically"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          claimed  <- db.register("dragons", "smaug", "hash-1")
          dupe     <- db.register("dragons", "smaug", "hash-other")
          found    <- db.authenticate("hash-1")
          unknown  <- db.authenticate("hash-none")
          rotated  <- db.rotate("dragons", "smaug", "hash-2")
          oldDead  <- db.authenticate("hash-1")
          newAlive <- db.authenticate("hash-2")
          ghost    <- db.rotate("dragons", "nobody", "hash-3")
        yield
          assert(claimed, "a fresh identity must register")
          assert(!dupe, "the primary key must make the second claim lose")
          assertEquals(found, Some(Principal.Bot("dragons", "smaug")): Option[Principal.Bot])
          assertEquals(unknown, None)
          assert(rotated, "rotation of a registered identity must succeed")
          assertEquals(oldDead, None)
          assertEquals(newAlive, Some(Principal.Bot("dragons", "smaug")): Option[Principal.Bot])
          assert(!ghost, "rotating an unregistered identity must report false")
      }
    }

  test("bot rating state: fresh registration is provisional, on_ladder toggles atomically, unregistered is None"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          _       <- db.register("dragons", "smaug", "hash-1")
          initial <- db.ratingOf("dragons", "smaug")
          joined  <- db.setOnLadder("dragons", "smaug", true)
          reread  <- db.ratingOf("dragons", "smaug")
          left    <- db.setOnLadder("dragons", "smaug", false)
          ghost   <- db.setOnLadder("dragons", "nobody", true)
          unknown <- db.ratingOf("dragons", "nobody")
        yield
          assertEquals(initial, Some(BotRating.initial))
          assertEquals(joined, Some(BotRating.initial.copy(onLadder = true)))
          assertEquals(reread, joined, "the RETURNING result must match a fresh read, not just the pre-update state")
          assertEquals(left, Some(BotRating.initial))
          assertEquals(ghost, None, "toggling an unregistered identity must report None")
          assertEquals(unknown, None)
      }
    }

  test("onLadderBots lists only registered bots currently opted in (#102)"):
    withContainers { pg =>
      store(pg).use { db =>
        // A dedicated team/hash namespace: this suite shares one database across all tests (TestContainerForAll,
        // no per-test reset), so a name or token hash reused from another test in this file would collide on the
        // token_hash unique constraint — and a plain equality assertion on onLadderBots would be fragile against
        // whatever else in the file happens to be on_ladder. Both are avoided here.
        for
          _        <- db.register("ladder-suite", "on-bot", "hash-ladder-on")
          _        <- db.register("ladder-suite", "off-bot", "hash-ladder-off")
          _        <- db.setOnLadder("ladder-suite", "on-bot", true)
          onLadder <- db.onLadderBots
        yield
          assert(onLadder.contains(Principal.Bot("ladder-suite", "on-bot")), s"expected on-bot in $onLadder")
          assert(
            !onLadder.contains(Principal.Bot("ladder-suite", "off-bot")),
            s"expected off-bot absent from $onLadder"
          )
      }
    }

  test("finishing a game inserts exactly one game_results row with the expected fields (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white     = Principal.Guest("b2-white-1")
        val black     = Principal.Bot("b2-team", "b2-bot-1")
        val pairingId = "11111111-1111-1111-1111-111111111111"
        for
          id <- GameId.random
          _  <- db.save(
            id,
            endedResultFixture(white, black, rated = true, pairingId = Some(pairingId))
          )
          rows <- db.recentResultsFor(white.externalId)
        yield
          val row = rows.find(_.gameId.value == id.value).getOrElse(fail(s"row for $id not found in $rows"))
          assertEquals(row.whiteExternalId, white.externalId)
          assertEquals(row.blackExternalId, black.externalId)
          assertEquals(row.result, Some(1), "white won: white-POV result must be 1")
          assertEquals(row.termination, "resign")
          assert(row.rated)
          assertEquals(row.timeControl, TimeControl.Fischer(300, 3).toString)
          assertEquals(row.serverSeed, "ab12cd34")
          assertEquals(row.pairingId, Some(pairingId))
      }
    }

  test("an active (not yet ended) game does not get a game_results row (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-white-active")
        val black = Principal.Guest("b2-black-active")
        for
          id <- GameId.random
          _  <- db.save(
            id,
            snapshotFixture(GameStatus.Active).copy(players = Map(Seat.White -> white, Seat.Black -> black))
          )
          rows <- db.recentResultsFor(white.externalId)
        yield assert(rows.forall(_.gameId.value != id.value), s"an active game must not appear in game_results: $rows")
      }
    }

  test("recentResultsFor finds a game whichever seat the participant sat, newest first (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val participant = Principal.Guest("b2-recent-participant")
        val opponent1   = Principal.Guest("b2-recent-opp1")
        val opponent2   = Principal.Bot("b2-team", "b2-recent-opp2")
        for
          idAsWhite <- GameId.random
          _         <- db.save(idAsWhite, endedResultFixture(participant, opponent1)) // participant seated White
          // A short, deterministic gap: finished_at defaults to the DB's own now(), and the "newest first" ordering
          // this test checks needs the two inserts to land at genuinely distinguishable timestamps.
          _         <- IO.sleep(20.millis)
          idAsBlack <- GameId.random
          _         <- db.save(idAsBlack, endedResultFixture(opponent2, participant)) // participant seated Black
          rows      <- db.recentResultsFor(participant.externalId)
        yield assertEquals(
          rows.map(_.gameId.value),
          List(idAsBlack.value, idAsWhite.value),
          s"expected newest first: $rows"
        )
      }
    }

  test("finishedRatedSince returns only rated games finished strictly after the cursor (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val before = Principal.Guest("b2-since-w1")
        for
          idBefore <- GameId.random
          _        <- db.save(idBefore, endedResultFixture(before, Principal.Guest("b2-since-b1"), rated = true))
          // The cursor is the row's OWN database-generated finished_at, not a JVM-side Instant.now(): comparing a
          // local clock against Postgres's own now() would make this boundary assertion depend on the two clocks
          // being in sync, which isn't guaranteed (#98 review).
          beforeRow <- db
            .recentResultsFor(before.externalId)
            .map(_.find(_.gameId.value == idBefore.value).getOrElse(fail("row not found right after saving it")))
          cursor = beforeRow.finishedAt
          // A short, deterministic gap so the next inserts' own finished_at lands strictly after the cursor.
          _            <- IO.sleep(20.millis)
          idAfterRated <- GameId.random
          _            <- db.save(
            idAfterRated,
            endedResultFixture(Principal.Guest("b2-since-w2"), Principal.Guest("b2-since-b2"), rated = true)
          )
          idAfterCasual <- GameId.random
          _             <- db.save(
            idAfterCasual,
            endedResultFixture(Principal.Guest("b2-since-w3"), Principal.Guest("b2-since-b3"), rated = false)
          )
          since <- db.finishedRatedSince(cursor)
        yield
          val ids = since.map(_.gameId.value).toSet
          assert(!ids.contains(idBefore.value), "a game AT the cursor must be excluded (strictly after)")
          assert(ids.contains(idAfterRated.value), "a rated game finished after the cursor must be included")
          assert(!ids.contains(idAfterCasual.value), "a casual (non-rated) game must be excluded regardless of timing")
      }
    }

  test("pairFor returns both games sharing a pairing id, and nothing for an unknown one (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val pairingId = "22222222-2222-2222-2222-222222222222"
        val alice     = Principal.Bot("b2-team", "b2-alice")
        val bob       = Principal.Bot("b2-team", "b2-bob")
        for
          idA     <- GameId.random
          _       <- db.save(idA, endedResultFixture(alice, bob, rated = true, pairingId = Some(pairingId)))
          idB     <- GameId.random
          _       <- db.save(idB, endedResultFixture(bob, alice, rated = true, pairingId = Some(pairingId)))
          paired  <- db.pairFor(pairingId)
          unknown <- db.pairFor("33333333-3333-3333-3333-333333333333")
        yield
          assertEquals(paired.map(_.gameId.value).toSet, Set(idA.value, idB.value))
          assertEquals(unknown, Nil)
      }
    }

  test("pairFor returns an empty list for a malformed pairing id, instead of a database error (#98)"):
    withContainers { pg =>
      store(pg).use(db => db.pairFor("not-a-uuid").map(assertEquals(_, Nil)))
    }

  test("recentResultsFor does not double-count a self-played game (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        // GameRegistry.create itself doesn't forbid seating the same principal on both sides (only its
        // Lobby/Challenges callers do) — a UNION ALL of the white/black subqueries would otherwise return this
        // game twice.
        val soloPlayer = Principal.Guest("b2-self-play")
        for
          id   <- GameId.random
          _    <- db.save(id, endedResultFixture(soloPlayer, soloPlayer))
          rows <- db.recentResultsFor(soloPlayer.externalId)
        yield assertEquals(rows.count(_.gameId.value == id.value), 1, s"expected exactly one row, got $rows")
      }
    }

  test("saving the same ended snapshot twice still inserts exactly one game_results row (#98)"):
    withContainers { pg =>
      store(pg).use { db =>
        val white = Principal.Guest("b2-idempotent-white")
        val black = Principal.Guest("b2-idempotent-black")
        for
          id <- GameId.random
          fixture = endedResultFixture(white, black)
          _    <- db.save(id, fixture)
          _    <- db.save(id, fixture) // re-save: same game id, ON CONFLICT (game_id) DO NOTHING must hold
          rows <- db.recentResultsFor(white.externalId)
        yield assertEquals(rows.count(_.gameId.value == id.value), 1, s"expected exactly one row, got $rows")
      }
    }

  test("ended games are not resumed"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          id  <- GameId.random
          _   <- db.save(id, snapshotFixture(GameStatus.Ended(GameOver(GameResult.Draw, Termination.Draw))))
          all <- db.loadActive
        yield assert(all.forall(_._1.value != id.value), "an ended game must not appear in loadActive")
      }
    }

  test("leaderboard: converged bots best-first with rated W-D-L; provisional bots hidden (#103)"):
    withContainers { pg =>
      store(pg).use { db =>
        val strong: Principal.Bot = Principal.Bot("lb-suite", "strong")
        val weak: Principal.Bot   = Principal.Bot("lb-suite", "weak")
        for
          _ <- db.register("lb-suite", "strong", "hash-lb-strong")
          _ <- db.register("lb-suite", "weak", "hash-lb-weak")
          _ <- db.register("lb-suite", "fresh", "hash-lb-fresh") // untouched: RD 350 = provisional
          _ <- db.setOnLadder("lb-suite", "strong", true)
          // Converge both veterans' ratings. The stamped game id is random and matches no game_results row, so the
          // stamp inside applyRatingUpdate is a no-op — this is purely "set two bots' glicko state atomically".
          fakeId <- GameId.random
          _      <- db.applyRatingUpdate(
            fakeId,
            strong,
            dicechess.play.rating.Glicko(1700.0, 80.0, 0.05),
            weak,
            dicechess.play.rating.Glicko(1400.0, 90.0, 0.05)
          )
          // The rated record: strong beats weak once per colour, plus one draw; one casual win must not count.
          idA <- GameId.random
          _   <- db.save(idA, endedResultFixture(strong, weak, rated = true)) // strong wins as White
          idB <- GameId.random
          _   <- db.save(
            idB,
            endedResultFixture(weak, strong, rated = true, result = GameResult.Win(Side.Black))
          ) // strong wins as Black
          idC   <- GameId.random
          _     <- db.save(idC, endedResultFixture(strong, weak, rated = true, result = GameResult.Draw))
          idD   <- GameId.random
          _     <- db.save(idD, endedResultFixture(strong, weak, rated = false)) // casual: excluded from the tally
          board <- db.leaderboard(maxRd = 110.0).map(_.filter(_.team == "lb-suite"))
        yield
          assertEquals(board.map(_.name), List("strong", "weak"), "best rating first; provisional 'fresh' hidden")
          val strongRow = board.head
          assertEquals(strongRow.tally, ResultTally(wins = 2, draws = 1, losses = 0))
          assert(strongRow.onLadder, "the on-ladder flag must ride along")
          assertEquals(board(1).tally, ResultTally(wins = 0, draws = 1, losses = 2))
          assert(!board(1).onLadder)
      }
    }

  test("resultTallyFor counts rated decided games from either seat, and is Empty for a stranger (#103)"):
    withContainers { pg =>
      store(pg).use { db =>
        val a = Principal.Bot("lb-tally", "a")
        val b = Principal.Bot("lb-tally", "b")
        for
          idA <- GameId.random
          _   <- db.save(idA, endedResultFixture(a, b, rated = true)) // a wins as White
          idB <- GameId.random
          _   <- db.save(idB, endedResultFixture(b, a, rated = true, result = GameResult.Win(Side.Black))) // a as Black
          idC <- GameId.random
          _        <- db.save(idC, endedResultFixture(a, b, rated = false)) // casual: excluded
          tallyA   <- db.resultTallyFor(a.externalId)
          tallyB   <- db.resultTallyFor(b.externalId)
          stranger <- db.resultTallyFor("bot:team:lb-tally:nobody")
        yield
          assertEquals(tallyA, ResultTally(wins = 2, draws = 0, losses = 0))
          assertEquals(tallyA.games, 2)
          assertEquals(tallyB, ResultTally(wins = 0, draws = 0, losses = 2))
          assertEquals(stranger, ResultTally.Empty)
      }
    }

  test("a live game — its fixed roll included — survives a crash and plays on with the same commitment"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          // Life before the crash: create a game, seed both seats, and see the opening roll land.
          registry1 <- GameRegistry.create(store = db)
          created   <- registry1.create(Principal.Guest("w-uuid"), Principal.Guest("b-uuid"))
          (id, room1) = created.toOption.getOrElse(fail("game creation failed"))
          _ <- room1.submit(Seat.White, GameCommand.SubmitSeed("white-client-seed-0001"))
          _ <- room1.submit(Seat.Black, GameCommand.SubmitSeed("black-client-seed-0001"))
          // Poll the public state instead of subscribing: a slow subscriber can miss the live roll event.
          _ <- room1.snapshot
            .flatTap(ps => IO.sleep(20.millis).unlessA(ps.dicePending))
            .iterateUntil(_.dicePending)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no opening roll")))
          before  <- room1.snapshot
          commit1 <- room1.diceCommit
          tokens1 = room1.joinTokens

          // The "crash": a brand-new registry over the same store, as a fresh process would build on boot.
          registry2 <- GameRegistry.create(store = db)
          resumed   <- registry2.resume
          _ = assert(resumed >= 1, "at least our live game must be resumed")
          room2   <- registry2.get(id).map(_.getOrElse(fail("resumed game not found in the registry")))
          after   <- room2.snapshot
          commit2 <- room2.diceCommit

          // The game still ends properly: the resumed room accepts commands and reveals the SAME committed seed.
          // Deterministic handshake: the subscriber's first pulled event (the initial Snapshot) proves registration,
          // so the resign can't race the subscription and the terminal event can't be missed.
          ready <- Deferred[IO, Unit]
          ended = room2.subscribe
            .evalTap(_ => ready.complete(()).void)
            .collectFirst { case e: GameEvent.GameEnded => e }
            .compile
            .lastOrError
          resign = ready.get *> room2.submit(Seat.White, GameCommand.Resign)
          terminal <- (ended, resign)
            .parMapN((e, _) => e)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("no end")))
        yield
          assertEquals(after.dfen, before.dfen, "the pending roll (DFEN dice pool) must survive the crash")
          assertEquals(commit2, commit1, "the dice commitment must survive the crash")
          assertEquals(room2.joinTokens, tokens1, "seat tokens must survive so players can reconnect")
          assertEquals(
            sha256Hex(terminal.seed.getOrElse(fail("expected a revealed seed"))),
            commit1,
            "the revealed seed still opens the pre-crash commitment"
          )
          assertEquals(
            terminal.clientSeeds,
            Some(ClientSeeds("white-client-seed-0001", "black-client-seed-0001")),
            "the submitted client seeds survive the crash into the reveal"
          )
      }
    }

  test("a mirrored pair's reveal-withholding survives a crash: resume correctly rebuilds the partner check (#116)"):
    withContainers { pg =>
      store(pg).use { db =>
        for
          // Life before the crash: a CRN mirrored pair, both games live.
          registry1 <- GameRegistry.create(store = db)
          paired    <- registry1.createMirroredPair(
            Principal.Bot("acme", "alice"),
            Principal.Bot("acme", "bob"),
            TimeControl.Unlimited
          )
          pair = paired.toOption.getOrElse(fail("createMirroredPair failed"))

          // The "crash": a brand-new registry over the same store — the in-memory partnerEnded closures from before
          // the restart are gone; resume must rebuild them from the persisted partnerGameId (#115).
          registry2 <- GameRegistry.create(store = db)
          resumed   <- registry2.resume
          _ = assert(resumed >= 2, s"both mirrored games must be resumed, got $resumed")
          roomA <- registry2.get(pair.gameAWhite).map(_.getOrElse(fail("resumed game A not found")))
          roomB <- registry2.get(pair.gameBWhite).map(_.getOrElse(fail("resumed game B not found")))

          // End the resumed game A only; its rebuilt partnerEnded check must still correctly see B as active.
          _      <- roomA.submit(Seat.White, GameCommand.Resign)
          _      <- roomA.result.timeoutTo(5.seconds, IO.raiseError(RuntimeException("game A never ended")))
          snapA1 <- roomA.snapshot
          _ = assertEquals(snapA1.seed, None, "a resumed paired game must still withhold its reveal while B is active")
          // A FRESH lookup through the registry, not the held `roomA` reference: this is what actually exercises
          // `register`'s own (separately threaded) partnerEnded check, not just GameRoom.restore's — the two are
          // easy to fix one and forget the other (as review on #116 caught), and a held reference can't tell the
          // difference, since it works identically whether or not the room is still in the registry's map.
          stillThere <- registry2.get(pair.gameAWhite)
          _ = assert(
            stillThere.isDefined,
            "a resumed paired game must stay registered (hence GET /games/{id}-reachable) while its partner is active"
          )

          // End B too; both now reveal, proving the rebuilt checks correctly see each other post-restart.
          _      <- roomB.submit(Seat.White, GameCommand.Resign)
          _      <- roomB.result.timeoutTo(5.seconds, IO.raiseError(RuntimeException("game B never ended")))
          snapB  <- roomB.snapshot
          snapA2 <- roomA.snapshot
        yield
          assert(snapB.seed.nonEmpty, "game B must reveal once it (the second to end) concludes")
          assert(snapA2.seed.nonEmpty, "game A must reveal on a later poll, now that B has also ended")
          assertEquals(snapA2.seed, snapB.seed, "both resumed games still share the same server seed")
      }
    }

  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
