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
    val ps =
      PublicGameState(
        3L,
        "fen",
        Seat.White,
        dicePending = true,
        GameStatus.Active,
        TimeControl.Fischer(300, 3),
        Some(Clocks(300000, 297000))
      )
    roundtrip[GameEvent](GameEvent.Snapshot(3L, ps))
    roundtrip[GameEvent](GameEvent.DiceRolled(1L, Seat.White, List(1, 2, 6), "dfen", Some(Clocks(180000, 175000))))
    roundtrip[GameEvent](GameEvent.DiceRolled(5L, Seat.Black, List(4), "dfen2", None))
    roundtrip[GameEvent](
      GameEvent.GameEnded(9L, GameOver(GameResult.Win(Side.Black), Termination.KingCaptured), "ab12")
    )
    roundtrip[GameEvent](GameEvent.GameEnded(7L, GameOver(GameResult.Draw, Termination.Aborted), "cd34"))
    roundtrip[GameEvent](GameEvent.GameEnded(8L, GameOver(GameResult.Win(Side.White), Termination.Timeout), "ef56"))
    roundtrip[GameEvent](GameEvent.Rejected(2L, Seat.Black, "nope"))

  test("Principal round-trips"):
    roundtrip[Principal](Principal.Guest("g1"))
    roundtrip[Principal](Principal.User("u1"))
    roundtrip[Principal](Principal.Bot("acme", "v3"))

  test("TimeControl round-trips"):
    roundtrip[TimeControl](TimeControl.Unlimited)
    roundtrip[TimeControl](TimeControl.SuddenDeath(60))
    roundtrip[TimeControl](TimeControl.Fischer(300, 3))
    roundtrip[TimeControl](TimeControl.PerMove(10))

  test("Clocks round-trips"):
    roundtrip[Clocks](Clocks(60000, 58500))

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
      (GameEvent
        .DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", Some(Clocks(180000, 175000))): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":{"white":180000,"black":175000}}}"""
    )
    // Unlimited games carry no clocks: the field is present and null (Circe's default for None).
    assertEquals(
      (GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", None): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":null}}"""
    )
