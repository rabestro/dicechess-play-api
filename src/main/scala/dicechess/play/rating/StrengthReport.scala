package dicechess.play.rating

import dicechess.play.core.Principal
import dicechess.play.store.GameResultRow

/** The E.1 (#120) strength report over `game_results`: pairwise SPRT verdicts on CRN pairs plus a Bradley-Terry pool
  * ranking — assembled purely from rows, so the whole pipeline is unit-testable without a database. The thin
  * `LadderReportMain` runner just loads rows and prints [[StrengthReport.render]].
  */
final case class StrengthReport(
    pairwise: List[StrengthReport.Pairwise],
    ranking: List[BradleyTerry.Ranked],
    completePairs: Int,
    singles: Int,
    excludedRows: Int
)

object StrengthReport:

  /** SPRT of one unordered bot matchup, from `perspective`'s side (the lexicographically smaller external id — fixed,
    * so re-runs render identically).
    */
  final case class Pairwise(
      perspective: String,
      opponent: String,
      pairs: Sprt.Pentanomial,
      singles: Sprt.Trinomial,
      result: Sprt.Result
  )

  final case class Config(
      elo0: Double = 0.0,
      elo1: Double = 20.0,
      alpha: Double = 0.05,
      beta: Double = 0.05,
      bootstrapIterations: Int = 1000,
      seed: Long = 42L
  )

  /** A usable observation: both seats are registered-bot ids and the game is decided. */
  final private case class BotGame(white: Principal.Bot, black: Principal.Bot, whiteScore: Double)

  def build(rows: List[GameResultRow], config: Config = Config()): StrengthReport =
    val (usable, excluded) = rows.partitionMap { row =>
      (
        Principal.fromBotExternalId(row.whiteExternalId),
        Principal.fromBotExternalId(row.blackExternalId),
        row.result
      ) match
        case (Some(white), Some(black), Some(result)) if white != black =>
          val whiteScore = result match
            case 1  => 1.0
            case -1 => 0.0
            case _  => 0.5
          Left(row.pairingId -> BotGame(white, black, whiteScore))
        case _ => Right(())
    }

    // CRN groups: a pairing id shared by exactly two usable games of the same unordered matchup is a complete pair
    // (ONE pentanomial observation); everything else degrades to per-game trinomial singles.
    val (paired, unpaired)            = usable.partition(_._1.isDefined)
    val groups                        = paired.groupBy(_._1).values.map(_.map(_._2)).toList
    val (completePairs, brokenGroups) = groups.partition { games =>
      games.sizeIs == 2 && games.map(g => Set(g.white, g.black)).distinct.sizeIs == 1
    }
    val singleGames = unpaired.map(_._2) ++ brokenGroups.flatten

    def key(a: Principal.Bot, b: Principal.Bot): (Principal.Bot, Principal.Bot) =
      if a.externalId <= b.externalId then (a, b) else (b, a)

    val pentaByPair = completePairs
      .groupBy(games => key(games.head.white, games.head.black))
      .map { case ((perspective, opponent), pairs) =>
        val bins = pairs.groupBy { games =>
          val score = games.map(g => if g.white == perspective then g.whiteScore else 1.0 - g.whiteScore).sum
          (score * 2).round.toInt // 0..4
        }
        def n(i: Int): Long = bins.getOrElse(i, Nil).size.toLong
        (perspective, opponent) -> Sprt.Pentanomial(n(0), n(1), n(2), n(3), n(4))
      }

    val triByPair = singleGames
      .groupBy(game => key(game.white, game.black))
      .map { case ((perspective, opponent), games) =>
        val scores = games.map(g => if g.white == perspective then g.whiteScore else 1.0 - g.whiteScore)
        (perspective, opponent) -> Sprt.Trinomial(
          losses = scores.count(_ == 0.0),
          draws = scores.count(_ == 0.5),
          wins = scores.count(_ == 1.0)
        )
      }

    val pairwise = (pentaByPair.keySet ++ triByPair.keySet).toList
      .map { matchup =>
        val (perspective, opponent) = matchup
        val penta                   = pentaByPair.getOrElse(matchup, Sprt.Pentanomial.Empty)
        val tri                     = triByPair.getOrElse(matchup, Sprt.Trinomial.Empty)
        Pairwise(
          display(perspective),
          display(opponent),
          penta,
          tri,
          Sprt.test(penta, tri, config.elo0, config.elo1, config.alpha, config.beta)
        )
      }
      .sortBy(p => (-p.result.observations, p.perspective, p.opponent))

    // Bootstrap resampling units mirror the observation structure: a complete pair travels as one unit.
    val bootstrapGroups: Seq[Seq[BradleyTerry.Game]] =
      completePairs.map(_.map(toBtGame)) ++ singleGames.map(g => List(toBtGame(g)))

    StrengthReport(
      pairwise = pairwise,
      ranking = BradleyTerry.rankedWithBootstrap(bootstrapGroups, config.bootstrapIterations, config.seed),
      completePairs = completePairs.size,
      singles = singleGames.size,
      excludedRows = excluded.size
    )

  private def toBtGame(game: BotGame): BradleyTerry.Game =
    (display(game.white), display(game.black), game.whiteScore)

  private def display(bot: Principal.Bot): String = s"${bot.team}/${bot.name}"

  /** `String.format` pinned to `Locale.ROOT`: the report must render identically everywhere (dot decimals), not follow
    * whatever locale the operator's JVM happens to boot with — `f""` interpolators use the default locale.
    */
  private def line(pattern: String, args: Any*): String =
    String.format(java.util.Locale.ROOT, pattern, args.map(_.asInstanceOf[Object])*)

  /** The owner-facing plain-text rendering (the runner prints this verbatim). */
  def render(report: StrengthReport, config: Config): String =
    val header = List(
      "=== Dice Chess ladder — strength report ===",
      line(
        "observations: %d CRN pairs + %d singles (excluded rows: %d)",
        report.completePairs,
        report.singles,
        report.excludedRows
      ),
      line(
        "SPRT hypotheses: H0 \"stronger by <= %.0f elo\" vs H1 \">= %.0f elo\", alpha=beta=%.2f",
        config.elo0,
        config.elo1,
        config.alpha
      ),
      ""
    )
    val pairLines = report.pairwise.map { p =>
      val verdict = p.result.verdict match
        case Sprt.Verdict.AcceptH1 => s"ACCEPT H1 — ${p.perspective} is stronger"
        case Sprt.Verdict.AcceptH0 => s"ACCEPT H0 — ${p.perspective} is not stronger"
        case Sprt.Verdict.Continue => "CONTINUE — need more games"
      val pen = p.pairs
      line(
        "%-18s vs %-18s pairs[%d,%d,%d,%d,%d] singles(w/d/l %d/%d/%d)  LLR %+.2f in [%.2f, %.2f]  %s",
        p.perspective,
        p.opponent,
        pen.n0,
        pen.n1,
        pen.n2,
        pen.n3,
        pen.n4,
        p.singles.wins,
        p.singles.draws,
        p.singles.losses,
        p.result.llr,
        p.result.lower,
        p.result.upper,
        verdict
      )
    }
    val rankLines = report.ranking.zipWithIndex.map { (r, i) =>
      val los = r.losVsNext.map(v => line("  LOS vs next %.1f%%", v * 100)).getOrElse("")
      line("%2d. %-18s %+7.1f  [%+7.1f, %+7.1f]%s", i + 1, r.player, r.elo, r.ciLow, r.ciHigh, los)
    }
    (header ++ List("--- Pairwise SPRT (pentanomial on CRN pairs) ---") ++ pairLines ++
      List("", "--- Pool ranking (Bradley-Terry, relative elo, bootstrap 95% CI) ---") ++ rankLines)
      .mkString("\n")
