# Ladder Strength Methodology

How the bot ladder measures "which algorithm is stronger", and what each number means. Two
complementary instruments run over the same `game_results` corpus:

| Instrument | Question | Where |
| --- | --- | --- |
| **Glicko-2** (continuous) | "roughly how strong is each bot right now?" | rating batch → `bots.glicko_*` → public `/leaderboard` |
| **SPRT + Bradley-Terry** (batch report) | "is B stronger than A, with controlled error rates?" | `mise run ladder:report` (owner-facing, read-only) |

## Common random numbers (CRN)

The scheduler starts ladder games as **mirrored pairs**: two games between the same two bots,
colours swapped, sharing one dice sequence (`pairing_id` ties them together). Whatever luck the
dice hand White in game 1, they hand the *other* bot in game 2 — so over a pair, dice luck and
colour advantage cancel, and what remains is skill difference. This is the Fishtest/Stockfish
testing methodology adapted to dice chess, where the dice make single games far noisier than
regular chess.

## Pairwise SPRT (pentanomial)

For each bot matchup the report runs a Sequential Probability Ratio Test of

- **H0**: the perspective bot is stronger by **≤ elo0** (default 0),
- **H1**: stronger by **≥ elo1** (default 20),

at error rates α = β = 0.05 (both configurable: `mise run ladder:report -- 0 20 0.05 0.05`).

One **complete CRN pair is a single observation** with score ∈ {0, ¼, ½, ¾, 1} (both game scores
summed, normalised) — the *pentanomial* model. Scoring pairs as units, rather than their games
independently, keeps the correlated halves of a pair from being double-counted and captures the
variance reduction CRN creates. Unpaired rated games (rare — a scheduler pair whose partner was
never finished) fall back to ordinary per-game *trinomial* observations; the two families'
log-likelihood contributions add.

The LLR uses the GSPRT normal approximation (Van den Bergh, as deployed by Fishtest): for a
sample with mean `m` and variance `v` of the per-observation score and hypothesis means
`s0 = score(elo0)`, `s1 = score(elo1)` (logistic `score(e) = 1/(1+10^(-e/400))`):

```
LLR ≈ N · (s1 − s0) · (2m − s0 − s1) / (2v)
```

with decision bounds `ln(β/(1−α))` and `ln((1−β)/α)`. `ACCEPT H1` = the perspective bot is
stronger (at the requested confidence); `ACCEPT H0` = it is not; `CONTINUE` = keep playing.

## Pool ranking (Bradley-Terry + bootstrap)

A single relative-Elo number per bot, fitted jointly over all rated decided games by
minorization-maximization (draws = half a win each way). A small **virtual draw** between every
pair of present players keeps undefeated bots finite and the comparison graph connected; the
resulting pull toward the mean is negligible next to the intervals at current corpus sizes.
Ratings are **relative** (pool mean = 0) — not comparable to the Glicko board's 1500-centred
numbers.

Uncertainty comes from a seeded **bootstrap over pairing groups**: a CRN pair is resampled as one
unit (resampling its halves independently would pretend the shared-dice correlation away). The
report prints the 95% percentile interval per bot and the **LOS** (likelihood of superiority)
against the next-ranked bot — the fraction of bootstrap worlds in which the order holds.

## Reading the report

- `CONTINUE` is not a failure: it means the corpus hasn't accumulated enough evidence for the
  requested elo gap and error rates — let the scheduler play more pairs.
- Overlapping CIs with LOS ≈ 50% mean the bots are indistinguishable so far.
- The report is read-only and deterministic for a given corpus (fixed bootstrap seed), so two
  runs on the same data render identically.
