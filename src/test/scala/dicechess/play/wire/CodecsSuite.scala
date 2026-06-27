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
    roundtrip[GameEvent](GameEvent.GameEnded(7L, GameOver(GameResult.Draw, Termination.Aborted)))
    roundtrip[GameEvent](GameEvent.GameEnded(8L, GameOver(GameResult.Win(Side.White), Termination.Timeout)))
    roundtrip[GameEvent](GameEvent.Rejected(2L, Seat.Black, "nope"))

  test("Principal round-trips"):
    roundtrip[Principal](Principal.Guest("g1"))
    roundtrip[Principal](Principal.User("u1"))
    roundtrip[Principal](Principal.Bot("acme", "v3"))

  // Pin the exact on-the-wire shape: the browser/bot client depends on it, so a future
  // codec change must break these, not silently reshape the protocol.

  test("wire format the server accepts (decode)"):
    assertEquals(decode[GameCommand]("""{"Resign":{}}"""), Right(GameCommand.Resign))
    assertEquals(
      decode[GameCommand]("""{"SubmitTurn":{"moves":["e2e4","g1f3"]}}"""),
      Right(GameCommand.SubmitTurn(List("e2e4", "g1f3")))
    )

  test("wire format the server emits (encode)"):
    assertEquals((GameCommand.Resign: GameCommand).asJson.noSpaces, """{"Resign":{}}""")
    assertEquals(
      (GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen"): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen"}}"""
    )
