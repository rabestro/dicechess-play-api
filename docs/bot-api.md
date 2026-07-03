# Bot API Reference

This is a public, end-user reference for connecting third-party bots to the Dice Chess play platform.

For a reference client implementation in Scala, see [rabestro/dicechess-reference-bot](https://github.com/rabestro/dicechess-reference-bot).

## Base URL & Authentication

- **Base URL:** `https://play-api.jc.id.lv`
- **Authentication:** `Authorization: Bearer <token>` on every route except `POST /bot/anon`.

### Common Error Codes

- `400 Bad Request` — Malformed body or query parameters.
- `401 Unauthorized` — Missing, invalid, or expired Bearer token.
- `403 Forbidden` — Attempting to act on a game or challenge that does not belong to you, or submitting a move when it is not your turn.
- `404 Not Found` — Unknown game or challenge.
- `429 Too Many Requests` — Rate limited. Check the `Retry-After` header for cooldown duration.

---

## Game State & Mechanics

### Turn Resolving

A bot does not need to determine its seat color (White or Black) to submit moves. The server automatically resolves the seat of the caller based on their authorization token.

### Time Controls

Time controls are **enforced**: the server is the only timekeeper. The side to move runs down a real per-side clock and **loses on time** (a `Timeout` termination) if it does not complete its turn in time.

- `SuddenDeath` — one bank per side, no bonus.
- `Fischer` — the increment is credited when a turn is completed.
- `PerMove` — a fresh budget each turn (no carry-over).
- `Unlimited` — no clock (only a 120s anti-abandonment cap per turn).

The clock runs **per turn** (a turn = several micro-moves, one per die). A forced pass (no legal move) is instant and costs nothing. Remaining time is surfaced on the wire (see `clocks` on `Snapshot` and `DiceRolled` below) in **milliseconds**, so a bot can budget; the side to move is still ticking, so subtract your own elapsed time since the event.

### Provably-Fair Dice

The server is authoritative over the dice, but every roll is verifiable after the game:

1. **Commit.** At game creation the create response carries `commit = SHA-256(serverSeed)` — the server is now locked to a `serverSeed` it cannot change. The same `commit` also rides on every `Snapshot`, so a bot that only opens the game stream (and never saw the create response) still sees it before any roll.
2. **Contribute entropy.** After the commit, each side submits its own high-entropy `clientSeed` (see [`POST /bot/game/{id}/seed`](#submit-a-dice-seed)). Because the commit was published *before* the server saw any client seed, neither the server nor a player can grind the dice in their favour.
3. **Roll.** Every roll is `HMAC-SHA256(serverSeed, message)` mapped to three unbiased 1..6 values, where `message` is the canonical, length-prefixed concatenation `uint32be(len(clientSeedWhite)) ++ clientSeedWhite ++ uint32be(len(clientSeedBlack)) ++ clientSeedBlack ++ int64be(ply)` (seed lengths are UTF-8 byte counts; all integers big-endian). The seeds are fixed for the whole game.
4. **Reveal.** `GameEnded` reveals `seed` (the server seed) and `clientSeeds`, so anyone can recompute every roll and confirm that `SHA-256(seed)` equals the `commit`.

**Opening-roll gate.** The server holds the first roll until *both* seats have submitted a seed, so submit yours as soon as you receive `GameStart`. If a seat does not seed within a few seconds the game force-starts anyway, and that seat's contribution falls back to its (already-public) external id — a missing seed never stalls the game, it only forfeits that seat's own entropy contribution. A seed must be 16–256 characters (e.g. the hex of ≥8 random bytes); send a strong random one promptly.

### DFEN (Dice Forsyth-Edwards Notation)

Dice Chess uses DFEN to represent positions with rolled dice. It extends standard FEN by adding a **7th space-separated field** at the end.
- The 7th field represents the active player's pending dice pool as piece letters.
- The piece mapping for dice values is:
  - `1` → Pawn (`p` / `P`)
  - `2` → Knight (`n` / `N`)
  - `3` → Bishop (`b` / `B`)
  - `4` → Rook (`r` / `R`)
  - `5` → Queen (`q` / `Q`)
  - `6` → King (`k` / `K`)
- The letters are sorted numerically by their die value and capitalized for White, lowercase for Black.
- *Example:* If it is White's turn and the rolled dice are `[2, 3, 6]`, the 7th field is `NBK`.
- You do **not** need to compute legal moves yourself: the server publishes them with every roll (see [Legal Moves](#legal-moves)).

### Legal Moves

The server enumerates every legal turn for the pending roll and puts it on the wire, so a bot needs **no rules
implementation of its own** — in any language.

The shape is a **prefix tree of UCI micro-moves**: each key is a micro-move, its value is the tree of legal
continuations.

```json
{"e2e4": {"g1f3": {}, "b1c3": {}}, "d2d4": {"d4d5": {}}}
```

- **A node with no children (`{}`) is a complete legal turn**: walk any root-to-leaf path and submit it as `moves`.
  This is safe because every legal turn uses the maximal number of dice (the *Maximum Micro-moves Rule* is already
  applied), except a turn that captures the king — which ends the game and is always a leaf.
- **An empty tree (`{}` at the top level)** means the roll has no legal move: the server auto-passes on its own —
  submit nothing.
- **`null`** (only on the inline copies) means the enumeration was too large to inline — fetch the full tree from
  [`GET /games/{id}/moves`](#get-legal-moves).

The tree rides in three places:
1. `DiceRolled.legalMoves` — with every roll.
2. `Snapshot.state.legalMoves` (and the public `GET /games/{id}` snapshot) — while `dicePending` is true, so a
   (re)joining or polling bot can act from the snapshot alone.
3. [`GET /games/{id}/moves`](#get-legal-moves) — always the full tree, never capped.

A complete random bot is therefore just: read the tree, walk root→leaf picking a random child at each node, and
`POST` the path — no engine, no DFEN parsing required.

---

## REST API Endpoints

### Identity / Tokens

#### Mint Anonymous Token
`POST /bot/anon`

Mints an ephemeral, unranked token.
- **Query Parameter:** `name` (optional) — a name for the anonymous bot.
- **Rate Limit:** Per-IP rate-limited to 30 requests/hour. Returns `429` with `Retry-After` on limit breach.
- **Token TTL:** ~24 hours.
- **Response:** `201 Created`
  ```json
  {
    "token": "bearer-token-string",
    "team": "anon",
    "name": "mybot-8b7a6c5d",
    "id": "bot:team:anon:mybot-8b7a6c5d"
  }
  ```

#### Get Account Info
`GET /bot/account`

- **Response:** `200 OK`
  ```json
  {
    "team": "anon",
    "name": "mybot-8b7a6c5d",
    "id": "bot:team:anon:mybot-8b7a6c5d"
  }
  ```

---

### Challenge Management

#### Create Challenge
`POST /bot/challenge`

- **Request Body:**
  ```json
  {
    "team": "house",
    "name": "greedy",
    "timeControl": {"Unlimited": {}}
  }
  ```
  *(Note: To play against the server's built-in bot for testing, use `team="house"` and `name="greedy"`.)*
- **Response:** `201 Created`
  ```json
  {
    "id": "challenge-id",
    "challenger": {
      "Bot": {
        "team": "anon",
        "name": "mybot-8b7a6c5d"
      }
    },
    "target": {
      "Bot": {
        "team": "house",
        "name": "greedy"
      }
    },
    "timeControl": {
      "Unlimited": {}
    },
    "targetOnline": true
  }
  ```

  `targetOnline` says whether the target currently holds an account stream — advisory only: an offline target can
  still discover the challenge by polling [`GET /bot/challenges`](#list-pending-challenges) until it expires.
- **Errors:** `400 Bad Request` — challenging yourself; `429 Too Many Requests` — too many pending challenges
  (accept, decline, or let them expire).
- **Expiry:** an unclaimed challenge expires after **~5 minutes**; the challenger then receives `ChallengeDeclined`
  on its event stream (a polling challenger sees the entry vanish from its `out` list).

#### List Pending Challenges

`GET /bot/challenges`

The polling counterpart of the event stream: every pending challenge involving the caller. `in` entries are addressed
to you (accept or decline by id); `out` entries are yours (watch their fate — one vanishing means it was accepted, so
check [`GET /bot/games`](#list-my-games), declined, or expired). A bot that was offline when `ChallengeReceived` was
pushed recovers it here.

- **Response:** `200 OK`

  ```json
  {
    "in": [{"id": "challenge-7", "challenger": {"Bot": {"team": "acme", "name": "rival"}},
            "target": {"Bot": {"team": "anon", "name": "mybot-8b7a6c5d"}}, "timeControl": {"Unlimited": {}}}],
    "out": []
  }
  ```

#### List My Games

`GET /bot/games`

Every live game the caller is seated in — the polling counterpart of `GameStart` and the **post-restart recovery
path**: games survive a server restart, and this listing finds them again even if the start event was never seen.
Enough to decide whether to act; fetch [`GET /games/{id}`](#game-event-stream) for the position and
[`GET /games/{id}/moves`](#get-legal-moves) for the legal turns.

- **Response:** `200 OK`

  ```json
  {
    "games": [{
      "gameId": "game-uuid",
      "seat": "White",
      "activeSeat": "White",
      "dicePending": true,
      "timeControl": {"Unlimited": {}},
      "clocks": null,
      "version": 17
    }]
  }
  ```

#### Accept Challenge
`POST /bot/challenge/{id}/accept`

Accepts a pending challenge. (Only callable by the challenged bot).
- **Response:** `201 Created`
  ```json
  {
    "gameId": "game-uuid"
  }
  ```

#### Decline Challenge
`POST /bot/challenge/{id}/decline`

- **Response:** `200 OK`

---

### Gameplay

#### Submit a Dice Seed
`POST /bot/game/{id}/seed`

Contribute this seat's post-commit entropy for the [provably-fair dice](#provably-fair-dice). Submit once, as soon as the game starts and before the opening roll.
- **Request Body:**
  ```json
  {
    "seed": "f3a1c0de9b8a7c6d"
  }
  ```
- **Response:** `202 Accepted` (fire-and-forget). A duplicate, too-late, or malformed seed is ignored (a malformed one may surface as a `Rejected` event on the game stream).

#### Submit Turn Moves
`POST /bot/game/{id}/move`

Submits the turn's micro-moves in UCI notation. Submit one move per rolled die.
- **Request Body:**
  ```json
  {
    "moves": ["e2e4", "g8f6"]
  }
  ```
- **Response:** `202 Accepted` (fire-and-forget; validity results arrive asynchronously on the game stream as `TurnPlayed` or `Rejected`).

#### Resign Game
`POST /bot/game/{id}/resign`

- **Response:** `202 Accepted`

#### Get Legal Moves
`GET /games/{id}/moves`

The full [legal-move tree](#legal-moves) for the game's pending roll — never capped, unlike the inline copies on
`DiceRolled`/`Snapshot`. Public (no `Authorization` header needed): legal moves are a pure function of the
already-public position.

- **Response:** `200 OK`
  ```json
  {
    "version": 4,
    "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
    "dicePending": true,
    "legalMoves": {"b1c3": {"g1f3": {"e2e4": {}}}, "e2e4": {"b1c3": {"g1f3": {}}}}
  }
  ```
  `version` and `dfen` tie the tree to the roll it answers (compare with the `v` of the `DiceRolled` you are acting
  on). `legalMoves` is `{}` when `dicePending` is `false` (between turns or after the game ends) or when the roll is
  a forced pass.
- **Errors:** `404 Not Found` — unknown game id.

---

## Streaming Endpoints (ndjson)

Both event streams return `application/x-ndjson` (Newline Delimited JSON).
- Each message is a single JSON object on one line.
- **Blank lines are keep-alives (~25s) and must be ignored by your parser.**

### Account Event Stream
`GET /bot/stream/event`

Long-lived stream for incoming challenges and game starts.

The stream is **live-only** (events published while you are disconnected are not replayed), but it is no longer the
only source of truth: [`GET /bot/challenges`](#list-pending-challenges) and [`GET /bot/games`](#list-my-games) recover
the same facts by polling. A **poll-only bot** — e.g. a cron-triggered cloud function that never holds a stream — can
play entirely without it: wake → list challenges → accept → list games → for each game where `dicePending` and
`activeSeat` is yours, fetch the [legal moves](#legal-moves) and submit a turn → sleep. Mind the clocks: for
`Unlimited` games the 120s anti-abandonment cap makes a ~1-minute timer sufficient; short time controls need the
stream (or faster polling) to stay ahead of the clock.

- **Events:**
  - **ChallengeReceived:**
    ```json
    {"ChallengeReceived":{"id":"challenge-uuid","challenger":{"Bot":{"team":"anon","name":"other-bot"}}}}
    ```
  - **ChallengeDeclined:**
    ```json
    {"ChallengeDeclined":{"id":"challenge-uuid"}}
    ```
  - **GameStart:**
    ```json
    {"GameStart":{"gameId":"game-uuid"}}
    ```

### Game Event Stream
`GET /bot/game/stream/{id}`

Long-lived stream for a specific game's state transitions.

- **Events:**
  - **Snapshot** (Sent immediately on connect, contains current game state):
    ```json
    {
      "Snapshot": {
        "v": 0,
        "state": {
          "version": 0,
          "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
          "activeSeat": "White",
          "dicePending": true,
          "status": {
            "Active": {}
          },
          "timeControl": {
            "Unlimited": {}
          },
          "clocks": null,
          "commit": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
          "seed": null,
          "clientSeeds": null,
          "legalMoves": null
        }
      }
    }
    ```
    `commit` is the dice commitment (constant for the game); `seed` and `clientSeeds` stay `null` until the game ends, then carry the reveal (same fields as `GameEnded`). While `dicePending` is `true`, `legalMoves` carries the pending roll's [legal-move tree](#legal-moves) (or `null` if it was too large to inline — fetch [`GET /games/{id}/moves`](#get-legal-moves)).
  - **DiceRolled** (Server rolled dice for a player's turn):
    ```json
    {
      "DiceRolled": {
        "v": 1,
        "seat": "White",
        "dice": [2, 3, 6],
        "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
        "clocks": {"white": 180000, "black": 175000},
        "legalMoves": {"b1c3": {"g1f3": {"e2e4": {}}}, "e2e4": {"b1c3": {"g1f3": {}}}}
      }
    }
    ```
    `clocks` is remaining milliseconds per side, or `null` for an `Unlimited` game. It rides on every `Snapshot` and `DiceRolled`; the side to move keeps ticking, so count down locally between events. On a flag-fall the game ends with `termination: "Timeout"` and the loser's clock at `0`.
    `legalMoves` is the roll's [legal-move tree](#legal-moves): walk any root-to-leaf path and submit it. `{}` = no legal move (the server auto-passes; submit nothing); `null` = too large to inline, fetch [`GET /games/{id}/moves`](#get-legal-moves).
  - **TurnPlayed** (Turn moves applied):
    ```json
    {
      "TurnPlayed": {
        "v": 2,
        "seat": "White",
        "moves": ["e2e4"],
        "fenAfter": "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
      }
    }
    ```
  - **GameEnded** (Game over):
    ```json
    {
      "GameEnded": {
        "v": 3,
        "over": {
          "result": {
            "Win": {
              "side": "White"
            }
          },
          "termination": "KingCaptured"
        },
        "seed": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
        "clientSeeds": {"white": "f3a1...", "black": "b27c..."}
      }
    }
    ```
    *(Result can also be `{"Draw":{}}` and termination can be one of `"KingCaptured"`, `"Resign"`, `"Draw"`, `"Aborted"`, `"Timeout"`.)*
    `seed` is the revealed server seed (hex): `SHA-256(seed)` equals the `commit` (in the create response and on every `Snapshot`), so anyone can open the dice commitment after the game. `clientSeeds` reveals the two post-commit client seeds folded into every roll (see [Provably-Fair Dice](#provably-fair-dice)); with these you can recompute the full transcript: `roll(ply) = HMAC-SHA256(seed, uint32be(len(white)) ++ white ++ uint32be(len(black)) ++ black ++ int64be(ply))`. A seat that never submitted a seed shows its external id here (the fallback the server used).
  - **Rejected** (Moves rejected by the engine):
    ```json
    {
      "Rejected": {
        "v": 2,
        "seat": "White",
        "reason": "Move e2e4 is illegal for dice pool"
      }
    }
    ```

---

## Data Shapes

### Principal
JSON representations of a participant's identity:
- Guest: `{"Guest":{"id":"guest-uuid"}}`
- User: `{"User":{"id":"user-uuid"}}`
- Bot: `{"Bot":{"team":"anon","name":"mybot"}}`

### TimeControl
- Unlimited: `{"Unlimited":{}}`
- SuddenDeath: `{"SuddenDeath":{"initialSeconds":180}}`
- Fischer: `{"Fischer":{"initialSeconds":180,"incrementSeconds":2}}`
- PerMove: `{"PerMove":{"secondsPerMove":10}}`

### Clocks
Remaining time per side, in **milliseconds**, as of the carrying event: `{"white":180000,"black":175000}`. `null` on `Unlimited` games. Appears on `Snapshot.state` and `DiceRolled`.

---

## Test Loop (runnable curl)

Run these steps in separate terminals or script them to play against the server's greedy house bot:

### 1. Mint an Anonymous Token
```bash
T=$(curl -sX POST 'https://play-api.jc.id.lv/bot/anon?name=mybot' | jq -r .token)
echo "Token: $T"
```

### 2. Listen to Event Stream (Terminal 1)
```bash
curl -N -H "Authorization: Bearer $T" https://play-api.jc.id.lv/bot/stream/event
```

### 3. Challenge the House Bot (Terminal 2)
```bash
curl -sX POST -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' \
  -d '{"team":"house","name":"greedy"}' \
  https://play-api.jc.id.lv/bot/challenge
```
*The house bot will automatically accept your challenge. In Terminal 1, you will see a `GameStart` event containing a `gameId`.*

### 4. Listen to the Game Stream (Terminal 3)
Replace `<gameId>` with the ID from the `GameStart` event:
```bash
curl -N -H "Authorization: Bearer $T" https://play-api.jc.id.lv/bot/game/stream/<gameId>
```
On each `DiceRolled` event where the `seat` matches your bot, pick a turn from its `legalMoves` tree (any root-to-leaf path) and submit it; if `legalMoves` is `null` (over the inline cap), fetch the full tree from `GET /games/{id}/moves` first.

### 5. Submit Your Dice Seed (Terminal 2)
As soon as the game starts — before the opening roll — contribute your entropy for the provably-fair dice:
```bash
curl -sX POST -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' \
  -d "{\"seed\":\"$(openssl rand -hex 16)\"}" \
  https://play-api.jc.id.lv/bot/game/<gameId>/seed
```

### 6. Submit a Turn (Terminal 2)
Replace `<gameId>` with your game's ID and provide the list of UCI micro-moves:
```bash
curl -sX POST -H "Authorization: Bearer $T" \
  -H 'Content-Type: application/json' \
  -d '{"moves":["e2e4"]}' \
  https://play-api.jc.id.lv/bot/game/<gameId>/move
```

### Self-Play Test Loop

To run a bot against itself:

1. Mint two tokens:
   ```bash
   T1=$(curl -sX POST 'https://play-api.jc.id.lv/bot/anon?name=bot-a' | jq -r .token)
   T2=$(curl -sX POST 'https://play-api.jc.id.lv/bot/anon?name=bot-b' | jq -r .token)
   ```
2. Get the identity of Bot B:
   ```bash
   NAME_B=$(curl -s -H "Authorization: Bearer $T2" https://play-api.jc.id.lv/bot/account | jq -r .name)
   ```
3. Open event streams for both bots.
4. Have Bot A challenge Bot B:
   ```bash
   curl -sX POST -H "Authorization: Bearer $T1" \
     -H 'Content-Type: application/json' \
     -d "{\"team\":\"anon\",\"name\":\"$NAME_B\"}" \
     https://play-api.jc.id.lv/bot/challenge
   ```
5. Bot B receives the challenge via its event stream, extracts the `challengeId`, and accepts it:
   ```bash
   curl -sX POST -H "Authorization: Bearer $T2" https://play-api.jc.id.lv/bot/challenge/<challengeId>/accept
   ```
