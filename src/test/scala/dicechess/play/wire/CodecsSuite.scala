package dicechess.play.wire

import dicechess.play.core.*
import dicechess.play.wire.Codecs.given
import io.circe.parser.decode
import io.circe.syntax.*

class CodecsSuite extends munit.FunSuite:

  private def roundtrip[A: io.circe.Codec](value: A): Unit =
    assertEquals(decode[A](value.asJson.noSpaces), Right(value))

  test("GameCommand round-trips"):
    roundtrip[GameCommand](GameCommand.SubmitTurn(List("e2e4", "g1f3")))
    roundtrip[GameCommand](GameCommand.Resign)

  test("GameEvent round-trips"):
    val ps = PublicGameState(3L, "fen", Seat.White, dicePending = true, GameStatus.Active)
    roundtrip[GameEvent](GameEvent.Snapshot(3L, ps))
    roundtrip[GameEvent](GameEvent.DiceRolled(1L, Seat.White, List(1, 2, 6), "dfen"))
    roundtrip[GameEvent](
      GameEvent.GameEnded(9L, GameOver(GameResult.Win(Side.Black), Termination.KingCaptured))
    )
    roundtrip[GameEvent](GameEvent.Rejected(2L, Seat.Black, "nope"))

  test("Principal round-trips"):
    roundtrip[Principal](Principal.Guest("g1"))
    roundtrip[Principal](Principal.User("u1"))
    roundtrip[Principal](Principal.Bot("acme", "v3"))
