package dicechess.play.rating

/** Pure math — no IO, no Docker. Anchors: transitive dominance ordering, the mean-zero anchor, smoothing keeping a
  * 100%-scorer finite, and bootstrap determinism under a fixed seed.
  */
class BradleyTerrySuite extends munit.FunSuite:

  private def repeat(game: BradleyTerry.Game, times: Int): Seq[BradleyTerry.Game] = Seq.fill(times)(game)

  private val dominanceGames =
    repeat(("a", "b", 1.0), 3) ++ repeat(("a", "b", 0.0), 1) ++   // a beats b 3:1
      repeat(("b", "c", 1.0), 3) ++ repeat(("b", "c", 0.0), 1) ++ // b beats c 3:1
      repeat(("a", "c", 1.0), 9) ++ repeat(("a", "c", 0.0), 1)    // a crushes c 9:1

  test("a transitive dominance corpus ranks a > b > c with mean-zero relative Elo"):
    val elo = BradleyTerry.ratings(dominanceGames)
    assert(elo("a") > elo("b") && elo("b") > elo("c"), s"expected a > b > c, got $elo")
    assertEqualsDouble(elo.values.sum, 0.0, 1e-6)

  test("a perfectly symmetric matchup lands both players on zero"):
    val elo = BradleyTerry.ratings(repeat(("x", "y", 1.0), 5) ++ repeat(("x", "y", 0.0), 5))
    assertEqualsDouble(elo("x"), 0.0, 1e-6)
    assertEqualsDouble(elo("y"), 0.0, 1e-6)

  test("draws count half a win each way"):
    val elo = BradleyTerry.ratings(repeat(("x", "y", 0.5), 10))
    assertEqualsDouble(elo("x"), elo("y"), 1e-6)

  test("the virtual draw keeps an undefeated player's rating finite"):
    val elo = BradleyTerry.ratings(repeat(("champ", "victim", 1.0), 50))
    assert(elo("champ").isFinite && elo("champ") > 0)
    assert(elo("victim").isFinite && elo("victim") < 0)

  test("bootstrap is deterministic for a fixed seed and orders CIs sanely"):
    val groups = dominanceGames.grouped(2).toSeq
    val first  = BradleyTerry.rankedWithBootstrap(groups, iterations = 200, seed = 7L)
    val second = BradleyTerry.rankedWithBootstrap(groups, iterations = 200, seed = 7L)
    assertEquals(first, second, "same seed must reproduce the identical report")
    assertEquals(first.map(_.player), List("a", "b", "c"))
    first.foreach { row =>
      assert(row.ciLow <= row.ciHigh, s"$row")
    }
    assert(first.last.losVsNext.isEmpty, "the last row has no neighbour")

  test("LOS against the next rank is high when the gap is decisive"):
    val lopsided = repeat(("a", "b", 1.0), 40) ++ repeat(("a", "b", 0.0), 2)
    val ranked   = BradleyTerry.rankedWithBootstrap(lopsided.grouped(2).toSeq, iterations = 300, seed = 11L)
    assertEquals(ranked.head.player, "a")
    assert(ranked.head.losVsNext.exists(_ > 0.95), s"expected decisive LOS, got ${ranked.head.losVsNext}")

  test("empty and single-player inputs degrade gracefully"):
    assertEquals(BradleyTerry.ratings(Nil), Map.empty[String, Double])
    assertEquals(BradleyTerry.rankedWithBootstrap(Nil, iterations = 10, seed = 1L), Nil)
