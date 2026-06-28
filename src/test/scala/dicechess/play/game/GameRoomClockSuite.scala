package dicechess.play.game

import cats.effect.IO
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource

import scala.concurrent.duration.*

/** Enforcement of the real per-side clocks (#46). Flag-fall tests use a tiny clock against the default 120s `idleCheck`:
  * completing in a second or two proves the chess clock — not the anti-abandonment cap — ended the game.
  */
class GameRoomClockSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get
  private def dice   = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"), "white", "black")
  private def seats  = Map[Seat, Principal](Seat.White -> Principal.Guest("white"), Seat.Black -> Principal.Guest("black"))

  /** Create a timed room (default `idleCheck`), run it with nobody ever submitting, and return how it ended. */
  private def flagFall(timeControl: TimeControl): IO[GameOver] =
    GameRoom
      .create(seats, dice, timeControl = timeControl)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          (room.start *> room.result)
            .timeoutTo(5.seconds, IO.raiseError(RuntimeException("clock did not flag before the 120s idleCheck")))
      }

  test("SuddenDeath: the side to move flags when its clock runs out"):
    flagFall(TimeControl.SuddenDeath(1)).map: over =>
      assertEquals(over.termination, Termination.Timeout)
      assert(over.result.isInstanceOf[GameResult.Win], s"a flag-fall is a win on time, got: ${over.result}")

  test("Fischer: an idle side still flags (the increment can't save a turn never made)"):
    flagFall(TimeControl.Fischer(1, 2)).map: over =>
      assertEquals(over.termination, Termination.Timeout)
      assert(over.result.isInstanceOf[GameResult.Win], s"a flag-fall is a win on time, got: ${over.result}")

  test("PerMove: exceeding the per-move budget flags"):
    flagFall(TimeControl.PerMove(1)).map: over =>
      assertEquals(over.termination, Termination.Timeout)
      assert(over.result.isInstanceOf[GameResult.Win], s"a flag-fall is a win on time, got: ${over.result}")

  /** Two bots that always move in time must finish normally — never on the clock. A generous control exercises the
    * debit + increment path on every completed turn without any side running out.
    */
  private def botsFinishUnderClock(timeControl: TimeControl): IO[GameOver] =
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    GameRoom
      .create(seats, dice, timeControl = timeControl)
      .flatMap {
        case Left(error) => IO.raiseError(RuntimeException(s"room creation failed: $error"))
        case Right(room) =>
          (white.run(room).background, black.run(room).background).tupled
            .use(_ => room.start *> room.result)
            .timeoutTo(30.seconds, IO.raiseError(RuntimeException("timed game with active bots did not finish")))
      }

  test("Fischer: bots that move in time finish on the board, never on the clock"):
    botsFinishUnderClock(TimeControl.Fischer(600, 2)).map: over =>
      assertNotEquals(over.termination, Termination.Timeout)

  test("SuddenDeath: bots with ample time finish on the board, never on the clock"):
    botsFinishUnderClock(TimeControl.SuddenDeath(600)).map: over =>
      assertNotEquals(over.termination, Termination.Timeout)
