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

  /** A deliberately awkward corpus for the golden vector below: four players, uneven group sizes, and `dave` present in
    * exactly ONE group — so a good share of resamples omit him entirely and exercise the "a bootstrap sample ranks only
    * the players it actually contains" path, which a corpus where everyone appears everywhere would never reach.
    */
  private val goldenGroups: Seq[Seq[BradleyTerry.Game]] = Seq(
    Seq(("alice", "bob", 1.0), ("bob", "alice", 0.0)),
    Seq(("alice", "carol", 0.5)),
    Seq(("bob", "carol", 1.0), ("carol", "bob", 0.5)),
    Seq(("alice", "bob", 0.0)),
    Seq(("carol", "dave", 1.0), ("dave", "carol", 0.0)),
    Seq(("alice", "carol", 1.0))
  )

  test("the bootstrap is a fixed function of (corpus, iterations, seed) — golden vector"):
    // Locked to the exact doubles the implementation produced when this was written. The point is not that these
    // numbers are "right" — the surrounding tests cover the statistics — but that OPTIMISING the fit must not move
    // them: the report is reproducible by seed, so any change in output is a change in the published ranking.
    val expected = List(
      ("alice", 134.39508571506718, -71.59493280482212, 332.4184545365102, Some(0.5979381443298969)),
      ("bob", 61.064746775853656, -133.19303125947457, 223.98402138106349, Some(0.7448979591836735)),
      ("carol", 3.438752686306725, -144.32867782314693, 160.77211715151589, Some(1.0)),
      ("dave", -198.89858517722757, -323.90795132933715, -155.79970059805692, None)
    )
    val actual = BradleyTerry
      .rankedWithBootstrap(goldenGroups, iterations = 100, seed = 2026L)
      .map(r => (r.player, r.elo, r.ciLow, r.ciHigh, r.losVsNext))
    assertEquals(actual, expected)

  test("empty and single-player inputs degrade gracefully"):
    assertEquals(BradleyTerry.ratings(Nil), Map.empty[String, Double])
    assertEquals(BradleyTerry.rankedWithBootstrap(Nil, iterations = 10, seed = 1L), Nil)
