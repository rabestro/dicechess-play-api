---
title: REST Endpoints
description: The complete REST surface — identity, challenges, seeks, gameplay, public discovery, the leaderboard, and the strength report.
---

All routes are relative to `https://play-api.jc.id.lv` and require `Authorization: Bearer <token>` unless marked **public**. See [Authentication & Identity](../../authentication/) for tokens and [Common error codes](../../authentication/#common-error-codes).

## Identity & tokens

Covered in depth under [Authentication & Identity](../../authentication/); summarised here.

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/bot/anon` | Mint an anonymous token (public; `?name=` optional). |
| `POST` | `/bot/register` | Claim a durable identity. Token shown once. |
| `POST` | `/bot/token` | Rotate the token (registered only). |
| `GET` | `/bot/account` | Current identity. |
| `POST` | `/bot/ladder/join` · `/bot/ladder/leave` | Opt in/out of the rating ladder (registered only). |
| `POST` | `/bot/open-to-humans` · `/bot/open-to-humans/leave` | Opt in/out of the human catalog; the open call sets an optional description (registered only). |

## Challenges

### Create challenge

`POST /bot/challenge`

```json
{ "team": "house", "name": "greedy", "timeControl": { "Unlimited": {} } }
```

Responds `201` with the challenge, including `targetOnline` (advisory — an offline target can still discover it by polling). Errors: `400` challenging yourself; `429` too many pending. An unclaimed challenge expires after ~5 minutes.

### List pending challenges

`GET /bot/challenges`

Every pending challenge involving you. `in` entries are addressed to you (accept/decline by id); `out` are yours to watch. Recovers challenges you missed while offline.

```json
{ "in": [{ "id": "challenge-7", "challenger": { "Bot": { "team": "acme", "name": "rival" } }, "timeControl": { "Unlimited": {} } }], "out": [] }
```

### Accept / decline challenge

`POST /bot/challenge/{id}/accept` → `201 { "gameId": "game-uuid" }` (only the challenged bot).
`POST /bot/challenge/{id}/decline` → `200`.

## Seeks (meeting humans)

Also useful for testing your own bot against yourself — see
[Play Against Your Bot](../../play-your-bot/).

### Post a lobby seek

`POST /bot/seeks`

A standing public offer in the same lobby guests use — anyone, human or bot, may accept it.

```json
{ "timeControl": { "Fischer": { "initialSeconds": 180, "incrementSeconds": 2 } } }
```

`timeControl` is optional. Omit it and the seek gets **Fischer 600+10** (10+10): a human can sit down at this offer, and a clockless public game has nothing to end it but the 120-second anti-abandonment cap. Pass `{ "Unlimited": {} }` explicitly if you really want no clock.

Responds `201 { "seekId": "seek-12", "secret": "capability-secret" }`. Hold the seek by polling `GET /lobby/seeks/{id}?secret=<secret>` — bot seeks expire after ~2 minutes without a poll; that same poll reports the match. Cancel with `DELETE /lobby/seeks/{id}?secret=<secret>`. Cap: 3 open seeks (`429` beyond).

### Accept a lobby seek

`POST /bot/seeks/{id}/accept` — accept an open seek from the public `GET /lobby/seeks` list. Colour is random; read it off [`GET /bot/games`](#list-my-games). Errors: `404` no such seek, `409` claimed first, `400` your own seek.

## Gameplay

### Submit a dice seed

`POST /bot/game/{id}/seed`

Contribute this seat's entropy for the [provably-fair dice](../../provably-fair/). Submit once, as soon as the game starts and before the opening roll.

```json
{ "seed": "f3a1c0de9b8a7c6d" }
```

Responds `202` (fire-and-forget). A duplicate, too-late, or malformed seed is ignored (a malformed one may surface as a `Rejected` game-stream event). A seed is 16–256 characters.

### Submit turn moves

`POST /bot/game/{id}/move`

The turn's micro-moves in UCI, one per rolled die.

```json
{ "moves": ["e2e4", "g8f6"] }
```

The verdict is **synchronous**:

- `200 { "applied": true, "version": 17, "reason": null }` — applied; `version` is the resulting `TurnPlayed`'s `v`.
- `409 { "applied": false, "version": null, "reason": "illegal turn" }` — refused, same reason the stream's `Rejected` carries (`"not your turn"`, `"illegal turn"`, `"game is over"`).
- `202` — fallback: no verdict within a few seconds (never blocks on a wedged game); treat as fire-and-forget and watch the stream.

A `TurnPlayed`/`Rejected` still broadcasts on the game stream regardless, so fire-and-forget bots can ignore the body.

### Resign

`POST /bot/game/{id}/resign` → `202`.

### List my games

`GET /bot/games`

Every live game you are seated in — the polling counterpart of `GameStart` and the **post-restart recovery path**.

```json
{ "games": [{ "gameId": "game-uuid", "seat": "White", "activeSeat": "White", "dicePending": true, "timeControl": { "Unlimited": {} }, "clocks": null, "version": 17 }] }
```

## Public discovery

### List live games

`GET /games` — **public.** Every live game on the node, both seats' public faces — the spectating surface. Sorted by `version` descending, capped at 50; `total` carries the real count. No legal-move tree (fetch the per-game endpoint).

```json
{
  "games": [{
    "gameId": "game-uuid",
    "players": { "white": { "kind": "Bot", "name": "house greedy" }, "black": { "kind": "Human", "name": null } },
    "timeControl": { "Unlimited": {} },
    "activeSeat": "Black", "dicePending": true, "clocks": null, "version": 17,
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR b KQkq - 0 1 nk"
  }],
  "total": 1
}
```

### Get legal moves

`GET /games/{id}/moves` — **public.** The full [legal-move tree](../../game-mechanics/#legal-moves) for the pending roll, never capped.

```json
{
  "version": 4,
  "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
  "dicePending": true,
  "legalMoves": { "b1c3": { "g1f3": { "e2e4": {} } }, "e2e4": { "b1c3": { "g1f3": {} } } }
}
```

`version` and `dfen` tie the tree to the roll it answers. `legalMoves` is `{}` when `dicePending` is `false` or the roll is a forced pass. Errors: `404` unknown game.

### Get a game snapshot

`GET /games/{id}` — **public.** The polling read of a single game: the same `Snapshot.state` object the game stream sends on connect (documented under [Event Streams](../streaming/#snapshot)) — `dfen`, `activeSeat`, `dicePending`, `clocks`, `commit`, `players`, and, while `dicePending` is true, the inline `legalMoves`. This is also where a withheld [dice reveal](../../provably-fair/#the-mirror-pair-exception-withheld-reveal) becomes available: for a mirror-pair game, `seed` and `clientSeeds` stay `null` on the live `GameEnded` event until both games conclude, then appear here on a re-poll. Errors: `404` unknown game.

### Get game history (replay)

`GET /games/{id}/history` — **public.** The full replay of a **finished** game, independent of whether the live room has been evicted from memory: every turn's dice and moves, plus the dice-fairness reveal so anyone can [re-derive every roll](../../provably-fair/) and check it against the commitment published at creation.

```json
{
  "gameId": "game-uuid",
  "players": { "white": { "kind": "Bot", "name": "house greedy" }, "black": { "kind": "Human", "name": null } },
  "rated": true,
  "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 3 } },
  "result": 1,
  "termination": "king_captured",
  "finishedAt": "2026-07-30T21:05:00Z",
  "initialDfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "turns": [{ "turnNumber": 1, "activeColor": "White", "dice": [1, 1, 4], "moves": ["e2e4"], "fenAfter": "..." }],
  "fairness": { "commit": "sha256-hex", "seed": "server-seed-hex", "clientSeeds": { "white": "...", "black": "..." } }
}
```

`result` is white-POV (`1` white won, `-1` black won, `0` draw) and `termination` is one of `king_captured` / `timeout` / `resign` / `draw_agreement` / `aborted` — there is no requester identity here, so neither is reframed to a point of view the way a player's own games list is. `fairness.commit` is always present; `seed`/`clientSeeds` are `null` until the game is reveal-eligible — immediately for an ordinary game, or once its [mirror-pair partner](../../provably-fair/#the-mirror-pair-exception-withheld-reveal) has also concluded. A fully revealed response is cached `public, max-age=31536000, immutable` (it can never change again); a withheld one is cached only briefly, so a re-fetch after the partner concludes sees the reveal promptly. Errors: `404` unknown game, or a finished game with no archive row (pre-archive history — not backfilled).

## Leaderboard & bot profiles

Public, no `Authorization`. Both exist only when the server runs with persistence; an in-memory dev server answers `404`.

### Leaderboard

`GET /leaderboard`

Registered bots whose rating has **converged** (RD ≤ 110), best first. Provisional bots are counted internally but absent by policy — see [Rating & Ladder](../../rating/) for what that means and why it can take longer than expected. `wins`/`draws`/`losses` count **rated, decided** games only.

```json
{ "leaders": [{ "rank": 1, "team": "acme", "name": "alice", "rating": 1720.5, "rd": 85.2, "onLadder": true, "games": 42, "wins": 30, "draws": 2, "losses": 10 }] }
```

A bot that left the ladder keeps its frozen rating and stays listed with `onLadder: false`.

### Bot catalog

`GET /lobby/bots`

Bots that opened themselves to human play via [`POST /bot/open-to-humans`](#identity), each with the rating summary its catalog card shows. Unlike the leaderboard, a **provisional** bot (RD > 110) is listed and flagged rather than hidden, so a freshly opened bot still appears. `description` is the bot's own blurb (may be `null`).

```json
{ "bots": [{ "team": "acme", "name": "alice", "rating": 1720.5, "rd": 85.0, "provisional": false, "description": "aggressive + book" }] }
```

### Wake a catalog bot

`POST /lobby/bots/{team}/{name}/wake`

Before starting a game against a scale-to-zero bot, ping it to force a cold start and confirm it actually answers — the SPA calls this on catalog click, before offering the game-config panel. `404` for a name outside the catalog; otherwise `200` always, `alive` covering "no webhook registered" and "webhook didn't answer" alike (the caller only needs yes/no). `503` if the server runs without webhooks enabled. Rate-limited per IP.

```json
{ "alive": true }
```

### Play a catalog bot

`POST /lobby/play-bot`

Starts a guest-vs-bot game from the catalog:

```json
{ "guestId": "0a1b2c3d-...", "team": "acme", "name": "alice", "timeControl": { "Fischer": { "initialSeconds": 300, "incrementSeconds": 5 } }, "preferredColor": "White" }
```

`guestId` is the SPA's stable per-browser identity (a UUID, same convention as `POST /lobby/seeks`'s `creator`). `timeControl` is **mandatory** — a catalog game is never unlimited, `400` if it is. `preferredColor` (`"White"` / `"Black"`) is optional; omitted, the seat is random. Responds `201` with the guest's seat:

```json
{ "gameId": "g-42", "token": "seat-secret", "seat": "White" }
```

Errors: `400` bad body, invalid `guestId`, or an unlimited time control; `404` a name outside the catalog; `409` the guest already has an unfinished catalog game (one at a time, for now); `429` rate limit. No fresh liveness check runs here — `wake` already confirmed the bot moments earlier, and a bot that's gone dark since is handled the same way any registered-webhook bot going quiet mid-game is: the clock forfeits it.

### Bot profile

`GET /bots/{team}/{name}`

One registered bot's public card: rating summary, its aggregate record against every opponent it has played, and up to 20 recent games, newest first. Unlike the board, a **provisional** bot is visible here (flagged). `opponent` is a public face (never a raw id); `result` is from the profiled bot's point of view.

`opponents` is one row per other registered bot (head-to-head) plus one collapsed row for every human/guest opponent combined — that collapsed row (`team`/`botName` both `null`) is the bot's **record vs humans**. Unlike the top-level `wins`/`draws`/`losses` (rated, decided games only — the ladder record), `opponents` counts every game, rated and casual alike: a guest game is always casual, so a rated-only tally would always read zero against humans. A bucket with no games simply has no row.

```json
{
  "team": "acme", "name": "alice",
  "rating": 1650.0, "rd": 95.0, "provisional": false, "onLadder": true,
  "games": 30, "wins": 20, "draws": 3, "losses": 7,
  "opponents": [
    { "opponent": { "kind": "Bot", "name": "acme bob" }, "team": "acme", "botName": "bob", "games": 42, "wins": 22, "draws": 4, "losses": 16, "lastPlayedAt": "2026-07-16T12:00:00Z" },
    { "opponent": { "kind": "Human", "name": null }, "team": null, "botName": null, "games": 15, "wins": 11, "draws": 1, "losses": 3, "lastPlayedAt": "2026-07-15T09:30:00Z" }
  ],
  "recent": [{ "gameId": "game-uuid", "seat": "White", "opponent": { "kind": "Bot", "name": "acme bob" }, "result": "win", "rated": true, "termination": "resign", "finishedAt": "2026-07-16T12:00:00Z" }]
}
```

Errors: `404` no registered bot with that team/name.

## Strength report

Public, no `Authorization`. The precise, error-rate-bounded complement to the Glicko-2 leaderboard above — see [Rating & Ladder](../../rating/) for why a bot might want both numbers. Exists only when the server runs with persistence (`404` otherwise); before the rating batch has completed its first refresh (a fresh boot, or a server with rating updates disabled), both routes answer `503` rather than blocking on a synchronous build — the underlying report folds the entire game history and its ranking runs a four-figure bootstrap, too expensive to pay per request.

### Strength report

`GET /strength`

The whole cached report: every pairwise [SPRT](https://en.wikipedia.org/wiki/Sequential_probability_ratio_test) verdict — combining CRN mirror-pair observations with any eligible single (unpaired) games for that matchup — plus a [Bradley-Terry](https://en.wikipedia.org/wiki/Bradley%E2%80%93Terry_model) pool ranking. `verdict` is `"AcceptH1"` (the `perspective` bot is stronger), `"AcceptH0"` (not stronger by the tested margin), or `"Continue"` (not enough data yet) — `Continue` is surfaced honestly rather than hidden or rounded into a claim. `elo` in `ranking` is **relative** (the pool's mean is 0 by construction, not the Glicko board's 1500-centred scale).

```json
{
  "pairwise": [{
    "perspective": "acme/alice", "opponent": "acme/bob",
    "pairs": { "n0": 1, "n1": 0, "n2": 2, "n3": 4, "n4": 9 },
    "singles": { "losses": 0, "draws": 1, "wins": 2 },
    "result": { "llr": 1.8, "lower": -2.89, "upper": 2.89, "verdict": "Continue", "observations": 19 }
  }],
  "ranking": [{ "player": "acme/alice", "elo": 42.0, "ciLow": 10.0, "ciHigh": 74.0, "losVsNext": 0.91 }],
  "completePairs": 16, "singles": 3, "excludedRows": 2
}
```

### Bot strength profile

`GET /bots/{team}/{name}/strength`

Just the matchups involving one registered bot — the profile-page-sized slice of the report above. `pairwise` uses the same shape. Errors: `404` no registered bot with that team/name.

```json
{ "team": "acme", "name": "alice", "pairwise": [{ "perspective": "acme/alice", "opponent": "acme/bob", "pairs": { "n0": 1, "n1": 0, "n2": 2, "n3": 4, "n4": 9 }, "singles": { "losses": 0, "draws": 1, "wins": 2 }, "result": { "llr": 1.8, "lower": -2.89, "upper": 2.89, "verdict": "Continue", "observations": 19 } }] }
```
