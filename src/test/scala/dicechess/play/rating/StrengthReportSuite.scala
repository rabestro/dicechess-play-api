package dicechess.play.rating

import dicechess.play.core.GameId
import dicechess.play.store.GameResultRow

import java.time.Instant

/** Pure — no IO, no Docker: rows in, report out. Anchors: a complete CRN pair becomes ONE pentanomial observation
  * (never two singles), broken/unpaired games degrade to trinomial, non-bot or undecided rows are excluded but counted,
  * and the perspective of a matchup is stable across runs.
  */
class StrengthReportSuite extends munit.FunSuite:

  private val at = Instant.parse("2026-07-17T10:00:00Z")
  private val v1 = "bot:team:oracle:v1"
  private val v3 = "bot:team:oracle:v3"

  private var nextId = 0
  private def row(
      white: String,
      black: String,
      result: Option[Int],
      pairingId: Option[String]
  ): GameResultRow =
    nextId += 1
    GameResultRow(
      GameId(s"g-$nextId"),
      white,
      black,
      result,
      "resign",
      rated = true,
      "Fischer(300,3)",
      "ab",
      pairingId,
      at
    )

  /** One complete CRN pair: v3 as White wins game 1, v3 as Black gets `secondResult` in the mirror. */
  private def pair(id: String, secondResult: Int): List[GameResultRow] =
    List(row(v3, v1, Some(1), Some(id)), row(v1, v3, Some(secondResult), Some(id)))

  test("a complete CRN pair is one pentanomial observation, not two singles"):
    val report = StrengthReport.build(pair("p1", secondResult = -1)) // v3 wins both halves
    assertEquals(report.completePairs, 1)
    assertEquals(report.singles, 0)
    val matchup = report.pairwise.head
    // Perspective is the lexicographically smaller external id: bot:team:oracle:v1.
    assertEquals(matchup.perspective, "oracle/v1")
    assertEquals(matchup.opponent, "oracle/v3")
    assertEquals(matchup.pairs, Sprt.Pentanomial(1, 0, 0, 0, 0), "v1 lost both games of the pair -> bin 0")

  test("pentanomial bins land by the pair's summed score"):
    val rows = pair("a", -1) ++ // v1 loses both -> bin 0
      pair(
        "b",
        1
      ) ++ // v1 loses as Black, wins as White (2nd game result=1 means White=v1 wins) -> bin 2? no: 0 + 1 = 1 -> bin 2
      pair("c", 0) // loss + draw -> 0.5 -> bin 1
    val report  = StrengthReport.build(rows)
    val matchup = report.pairwise.head
    assertEquals(matchup.pairs.total, 3L)
    assertEquals(matchup.pairs.n0, 1L)
    assertEquals(matchup.pairs.n1, 1L, "loss + draw = 0.5 for v1")
    assertEquals(matchup.pairs.n2, 1L, "loss + win = 1.0 for v1")

  test("an incomplete pairing group and an unpaired game both degrade to trinomial singles"):
    val rows = List(
      row(v3, v1, Some(1), Some("half-pair")), // partner never finished
      row(v1, v3, Some(0), None)               // ordinary unpaired rated game
    )
    val report = StrengthReport.build(rows)
    assertEquals(report.completePairs, 0)
    assertEquals(report.singles, 2)
    assertEquals(report.pairwise.head.singles, Sprt.Trinomial(losses = 1, draws = 1, wins = 0))

  test("non-bot participants, undecided results and self-play are excluded but counted"):
    val rows = List(
      row("guest:human", v1, Some(1), None),
      row(v1, v3, None, None),
      row(v1, v1, Some(1), None)
    )
    val report = StrengthReport.build(rows)
    assertEquals(report.excludedRows, 3)
    assertEquals(report.pairwise, Nil)
    assertEquals(report.ranking, Nil)

  test("a dominant corpus yields an SPRT accept and a matching Bradley-Terry order"):
    val rows   = (1 to 30).toList.flatMap(i => pair(s"p$i", secondResult = -1)) // v3 sweeps 30 pairs
    val report = StrengthReport.build(rows, StrengthReport.Config(bootstrapIterations = 100))
    val m      = report.pairwise.head
    // Perspective v1 loses everything: H0 accepted from v1's side = v3 is the stronger one.
    assertEquals(m.result.verdict, Sprt.Verdict.AcceptH0)
    assertEquals(report.ranking.head.player, "oracle/v3")
    assert(report.ranking.head.elo > report.ranking.last.elo)

  test("render mentions the counts and each matchup once"):
    val report   = StrengthReport.build(pair("p1", -1) :+ row(v3, v1, Some(1), None))
    val rendered = StrengthReport.render(report, StrengthReport.Config())
    assert(rendered.contains("1 CRN pairs + 1 singles"), rendered.linesIterator.toList.head)
    assert(rendered.linesIterator.count(_.contains("oracle/v1")) >= 1)
    // Locale-pinned rendering (live-corpus regression: a comma-decimal JVM once printed "alpha=beta=0,05").
    assert(rendered.contains("alpha=beta=0.05"), "the report must render dot decimals regardless of JVM locale")
