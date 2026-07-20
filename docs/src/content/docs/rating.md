---
title: Rating & Ladder
description: What Glicko-2 rating, RD, and volatility actually mean, why a bot can stay "provisional" for a long time, and how mirrored pairs cancel dice luck.
---

Every on-ladder bot carries three numbers, not one: a **rating**, a **deviation** (RD), and a **volatility**. This page explains what each means, how a finished game moves them, and why your bot might be actively playing — and even winning some games — without showing up on the public [leaderboard](../reference/rest/#leaderboard).

## The three numbers

Dice Chess uses [Glicko-2](http://www.glicko.net/glicko/glicko2.pdf) (Glickman), not plain Elo. Elo gives you one number; Glicko-2 gives you three, because a rating built on 3 games and a rating built on 300 games shouldn't be trusted equally:

| Field | Meaning | Fresh bot |
| --- | --- | --- |
| `rating` | Your estimated strength, on the familiar ~0–3000 scale. | `1500.0` |
| `rd` | **Rating deviation** — how *uncertain* that estimate still is. Lower means more confident. | `350.0` |
| `volatility` | How erratic your results have been relative to what your rating predicts. Not on the public wire, but it's what keeps `rd` from converging when results are noisy. | `0.06` |

A rating without its `rd` is close to meaningless: `1500 ± 30` and `1500 ± 350` are very different claims about the same number. The `±` is the point — Glicko-2 tracks it honestly instead of pretending every rating is equally solid.

## How one game moves the numbers

The ladder treats **every finished, rated game as its own one-game rating period** for both participants. An offline batch — not the game server itself — recomputes ratings roughly once a minute from the finished-game backlog, so a result lands on your rating shortly after the game ends, not instantly.

Each update pulls the rating and shrinks (or doesn't shrink) `rd` depending on how *surprising* the result was, given both bots' ratings at the time:

- **Win as the underdog, or lose as the favourite** — the rating moves a lot, `rd` shrinks. The result was informative.
- **Win as the favourite, or lose as the underdog** — the rating barely moves. That's what everyone expected.
- **A run of results your rating didn't predict at all** (winning far more, or far less, consistently than expected) — `volatility` rises, which pushes `rd` back up on later updates. The system is telling you the estimate is *less* trustworthy than the raw game count would suggest.

That last point matters more than it sounds: **`rd` does not shrink purely from playing more games.** A bot that loses almost every game in a stable, predictable way converges quickly, because every result confirms the estimate. A bot whose results are erratic — say, a random-move bot that mostly loses but occasionally, unpredictably wins — can keep a high `rd` for a surprisingly long time, because those upsets are exactly the "my rating didn't see that coming" signal Glicko-2 reacts to. Games played is not the same as certainty gained.

There is deliberately **no idle-time RD inflation** here (the part of Glicko-2 where a rating gets less certain just from not playing): on-ladder bots are paired continuously by the server, so there's no meaningful idle time to model.

## Provisional bots and the public board

A rating is **provisional** while `rd > 110` — Glickman's own convergence threshold. A fresh bot starts provisional at `1500 ± 350` and typically settles within a few dozen games, *if its results are reasonably consistent*.

- The public [`GET /leaderboard`](../reference/rest/#leaderboard) lists **only converged** (non-provisional) bots, best first. This is a deliberate policy, not a bug — showing every wildly-uncertain fresh rating on the same board as settled ones would make the board noisy and misleading.
- Your own [`GET /bots/{team}/{name}`](../reference/rest/#bot-profile) profile shows you regardless, flagged `"provisional": true`, so joining the ladder never feels like a black hole while you wait to converge.
- `onLadder: false` means the bot left (`POST /bot/ladder/leave`) — its rating is **frozen**, not deleted, and it still appears on the leaderboard (if converged) or the profile endpoint (always) at whatever it was when it left.

If your bot has played plenty of games and is still provisional, check its win/loss pattern on its profile before assuming something is broken: a bot with a genuinely volatile result pattern (frequent upsets in either direction) converges slower than one that consistently loses — or consistently wins — no matter how many games it plays.

## Mirrored pairs: cancelling dice luck

Dice Chess has real variance the board game itself doesn't: two equally-matched bots can still draw wildly different rolls. To keep that from dominating the ladder, the scheduler doesn't play a single game per pairing — it plays a **mirrored pair**: the same dice sequence, twice, with colours swapped. See [Provably-Fair Dice → the mirror-pair exception](../provably-fair/#the-mirror-pair-exception-withheld-reveal) for the mechanism (and why the dice reveal on those two games is withheld until both conclude). For rating purposes, the effect is that a lucky or unlucky roll sequence hits both bots symmetrically instead of only one — so the win/loss split reflects play more than it reflects the dice.

## Joining and leaving

Covered in [Authentication & Identity → Joining the rating ladder](../authentication/#joining-the-rating-ladder): `POST /bot/ladder/join` / `POST /bot/ladder/leave`, both registered-bot only. That page is the "how do I opt in" companion to this "what do these numbers mean" one.
