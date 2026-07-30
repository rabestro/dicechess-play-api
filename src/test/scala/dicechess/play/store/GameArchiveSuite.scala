package dicechess.play.store

import dicechess.play.core.*
import dicechess.play.game.EngineOps

/** The archive payload builder: field round-trip, the same do-not-archive rules as `PlaysiteIngest`, and the
  * fairness-block fallback for a seat that never submitted a client seed.
  */
class GameArchiveSuite extends munit.FunSuite:

  private def snapshot(
      status: GameStatus,
      clientSeeds: Map[Seat, String] = Map(Seat.White -> "white-seed", Seat.Black -> "black-seed"),
      rated: Option[Boolean] = Some(true),
      pairingId: Option[String] = Some("11111111-1111-1111-1111-111111111111"),
      partnerGameId: Option[String] = Some("22222222-2222-2222-2222-222222222222")
  ): GameSnapshot =
    GameSnapshot(
      version = 9L,
      dfen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
      players = Map(Seat.White -> Principal.Guest("w-uuid"), Seat.Black -> Principal.Bot("house", "greedy")),
      seatTokens = Map(Seat.White -> "tok-w", Seat.Black -> "tok-b"),
      serverSeed = "ab12cd34",
      clientSeeds = clientSeeds,
      started = true,
      ply = 2L,
      pending = false,
      status = status,
      timeControl = TimeControl.Fischer(300, 3),
      remainingMs = Map.empty,
      lastRoll = List(2, 3, 6),
      turns = Vector(
        TurnRecord(1L, "w", List(1, 1, 4), List("e2e4"), "fen-1"),
        TurnRecord(2L, "b", List(2, 3, 6), Nil, "fen-2") // a forced pass: dice rolled, no legal move
      ),
      createdAtEpochMs = Some(1_782_000_000_000L),
      rated = rated,
      pairingId = pairingId,
      partnerGameId = partnerGameId
    )

  private def ended(result: GameResult, termination: Termination) = GameStatus.Ended(GameOver(result, termination))

  test("a finished game's payload round-trips every field"):
    val json   = GameArchive.payload(snapshot(ended(GameResult.Win(Side.White), Termination.KingCaptured)))
    val fields = json.getOrElse(fail("a finished game must produce a payload"))
    val c      = fields.hcursor
    assertEquals(c.get[Boolean]("rated").toOption, Some(true))
    assertEquals(c.get[String]("pairing_id").toOption, Some("11111111-1111-1111-1111-111111111111"))
    assertEquals(c.get[String]("partner_game_id").toOption, Some("22222222-2222-2222-2222-222222222222"))
    assertEquals(c.get[Int]("result").toOption, Some(1))
    assertEquals(c.get[String]("termination").toOption, Some("king_captured"))
    assertEquals(c.downField("players").get[String]("white").toOption, Some("guest:w-uuid"))
    assertEquals(c.downField("players").get[String]("black").toOption, Some("bot:team:house:greedy"))
    assertEquals(c.get[String]("initial_dfen").toOption, Some(EngineOps.InitialDfen))
    assertEquals(c.downField("time_control").downField("Fischer").get[Int]("initialSeconds").toOption, Some(300))
    val turns = c.downField("turns")
    assertEquals(turns.downN(0).get[List[Int]]("dice").toOption, Some(List(1, 1, 4)))
    assertEquals(turns.downN(0).get[List[String]]("moves").toOption, Some(List("e2e4")))
    assertEquals(turns.downN(1).get[List[String]]("moves").toOption, Some(Nil)) // the pass
    assertEquals(turns.downN(1).get[String]("active_color").toOption, Some("b"))
    val fairness = c.downField("fairness")
    assertEquals(fairness.get[String]("server_seed").toOption, Some("ab12cd34"))
    assert(fairness.get[String]("commit").toOption.exists(_.nonEmpty), "commit must be computed from the server seed")
    assertEquals(fairness.downField("client_seeds").get[String]("white").toOption, Some("white-seed"))
    assertEquals(fairness.downField("client_seeds").get[String]("black").toOption, Some("black-seed"))

  test("a seat that never submitted a client seed falls back to its own external id (matches actual dice usage)"):
    val json =
      GameArchive.payload(snapshot(ended(GameResult.Draw, Termination.Draw), clientSeeds = Map(Seat.White -> "w-only")))
    val c = json.getOrElse(fail("a finished game must produce a payload")).hcursor.downField("fairness")
    assertEquals(c.downField("client_seeds").get[String]("white").toOption, Some("w-only"))
    assertEquals(c.downField("client_seeds").get[String]("black").toOption, Some("bot:team:house:greedy"))

  test("an active game is never archived"):
    assertEquals(GameArchive.payload(snapshot(GameStatus.Active)), None)

  test("an aborted game is never archived (mirrors PlaysiteIngest — no sporting result)"):
    assertEquals(GameArchive.payload(snapshot(ended(GameResult.Draw, Termination.Aborted))), None)

  test("a malformed snapshot missing a seat produces no archive row (mirrors PgGameStore.finishedGameOf)"):
    val malformed = snapshot(ended(GameResult.Win(Side.White), Termination.KingCaptured))
      .copy(players = Map(Seat.White -> Principal.Guest("w-uuid"))) // Black seat missing
    assertEquals(GameArchive.payload(malformed), None)

  test("an unrated, unpaired game omits rated/pairing/partner correctly"):
    val json = GameArchive.payload(
      snapshot(
        ended(GameResult.Win(Side.Black), Termination.Resign),
        rated = None,
        pairingId = None,
        partnerGameId = None
      )
    )
    val c = json.getOrElse(fail("a finished game must produce a payload")).hcursor
    assertEquals(c.get[Boolean]("rated").toOption, Some(false)) // None resolves to false, same as game_results
    assert(c.downField("pairing_id").focus.exists(_.isNull))
    assert(c.downField("partner_game_id").focus.exists(_.isNull))
