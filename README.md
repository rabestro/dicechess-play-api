# Dice Chess Play API 🎲♟️

[![CI Pipeline](https://github.com/rabestro/dicechess-play-api/actions/workflows/ci.yaml/badge.svg)](https://github.com/rabestro/dicechess-play-api/actions/workflows/ci.yaml)
[![Play Live](https://img.shields.io/badge/Play-Live-success)](https://play.jc.id.lv/)
[![Bot API Docs](https://img.shields.io/badge/Docs-Bot%20API-orange)](https://jc.id.lv/dicechess-play-api/)
[![Leaderboard](https://img.shields.io/badge/Bots-Leaderboard-blue)](https://play.jc.id.lv/leaderboard)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-lightgrey)](./LICENSE)

Authoritative real-time server for **Dice Chess** — human-vs-human play, a third-party
**Bot API**, and an automatic **Glicko-2 rating ladder**. Phase 3 of the play platform: the
pivot from client-authoritative (vs-bot, phases 1–2 in
[`dicechess-play`](https://github.com/rabestro/dicechess-play)) to a server that owns the
truth. **Live in production** at [play-api.jc.id.lv](https://play-api.jc.id.lv/health),
pairing the bots on the [leaderboard](https://play.jc.id.lv/leaderboard) around the clock.

> **Status: live.** Authoritative HvH over WebSocket, the full Bot API (REST + ndjson event
> streams + webhooks), Postgres durability with crash recovery, analytics hand-off, and a
> continuously-paired Glicko-2 rating ladder have all shipped and run in production. Design
> records: ADR-0007 (server authority), ADR-0008 (dice fairness), ADR-0009 (Bot API) in the
> `dicechess-docs` vault. What's live vs. planned is spelled out [below](#status-whats-live).

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

Third-party bots connect via a dedicated, Lichess-shaped API — a token plus any of three
connection modes: REST polling, an ndjson event stream, or a single serverless **webhook**
(the server POSTs each turn, the HTTP response is the move). Language-agnostic and
reconnect-safe. Our own engine bots dogfood the exact same API and provide always-online
opponents; anyone can register a bot, self-test it, and opt into the rating ladder.

- **Docs:** <https://jc.id.lv/dicechess-play-api/> — quickstart, REST/stream/webhook
  reference, DFEN, the legal-move tree, and the provably-fair verification procedure.
- **Starters** (fork and run): [Python](https://github.com/rabestro/dicechess-bot-python),
  [TypeScript](https://github.com/rabestro/dicechess-bot-typescript) (both MIT, no engine),
  and [Scala](https://github.com/rabestro/dicechess-bot-scala) (engine-optional, on the
  shared [`dicechess-bot-runtime`](https://github.com/rabestro/dicechess-bot-runtime)).

## Status: what's live

Shipped and running in production:

- **Authoritative game core** — the server owns dice, clocks, and move legality (validated
  through the JVM engine); clients only send intents.
- **Human vs human** over WebSocket, end-to-end.
- **Bot API** — REST + ndjson event streams + webhooks; anonymous (ephemeral) and registered
  (durable) tokens. Full reference at <https://jc.id.lv/dicechess-play-api/>.
- **Durability** — Postgres `play` schema (Flyway `V1`–`V7`) with crash recovery; opt-in via
  `PLAY_DB_URL` (unset = in-memory dev mode, see [Running](#running)).
- **Analytics hand-off** — finished games flow to
  [`dicechess-analytics`](https://github.com/rabestro/dicechess-analytics) via a transactional
  outbox.
- **Rating ladder** — a continuously-paired, mirrored-dice scheduler with Glicko-2 ratings, a
  public [leaderboard](https://play.jc.id.lv/leaderboard), and per-bot profiles. Opt-in via
  `LADDER_INTERVAL_SECONDS`/`RATING_INTERVAL_SECONDS` — see [Running](#running).
- **Open seek lobby** — bots and humans meet and start games.

Planned: the doubling cube; a dedicated WebSocket edge tier + Redis pub/sub for horizontal
scale; formal cross-team tournaments (brackets / round-robin) layered on the ladder. The
detailed milestone roadmap lives in the `dicechess-docs` vault.

## Stack

Scala 3 · cats-effect · fs2 · http4s · doobie · Circe · PostgreSQL · the dice-chess engine
(JVM). Same toolchain as [`dicechess-analytics`](https://github.com/rabestro/dicechess-analytics).

## Running

Local (JVM) — reads `GITHUB_TOKEN` via the `gh` CLI for the engine artifact:

```bash
sbt run                      # serves on :8080 (in-memory — no DB needed)
curl localhost:8080/health   # {"status":"ok","version":"dev-<sha>"}
```

By default `sbt run` starts fully in-memory: no database, no analytics, no ladder — perfect
for local development, and a restart drops live games. Every persistent or outbound feature
is **opt-in via env vars** (`PLAY_DB_URL` for durability, `INGEST_URL`/`INGEST_TOKEN` for
analytics, `PLAY_BOT_TOKENS` for static bots, `LADDER_INTERVAL_SECONDS` for automatic ladder
pairing, `RATING_INTERVAL_SECONDS` for Glicko-2 updates, `WEBHOOK_TIMEOUT_SECONDS` for bot
webhook push) — see the deploy section below. Leaving any of these unset disables that one
feature **silently**: the server starts clean and `/health` stays green, the feature just never
does anything. When standing up a new deployment, confirm the ladder is actually alive with a
live check — `GET /games` becomes non-empty and `/leaderboard` counts increase within a
minute — not just `/health`.

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

**Endpoints:** `GET /health` · `GET /version` · the human game surface (`POST /games`, `GET /games/{id}`, `GET /games/{id}/ws?token=…`) · public discovery (`GET /games`, `GET /leaderboard`, `GET /bots/{team}/{name}`) · and the full Bot API under `/bot/…` (identity, challenges, seeks, gameplay, streams, webhooks, ladder). The **complete, authoritative reference** — every route, payload, and the provably-fair procedure — is the docs site: **<https://jc.id.lv/dicechess-play-api/>**.

**Anonymous bots:** `POST /bot/anon?name=…` mints an ephemeral, **unranked** Bearer token bound to `bot:team:anon:<uuid>` — zero registration, so a third party can point a bot at the API and test in minutes (challenge a house bot, or self-play). Tokens are in-memory with a TTL (expired entries pruned), and minting is **per-IP rate-limited** (`429` + `Retry-After`; the client IP is read from the Cloudflare tunnel's `CF-Connecting-IP`). Registered, durable identities come from `POST /bot/register` (or static `PLAY_BOT_TOKENS`), and only they can hold webhooks and join the ladder.

## License

[AGPL-3.0](./LICENSE) — inherited from the dice-chess engine this server links.
