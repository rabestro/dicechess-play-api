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
- Compute legal moves using the engine for these dice and submit them.

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
    }
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

---

## Streaming Endpoints (ndjson)

Both event streams return `application/x-ndjson` (Newline Delimited JSON).
- Each message is a single JSON object on one line.
- **Blank lines are keep-alives (~25s) and must be ignored by your parser.**

### Account Event Stream
`GET /bot/stream/event`

Long-lived stream for incoming challenges and game starts.

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
          "clocks": null
        }
      }
    }
    ```
  - **DiceRolled** (Server rolled dice for a player's turn):
    ```json
    {
      "DiceRolled": {
        "v": 1,
        "seat": "White",
        "dice": [2, 3, 6],
        "dfen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1 NBK",
        "clocks": {"white": 180000, "black": 175000}
      }
    }
    ```
    `clocks` is remaining milliseconds per side, or `null` for an `Unlimited` game. It rides on every `Snapshot` and `DiceRolled`; the side to move keeps ticking, so count down locally between events. On a flag-fall the game ends with `termination: "Timeout"` and the loser's clock at `0`.
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
        "seed": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
      }
    }
    ```
    *(Result can also be `{"Draw":{}}` and termination can be one of `"KingCaptured"`, `"Resign"`, `"Draw"`, `"Aborted"`, `"Timeout"`.)*
    `seed` is the revealed server seed (hex): `SHA-256(seed)` equals the `commit` published in the create response, so anyone can open the dice commitment after the game.
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
On each `DiceRolled` event where the `seat` matches your bot, compute the best turn path and submit it.

### 5. Submit a Turn (Terminal 2)
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
