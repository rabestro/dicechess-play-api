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
          assertEquals(sha256Hex(terminal.seed), commit1, "the revealed seed still opens the pre-crash commitment")
          assertEquals(
            terminal.clientSeeds,
            ClientSeeds("white-client-seed-0001", "black-client-seed-0001"),
            "the submitted client seeds survive the crash into the reveal"
          )
      }
    }

  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
