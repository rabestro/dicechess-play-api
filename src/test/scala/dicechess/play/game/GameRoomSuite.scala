package dicechess.play.game

import cats.effect.IO
import cats.syntax.all.*
import dicechess.engine.search.BotRegistry
import dicechess.play.core.*
import dicechess.play.dice.DiceSource

import scala.concurrent.duration.*

class GameRoomSuite extends munit.CatsEffectSuite:

  private def greedy = BotRegistry.getAlgorithm("greedy").get

  test("two bots play a full game through the room to a terminal state"):
    val white = BotConnection(Principal.Guest("white"), Seat.White, greedy)
    val black = BotConnection(Principal.Bot("acme", "greedy"), Seat.Black, greedy)
    val dice  = DiceSource.commitReveal("server-seed-fixture".getBytes("UTF-8"), "white", "black")

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
