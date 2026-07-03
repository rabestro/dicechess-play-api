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
    roundtrip[GameCommand](GameCommand.SubmitSeed("a1b2c3d4e5f60718"))
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
        Some(Clocks(300000, 297000)),
        commit = "00ff",
        seed = None,       // active game: seed not yet revealed
        clientSeeds = None // ditto for the client seeds
      )
    roundtrip[GameEvent](GameEvent.Snapshot(3L, ps))
    // An ended snapshot reveals the seeds so a late (re)joiner can still open the commitment.
    val endedPs = PublicGameState(
      9L,
      "fen",
      Seat.White,
      dicePending = false,
      GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.KingCaptured)),
      TimeControl.Unlimited,
      clocks = None,
      commit = "00ff",
      seed = Some("ab12"),
      clientSeeds = Some(ClientSeeds("w-seed", "b-seed"))
    )
    roundtrip[GameEvent](GameEvent.Snapshot(9L, endedPs))
    val tree = MoveTree(Map("e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty)), "a2a3" -> MoveTree.empty))
    roundtrip[GameEvent](
      GameEvent.DiceRolled(1L, Seat.White, List(1, 2, 6), "dfen", Some(Clocks(180000, 175000)), Some(tree))
    )
    roundtrip[GameEvent](GameEvent.DiceRolled(5L, Seat.Black, List(4), "dfen2", None, None))
    roundtrip[GameEvent](GameEvent.DiceRolled(6L, Seat.Black, List(1, 1, 1), "dfen3", None, Some(MoveTree.empty)))
    roundtrip[GameEvent](
      GameEvent.GameEnded(
        9L,
        GameOver(GameResult.Win(Side.Black), Termination.KingCaptured),
        "ab12",
        ClientSeeds("w", "b")
      )
    )
    roundtrip[GameEvent](
      GameEvent.GameEnded(7L, GameOver(GameResult.Draw, Termination.Aborted), "cd34", ClientSeeds("w", "b"))
    )
    roundtrip[GameEvent](
      GameEvent.GameEnded(8L, GameOver(GameResult.Win(Side.White), Termination.Timeout), "ef56", ClientSeeds("w", "b"))
    )
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
    assertEquals(
      decode[GameCommand]("""{"SubmitSeed":{"seed":"deadbeefdeadbeef"}}"""),
      Right(GameCommand.SubmitSeed("deadbeefdeadbeef"))
    )

  test("wire format the server emits (encode)"):
    assertEquals((GameCommand.Resign: GameCommand).asJson.noSpaces, """{"Resign":{}}""")
    assertEquals(
      (GameEvent
        .DiceRolled(
          1L,
          Seat.White,
          List(2, 3, 6),
          "fen",
          Some(Clocks(180000, 175000)),
          Some(MoveTree(Map("e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty)), "a2a3" -> MoveTree.empty)))
        ): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":{"white":180000,"black":175000},"legalMoves":{"a2a3":{},"e2e4":{"g1f3":{}}}}}"""
    )
    // Unlimited games carry no clocks: the field is present and null (Circe's default for None). A null legalMoves
    // means the enumeration was over the inline cap — the full tree is at GET /games/{id}/moves.
    assertEquals(
      (GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", None, None): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":null,"legalMoves":null}}"""
    )
    // The empty tree is a forced pass the server plays itself — distinct from null (elided by the cap).
    assertEquals(
      (GameEvent
        .DiceRolled(2L, Seat.Black, List(6, 6, 6), "fen", None, Some(MoveTree.empty)): GameEvent).asJson.noSpaces,
      """{"DiceRolled":{"v":2,"seat":"Black","dice":[6,6,6],"dfen":"fen","clocks":null,"legalMoves":{}}}"""
    )
    // A bot that only knows the pre-legalMoves protocol still decodes today's events (the field is additive), and a
    // recorded pre-upgrade event still decodes today (absent key -> None).
    assertEquals(
      decode[GameEvent]("""{"DiceRolled":{"v":1,"seat":"White","dice":[2,3,6],"dfen":"fen","clocks":null}}"""),
      Right(GameEvent.DiceRolled(1L, Seat.White, List(2, 3, 6), "fen", None, None))
    )
    // GameEnded reveals the server seed plus the two client seeds, so the whole roll transcript is verifiable.
    assertEquals(
      (GameEvent.GameEnded(
        3L,
        GameOver(GameResult.Win(Side.White), Termination.KingCaptured),
        "ab12",
        ClientSeeds("w", "b")
      ): GameEvent).asJson.noSpaces,
      """{"GameEnded":{"v":3,"over":{"result":{"Win":{"side":"White"}},"termination":"KingCaptured"},"seed":"ab12","clientSeeds":{"white":"w","black":"b"}}}"""
    )
    // A terminal Snapshot is a public surface too: pin its exact shape so a rename/omission of commit/seed/clientSeeds
    // (the dice-fairness trio revealed at game end) breaks the suite rather than silently reshaping the protocol.
    val terminal = PublicGameState(
      9L,
      "fen",
      Seat.White,
      dicePending = false,
      GameStatus.Ended(GameOver(GameResult.Win(Side.White), Termination.KingCaptured)),
      TimeControl.Unlimited,
      clocks = None,
      commit = "c0ffee",
      seed = Some("ab12"),
      clientSeeds = Some(ClientSeeds("w", "b"))
    )
    assertEquals(
      (GameEvent.Snapshot(9L, terminal): GameEvent).asJson.noSpaces,
      """{"Snapshot":{"v":9,"state":{"version":9,"dfen":"fen","activeSeat":"White","dicePending":false,"status":{"Ended":{"over":{"result":{"Win":{"side":"White"}},"termination":"KingCaptured"}}},"timeControl":{"Unlimited":{}},"clocks":null,"commit":"c0ffee","seed":"ab12","clientSeeds":{"white":"w","black":"b"},"legalMoves":null,"players":null}}}"""
    )

  test("Seek and Players pin their wire shapes (who a lobby row / board is looking at)"):
    val botSeek = Seek("seek-7", TimeControl.Unlimited, PlayerKind.Bot, Some("house greedy"))
    roundtrip[Seek](botSeek)
    roundtrip[Seek](Seek("seek-8", TimeControl.PerMove(10), PlayerKind.Human, None))
    assertEquals(
      botSeek.asJson.noSpaces,
      """{"id":"seek-7","timeControl":{"Unlimited":{}},"kind":"Bot","name":"house greedy"}"""
    )
    val players = Players(PublicPlayer(PlayerKind.Bot, Some("house greedy")), PublicPlayer(PlayerKind.Human, None))
    roundtrip[Players](players)
    assertEquals(
      players.asJson.noSpaces,
      """{"white":{"kind":"Bot","name":"house greedy"},"black":{"kind":"Human","name":null}}"""
    )
    // A pre-upgrade recorded state (no players key) still decodes — the field is additive.
    assertEquals(
      decode[PublicGameState](
        """{"version":1,"dfen":"fen","activeSeat":"White","dicePending":false,"status":{"Active":{}},"timeControl":{"Unlimited":{}},"clocks":null,"commit":"c0","seed":null,"clientSeeds":null}"""
      ).map(_.players),
      Right(None)
    )

  test("MoveTree round-trips and pins its wire shape"):
    val tree = MoveTree(
      Map(
        "e2e4" -> MoveTree(Map("g1f3" -> MoveTree.empty, "b1c3" -> MoveTree.empty)),
        "d2d4" -> MoveTree.empty
      )
    )
    roundtrip[MoveTree](tree)
    roundtrip[MoveTree](MoveTree.empty)
    // A node is the plain object of its children (keys sorted for a stable wire); a childless node is a complete turn.
    assertEquals(tree.asJson.noSpaces, """{"d2d4":{},"e2e4":{"b1c3":{},"g1f3":{}}}""")

  test("GameMoves pins its wire shape"):
    val body = GameMoves(4L, "fen NBK", dicePending = true, MoveTree(Map("e2e4" -> MoveTree.empty)))
    roundtrip[GameMoves](body)
    assertEquals(
      body.asJson.noSpaces,
      """{"version":4,"dfen":"fen NBK","dicePending":true,"legalMoves":{"e2e4":{}}}"""
    )
