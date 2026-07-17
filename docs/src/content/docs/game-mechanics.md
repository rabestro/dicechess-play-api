---
title: Game Mechanics
description: How a turn resolves, the enforced clocks, DFEN notation, and the legal-move tree the server hands you so no bot ever implements the rules.
---

The single most important thing to know: **you never implement the rules of Dice Chess.** The server enumerates every legal turn for the current roll and puts it on the wire. Everything below explains what that data means.

## Turn resolving

A turn begins when the server rolls three dice for the side to move. Each die value is a piece type, and the player makes one micro-move per die, in any order, using pieces of the rolled types. You do **not** determine your own seat colour — the server resolves it from your token.

A turn ends when its micro-moves are spent, when there is no further legal move (a forced pass), or when it captures the opponent's king (which wins immediately).

## Dice → piece mapping

<ul class="dice-map">
	<li><span class="pip">⚀</span><span class="glyph">♙</span><span class="name">1 · Pawn</span></li>
	<li><span class="pip">⚁</span><span class="glyph">♘</span><span class="name">2 · Knight</span></li>
	<li><span class="pip">⚂</span><span class="glyph">♗</span><span class="name">3 · Bishop</span></li>
	<li><span class="pip">⚃</span><span class="glyph">♖</span><span class="name">4 · Rook</span></li>
	<li><span class="pip">⚄</span><span class="glyph">♕</span><span class="name">5 · Queen</span></li>
	<li><span class="pip">⚅</span><span class="glyph">♔</span><span class="name">6 · King</span></li>
</ul>

## Time controls

Time controls are **enforced** — the server is the only timekeeper. The side to move runs down a real per-side clock and **loses on time** (a `Timeout` termination) if it does not complete its turn in time.

| Control | Behaviour |
| --- | --- |
| `Unlimited` | No clock — only a 120-second anti-abandonment cap per turn. |
| `SuddenDeath` | One bank per side, no bonus. |
| `Fischer` | An increment is credited when a turn is completed. |
| `PerMove` | A fresh budget each turn, no carry-over. |

The clock runs **per turn** (a turn is several micro-moves, one per die). A forced pass is instant and free. Remaining time rides on the wire in **milliseconds** (`clocks` on `Snapshot` and `DiceRolled`); the side to move keeps ticking, so subtract your own elapsed time since the event. On a flag-fall the game ends `Timeout` with the loser's clock at `0`.

See the exact JSON shapes in [Data Shapes → TimeControl](./reference/data-shapes/#timecontrol).

## DFEN — Dice Forsyth–Edwards Notation

Positions are represented in **DFEN**, which extends standard FEN with a **7th space-separated field** holding the active player's pending dice as piece letters:

```
rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK
                                                          ↑ dice pool
```

- Each die value becomes a piece letter: `1→p 2→n 3→b 4→r 5→q 6→k`.
- Letters are sorted by die value and cased by side — uppercase for White, lowercase for Black.
- Example: White has rolled `[2, 3, 6]` → the 7th field is `NBK`.

You do not have to parse DFEN to play — the legal moves are handed to you directly — but it is the canonical position string on every event.

## Legal moves

The server publishes every legal turn for the pending roll as a **prefix tree of UCI micro-moves**. Each key is a micro-move; its value is the tree of legal continuations.

```json
{ "e2e4": { "g1f3": {}, "b1c3": {} }, "d2d4": { "d4d5": {} } }
```

Reading the tree:

- **A node with no children (`{}`) is a complete legal turn.** Walk any root-to-leaf path and submit that path as `moves`. Every legal turn already uses the maximum number of dice (the *Maximum Micro-moves Rule* is applied for you) — except a king capture, which ends the game and is always a leaf.
- **An empty tree (`{}` at the top level)** means the roll has no legal move: the server auto-passes, so submit nothing.
- **`null`** (only on the inline copies carried by events) means the enumeration was too large to inline — fetch the full tree from [`GET /games/{id}/moves`](./reference/rest/#get-legal-moves).

The tree appears in three places:

1. `DiceRolled.legalMoves` — with every roll (see [Event Streams](./reference/streaming/)).
2. `Snapshot.state.legalMoves` (and the public `GET /games/{id}` snapshot) — while `dicePending` is true, so a joining or polling bot can act from the snapshot alone.
3. [`GET /games/{id}/moves`](./reference/rest/#get-legal-moves) — always the full tree, never capped.

A complete random bot is therefore: read the tree, walk root→leaf picking a random child at each node, and `POST` the path — no engine, no DFEN parsing. That is exactly what [`examples/random_bot.py`](https://github.com/rabestro/dicechess-play-api/blob/main/docs/examples/random_bot.py) does, end to end, in ~100 lines.
