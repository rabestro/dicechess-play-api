# dicechess-play-api

Authoritative real-time server for **Dice Chess** — human-vs-human play, the doubling
cube, and a third-party **Bot API**. This is **phase 3** of the play platform: the pivot
from client-authoritative (vs-bot, phases 1–2 in [`dicechess-play`](https://github.com/rabestro/dicechess-play))
to a server that owns the truth.

> **Status: 3a complete** (authoritative game core + human WebSocket transport); **3b in
> progress** — transport hardening done (fan-out, fiber supervision, seat auth, turn
> deadline); Bot API and durability under way. Design: ADR-0007 (server authority),
> ADR-0008 (dice fairness), ADR-0009 (Bot API & tournaments) in the `dicechess-docs` vault.

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

## Running

Local (JVM) — reads `GITHUB_TOKEN` via the `gh` CLI for the engine artifact:

```bash
sbt run                      # serves on :8080
curl localhost:8080/health   # {"status":"ok","version":"dev"}
```

Container — the engine artifact needs a `read:packages` token, passed as a BuildKit secret so it never lands in a layer:

```bash
GITHUB_TOKEN=$(gh auth token) DOCKER_BUILDKIT=1 docker build \
  --secret id=github_token,env=GITHUB_TOKEN --build-arg GITHUB_ACTOR="$USER" \
  -t dicechess-play-api .
IMAGE=dicechess-play-api scripts/smoke-test.sh   # boots the image, asserts it serves (no DB)
```

CI publishes a multi-arch image to `ghcr.io/rabestro/dicechess-play-api` on every push to `main` (build → smoke → push). Deploy on the homelab with `docker-compose.yaml` — set `PLAY_BOT_TOKENS` (and pin `API_TAG=vX.Y.Z`) in `.env`; the API listens on host port `8040`.

The browser play-site calls the API cross-origin, so CORS is enabled. By default any origin may read it (safe here — the API uses no cookies; tokens travel explicitly, so there are no ambient credentials to leak). Set `PLAY_CORS_ORIGINS` to a comma-separated allow-list of full origins (e.g. `https://play.jc.id.lv,http://localhost:5173`) to restrict it.

### Public deploy via Cloudflare Tunnel

The API is published at `play-api.jc.id.lv` with a Cloudflare Tunnel — automatic TLS + WebSocket, no port-forwarding, origin IP hidden. The `tunnel` service in `docker-compose.yaml` runs `cloudflared`; the public hostname is configured once in the Cloudflare dashboard.

1. **Create the tunnel** (Cloudflare → Zero Trust → Networks → Tunnels → Create → Cloudflared, env *Docker*). Copy the **tunnel token**.
2. **Add a public hostname** to the tunnel: `play-api` . `jc.id.lv` → type **HTTP** → URL **`api:8080`** (the `api` compose service, internal port). Cloudflare creates the proxied DNS record.
3. **`.env`** on the host:
   ```
   API_TAG=latest                 # or a pinned vX.Y.Z
   PLAY_CORS_ORIGINS=https://play.jc.id.lv,https://dicechess-play.pages.dev
   CF_TUNNEL_TOKEN=eyJ...         # account-scoped — never commit
   # PLAY_BOT_TOKENS=team|name|token
   ```
4. `docker compose pull && docker compose up -d`, then `curl https://play-api.jc.id.lv/health`.
5. **Client:** set `VITE_PLAY_API_URL=https://play-api.jc.id.lv` in the Cloudflare Pages project (Production) and redeploy; the client derives `wss://…` for the game socket.

**Endpoints:** `GET /health`, `GET /version`, `POST /games`, `GET /games/{id}`, `GET /games/{id}/ws?token=…`, and the Bot API under `/bot/…` (`POST /bot/anon`, `/bot/account`, `/bot/stream/event`, `POST /bot/challenge`). See [Bot API Reference](docs/bot-api.md) for the complete integration guide and payload schemas.

**Anonymous bots:** `POST /bot/anon?name=…` mints an ephemeral, **unranked** Bearer token bound to `bot:team:anon:<uuid>` — zero registration, so a third party can point a bot at the API and test in minutes (challenge a house bot, or self-play). Tokens are in-memory with a TTL (expired entries pruned), and minting is **per-IP rate-limited** (`429` + `Retry-After`; the client IP is read from the Cloudflare tunnel's `CF-Connecting-IP`). Static/official bots stay on `PLAY_BOT_TOKENS`.

> Game state is **in-memory** for now — a restart drops live games. Durability (Postgres `play` schema) lands later in 3b.

## License

[AGPL-3.0](./LICENSE) — inherited from the dice-chess engine this server links.
