# dicechess-play-api

Authoritative real-time server for **Dice Chess** — human-vs-human play, the doubling
cube, and a third-party **Bot API**. This is **phase 3** of the play platform: the pivot
from client-authoritative (vs-bot, phases 1–2 in [`dicechess-play`](https://github.com/rabestro/dicechess-play))
to a server that owns the truth.

> **Status: design complete, implementation starting.** The design is documented as
> ADR-0007 (server authority), ADR-0008 (dice fairness), ADR-0009 (Bot API & tournaments)
> in the `dicechess-docs` vault (Play Site). First milestone: `3a-core`.

## Why a server now

Phases 1–2 are client-authoritative because there is nothing to cheat — a human plays a
local bot, no stakes, the Scala.js engine runs in the browser. Human-vs-human breaks that:
a patched client trivially fakes **both the dice and its moves**. So HvH forces a server
that holds the true game state, validates every micro-move through the engine, rolls the
dice, and owns the clocks. This is the role Lichess's `lila` plays.

## Architecture

Scala 3 · cats-effect · http4s, reusing the **dice-chess engine on the JVM**
(`lv.id.jc` artifact, GitHub Packages) so move legality and rules never drift from the
client. Shaped like Lichess (`lila` authority + `lila-ws` edge).

```
  browser SPA (dicechess-play) ──WebSocket──┐
                                            ▼
  third-party bot ──HTTP (ndjson + REST)──► play-api (AUTHORITY)
                                            │  per-game fiber + Ref + Topic + Queue
                                            │  engine (JVM) · server clocks · DiceSource
                                            ▼  on game end: POST /api/games (Bearer)
                                       dicechess-analytics (read-only + token write)

  vs-bot: stays 100% client-side (Scala.js engine in the browser). Never touches play-api.
```

**Transport-agnostic player — the core principle.** A `GameRoom` does not know whether a
player is a human over WebSocket or a bot over HTTP. A player is *something that receives
game events and submits commands*, identified by a `Principal` and seated at a `Seat`. The
website WS and the Bot API are two thin adapters over the same room — the game logic is
written once and is identical for human-vs-human, human-vs-bot, and bot-vs-bot.

### Dice fairness

The **server** generates dice (CSPRNG), wrapped in **commit-reveal** so every roll is
provably fair after the fact, behind a swappable `DiceSource` interface. No client ever
rolls; no blockchain. See ADR-0008.

### Bot API

Third-party bots connect via a dedicated, Lichess-shaped API (token + ndjson event stream
+ REST move commands), not the website's WebSocket — language-agnostic and reconnect-safe.
Our own engine bots are the **first clients** (a `reference-bot` wrapping the JVM engine),
which dogfoods the exact API external teams will use and provides always-online opponents.
See ADR-0009.

## Roadmap (milestones)

| Milestone | Deliverable |
|-----------|-------------|
| **3a-core** | Authoritative `GameRoom` + transport-agnostic seams + engine + commit-reveal `DiceSource`, proven by an in-memory self-play test (no HTTP) |
| **3a-net** | Human WebSocket transport — two browsers play HvH end-to-end on one node |
| **3b** | Durability (Postgres `play` schema, crash recovery) + analytics hand-off + **Bot API** + reference bot + bot accounts/tokens |
| **3c** | Edge split (`play-ws`) + Redis pub/sub + reconnection polish + account claim-flow |
| **3d** | Share-link challenges + live spectating + doubling cube UX |
| **3e** | Open seek lobby + cross-team bot tournaments (double round-robin, mirrored dice, Glicko-2) |

## Stack

Scala 3 · cats-effect · fs2 · http4s · doobie · Circe · PostgreSQL · the dice-chess engine
(JVM). Same toolchain as [`dicechess-analytics`](https://github.com/rabestro/dicechess-analytics).

## License

[AGPL-3.0](./LICENSE) — inherited from the dice-chess engine this server links.
