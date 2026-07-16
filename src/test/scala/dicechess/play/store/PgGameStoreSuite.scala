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
