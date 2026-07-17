package dicechess.play.rating

/** Bradley-Terry pool ranking (E.1, #120) — the Ordo-style batch complement to the pairwise SPRT: one relative-Elo
  * number per bot, fitted jointly over every rated game, with bootstrap confidence intervals and LOS between
  * neighbours. Pure math: no IO; randomness only through an explicit seed.
  *
  * Fitting is the classic minorization-maximization iteration over strengths `p_i` (draws count half a win each way). A
  * small '''virtual draw''' is added between every pair of present players ("smoothing"): it keeps a 100%-scorer's
  * strength finite and the comparison graph connected, at the cost of a slight pull toward the mean — acceptable for a
  * report whose CIs dwarf the pull at these corpus sizes.
  *
  * Bootstrap resamples '''pairing groups''' (a CRN mirror pair = one unit, an unpaired game = its own unit) rather than
  * individual games — resampling the two halves of a pair independently would pretend the shared-dice correlation does
  * not exist and understate the intervals.
  */
object BradleyTerry:

  /** One game: `(playerA, playerB, scoreA)` with scoreA ∈ {0, 0.5, 1}. */
  type Game = (String, String, Double)

  /** One bot's row of the pool ranking. `elo` is RELATIVE (the pool's mean is 0 by construction — not comparable to the
    * Glicko-2 board's 1500-centred numbers); `losVsNext` is the likelihood of superiority over the next-ranked bot,
    * absent for the last row.
    */
  final case class Ranked(player: String, elo: Double, ciLow: Double, ciHigh: Double, losVsNext: Option[Double])

  private val Smoothing     = 0.5
  private val MaxIterations = 1000
  private val Tolerance     = 1e-10

  /** Relative Elo per player (mean 0), fitted by MM over the given games. Empty input → empty map. */
  def ratings(games: Seq[Game]): Map[String, Double] =
    val players = games.flatMap((a, b, _) => List(a, b)).distinct.sorted
    if players.sizeIs < 2 then return players.map(_ -> 0.0).toMap

    // points(i)(j) = points i scored against j; the virtual draw spreads Smoothing/2 each way per pair.
    val index  = players.zipWithIndex.toMap
    val n      = players.size
    val points = Array.fill(n, n)(0.0)
    for i <- 0 until n; j <- 0 until n if i != j do points(i)(j) = Smoothing / 2.0
    for (a, b, scoreA) <- games do
      val i = index(a)
      val j = index(b)
      points(i)(j) += scoreA
      points(j)(i) += 1.0 - scoreA

    val gamesBetween = Array.tabulate(n, n)((i, j) => points(i)(j) + points(j)(i))
    val totalPoints  = Array.tabulate(n)(i => points(i).sum)

    var strengths = Array.fill(n)(1.0)
    var iteration = 0
    var moved     = Double.MaxValue
    while iteration < MaxIterations && moved > Tolerance do
      val next = Array.tabulate(n) { i =>
        val denominator = (0 until n).filter(_ != i).map(j => gamesBetween(i)(j) / (strengths(i) + strengths(j))).sum
        if denominator == 0.0 then strengths(i) else totalPoints(i) / denominator
      }
      // Renormalise to geometric mean 1 so the iteration can't drift off to infinity as a family.
      val logMean = next.map(math.log).sum / n
      val scaled  = next.map(_ / math.exp(logMean))
      moved = scaled.zip(strengths).map((a, b) => math.abs(a - b)).max
      strengths = scaled
      iteration += 1

    val elos = strengths.map(p => 400.0 * math.log10(p))
    val mean = elos.sum / n
    players.zip(elos.map(_ - mean)).toMap

  /** The ranked pool with bootstrap 95% CIs and neighbour LOS. `groups` are the resampling units (see the object doc);
    * `seed` makes the whole report reproducible.
    */
  def rankedWithBootstrap(
      groups: Seq[Seq[Game]],
      iterations: Int = 1000,
      seed: Long = 42L
  ): List[Ranked] =
    val base = ratings(groups.flatten)
    if base.isEmpty then return Nil
    val order = base.toList.sortBy(-_._2).map(_._1)

    val rng     = new scala.util.Random(seed)
    val samples = Vector.fill(iterations) {
      val resampled = Seq.fill(groups.size)(groups(rng.nextInt(groups.size)))
      ratings(resampled.flatten)
    }

    def percentile(sorted: Vector[Double], p: Double): Double =
      if sorted.isEmpty then 0.0
      else sorted(math.min(sorted.size - 1, math.max(0, math.round(p * (sorted.size - 1)).toInt)))

    order.zipWithIndex.map { (player, rank) =>
      val values = samples.flatMap(_.get(player)).sorted
      val los    = order.lift(rank + 1).map { next =>
        val both = samples.flatMap(s => s.get(player).zip(s.get(next)))
        if both.isEmpty then 0.5 else both.count(_ > _).toDouble / both.size
      }
      Ranked(player, base(player), percentile(values, 0.025), percentile(values, 0.975), los)
    }
