---
title: REST Endpoints
description: The complete REST surface — identity, challenges, seeks, gameplay, public discovery, and the leaderboard.
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

### Bot profile

`GET /bots/{team}/{name}`

One registered bot's public card: rating summary plus up to 20 recent games, newest first. Unlike the board, a **provisional** bot is visible here (flagged). `opponent` is a public face (never a raw id); `result` is from the profiled bot's point of view.

```json
{
  "team": "acme", "name": "alice",
  "rating": 1650.0, "rd": 95.0, "provisional": false, "onLadder": true,
  "games": 30, "wins": 20, "draws": 3, "losses": 7,
  "recent": [{ "gameId": "game-uuid", "seat": "White", "opponent": { "kind": "Bot", "name": "acme bob" }, "result": "win", "rated": true, "termination": "resign", "finishedAt": "2026-07-16T12:00:00Z" }]
}
```

Errors: `404` no registered bot with that team/name.
