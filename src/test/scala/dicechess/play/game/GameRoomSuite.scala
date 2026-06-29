package dicechess.play.game

import cats.effect.IO
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource

import java.security.MessageDigest
import scala.concurrent.duration.*

class GameRoomSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get

  /** SHA-256 of a hex-encoded seed, hex-encoded — to check a reveal against its commitment. */
  private def sha256Hex(hexSeed: String): String =
    val bytes = hexSeed.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  private val seedW = "white-client-seed-0001" // ≥16 chars: a valid client dice seed
  private val seedB = "black-client-seed-0001"
  private def seats =
    Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  test("the game-end event reveals the server seed, opening the dice commitment"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Subscribe first, then resign shortly after so the terminal event is the live GameEnded (not a snapshot).
          val ended      = room.subscribe.collectFirst { case e: GameEvent.GameEnded => e }.compile.lastOrError
          val resignSoon = IO.sleep(100.millis) *> room.submit(Seat.White, GameCommand.Resign)
          (ended, resignSoon)
            .parMapN((event, _) => event)
            .flatMap(event => (room.diceCommit, room.snapshot).mapN((commit, snap) => (event, commit, snap)))
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no game-end event")))
      }
      .map: (event, commit, snap) =>
        assert(event.seed.nonEmpty, "the game-end event must reveal the seed")
        // The whole point of commit-reveal: the revealed seed hashes to the commitment published at creation.
        assertEquals(sha256Hex(event.seed), commit)
        // And the terminal snapshot carries the same reveal, so a client that joins after the end can still verify.
        assertEquals(snap.seed, Some(event.seed))

  test("two bots play a full game through the room to a terminal state"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    val dice  = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(Map(Seat.White -> white.principal, Seat.Black -> black.principal), dice)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Bot fibers scoped to the game: cancelled on success, failure, or timeout.
          val play = (white.run(room).background, black.run(room).background).tupled.use: _ =>
            room.start *> room.result
          play.timeoutTo(20.seconds, IO.raiseError(RuntimeException("game did not finish in time")))
      }
      .map: over =>
        assert(
          over.termination == Termination.KingCaptured || over.termination == Termination.Draw,
          s"unexpected termination: $over"
        )
        over.result match
          case GameResult.Win(_) => assertEquals(over.termination, Termination.KingCaptured)
          case GameResult.Draw   => assertEquals(over.termination, Termination.Draw)

  test("a stalled subscriber is dropped and never freezes the room"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    val dice  = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      // Small fan-out buffer so the stalled subscriber overflows well within one game; the two bots
      // never lag (the writer waits for the side to move), so they are unaffected.
      .create(Map(Seat.White -> white.principal, Seat.Black -> black.principal), dice, fanOutBuffer = 16)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Reads one event, then never pulls again. With the old back-pressuring fan-out this would
          // freeze the whole room; now it is dropped once its buffer fills.
          val stalled = room.subscribe.evalMap(_ => IO.never).compile.drain
          // A healthy observer must still receive the terminal event.
          val healthy = room.subscribe.collectFirst { case e: GameEvent.GameEnded => e }.compile.lastOrError

          (white.run(room).background, black.run(room).background, stalled.background).tupled
            .use(_ => room.start *> (room.result, healthy).parTupled)
            .timeoutTo(20.seconds, IO.raiseError(RuntimeException("room froze with a stalled subscriber")))
      }
      .map: (over, ended) =>
        assertEquals(ended.over, over)

  test("a writer-fiber failure aborts the game instead of bricking the room"):
    // A dice source that throws the moment the writer rolls: the consumer fiber dies before any
    // terminal. Without supervision `done` would never complete and every subscriber would hang.
    val boom = new DiceSource:
      def roll(ply: Long, clientSeedW: String, clientSeedB: String): List[Int] = throw RuntimeException("boom")
      def commit: String                                                       = "00"
      def reveal: String                                                       = "seed"

    GameRoom
      // Tiny seed grace so the opening roll (which throws) is reached promptly without anyone submitting a seed.
      .create(seats, boom, seedGrace = 50.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Await BOTH: the game's result and a subscriber's stream. If supervision were missing,
          // either would hang and the timeout would fail the test.
          val drained = room.subscribe.compile.drain
          (room.start *> (room.result, drained).parTupled)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("abort did not complete the game")))
      }
      .map: (over, _) =>
        assertEquals(over.termination, Termination.Aborted)

  test("a seat left with no connection past the grace window forfeits"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(
        Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")),
        dice,
        disconnectGrace = 200.millis
      )
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // White attaches then drops; nobody reconnects, so the seat forfeits once the grace elapses.
          room.connection(Seat.White).use_ *>
            room.result.timeoutTo(10.seconds, IO.raiseError(RuntimeException("grace forfeit did not fire")))
      }
      .map: over =>
        assertEquals(over.termination, Termination.Resign)
        assertEquals(over.result, GameResult.Win(Side.Black))

  test("reconnecting within the grace window cancels the forfeit"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(
        Map(Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black")),
        dice,
        disconnectGrace = 300.millis
      )
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Drop White (starts the grace), reconnect immediately, and hold the seat well past the window:
          // the forfeit must have been cancelled, so the game is still running (result not completed).
          room.connection(Seat.White).use_ *>
            room.connection(Seat.White).surround(room.result.map(Option(_)).timeoutTo(900.millis, IO.none))
      }
      .map: ended =>
        assertEquals(ended, None, "a reconnect within the grace must cancel the forfeit")

  test("an abandoned pending turn forfeits on the turn deadline"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))

    GameRoom
      .create(
        seats,
        dice,
        idleCheck = 200.millis,
        seedGrace = 50.millis // force-start quickly (no seeds), then the turn deadline applies
      )
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          // Nobody ever submits, so the side to move runs out the deadline and forfeits.
          (room.start *> room.result)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("turn deadline did not fire")))
      }
      .map: over =>
        assertEquals(over.termination, Termination.Timeout)
        over.result match
          case GameResult.Win(_) => ()
          case other             => fail(s"a timeout is a forfeit win, got: $other")

  test("the opening roll is withheld until both client seeds arrive, then rolls"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      // A long grace so only the seeds (never a timeout) can open the gate within the test window.
      .create(seats, dice, seedGrace = 10.seconds)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          for
            _    <- room.start
            _    <- IO.sleep(200.millis) // a roll would have happened by now if it were not gated
            held <- room.snapshot
            _ = assert(!held.dicePending, "no turn should be pending before both client seeds arrive")
            _ = assertEquals(held.status, GameStatus.Active)
            // Subscribe first, then submit both seeds shortly after, so the live DiceRolled is observed.
            firstRoll = room.subscribe.collectFirst { case r: GameEvent.DiceRolled => r }.compile.lastOrError
            seedSoon  = IO.sleep(100.millis) *>
              room.submit(Seat.White, GameCommand.SubmitSeed(seedW)) *>
              room.submit(Seat.Black, GameCommand.SubmitSeed(seedB))
            rolled <- (firstRoll, seedSoon)
              .parMapN((r, _) => r)
              .timeoutTo(5.seconds, IO.raiseError(RuntimeException("the roll never came after both seeds")))
          yield assert(rolled.v >= 1L, "the opening roll must follow the seeds")
      }

  test("a game whose clients never seed still force-starts after the grace"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 100.millis)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val rolled = room.subscribe.collectFirst { case r: GameEvent.DiceRolled => r }.compile.lastOrError
          (rolled, room.start)
            .parMapN((r, _) => r)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("the game never rolled without client seeds")))
      }
      .map(rolled => assert(rolled.v >= 1L, "a game with no client seeds must still roll (id fallback)"))

  test("the game-end event reveals the client seeds folded into the dice"):
    val dice = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"))
    GameRoom
      .create(seats, dice, seedGrace = 10.seconds)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          val ended = room.subscribe.collectFirst { case e: GameEvent.GameEnded => e }.compile.lastOrError
          // Seed both seats (before start, so the game rolls at once), play briefly, then resign to a terminal.
          val drive =
            room.submit(Seat.White, GameCommand.SubmitSeed(seedW)) *>
              room.submit(Seat.Black, GameCommand.SubmitSeed(seedB)) *>
              room.start *>
              IO.sleep(100.millis) *>
              room.submit(Seat.White, GameCommand.Resign)
          (ended, drive)
            .parMapN((event, _) => event)
            .timeoutTo(10.seconds, IO.raiseError(RuntimeException("no game-end event")))
      }
      .map(event => assertEquals(event.clientSeeds, ClientSeeds(seedW, seedB)))
