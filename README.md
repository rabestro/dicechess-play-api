# Dice Chess Play API 🎲♟️

[![CI Pipeline](https://github.com/rabestro/dicechess-play-api/actions/workflows/ci.yaml/badge.svg)](https://github.com/rabestro/dicechess-play-api/actions/workflows/ci.yaml)
[![Play Live](https://img.shields.io/badge/Play-Live-success)](https://play.jc.id.lv/)
[![Bot API Docs](https://img.shields.io/badge/Docs-Bot%20API-orange)](https://bots.jc.id.lv/)
[![Contributor Docs](https://img.shields.io/badge/Docs-Contributor-blue)](https://jc.id.lv/dicechess-play-api/)
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

- **Docs:** <https://bots.jc.id.lv/> — quickstart, REST/stream/webhook
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
  (durable) tokens. Full reference at <https://bots.jc.id.lv/>.
- **Durability** — Postgres `play` schema (Flyway `V1`–`V7`) with crash recovery; opt-in via
  `PLAY_DB_URL` (unset = in-memory dev mode, see [Running](#running)).
- **Analytics hand-off** — finished games flow to
  [`dicechess-analytics`](https://github.com/rabestro/dicechess-analytics) via a transactional
  outbox; the SPA's own in-browser bot games are accepted at `POST /ingest/games` (#212) and
  relayed through the same deliverer.
- **Rating ladder** — a continuously-paired matchmaking scheduler with Glicko-2 ratings, a
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
analytics, `PLAY_BOT_TOKENS` for static bots, `LADDER_INTERVAL_SECONDS` (plus optional
`LADDER_MAX_CONCURRENT_GAMES`, default `8`) for automatic ladder pairing,
`RATING_INTERVAL_SECONDS` (plus optional `RATING_BATCH_SIZE`, default `100`, and
`LADDER_TIMEOUT_PARK_GAMES`, default `4`) for Glicko-2 updates and ladder auto-park,
`WEBHOOK_TIMEOUT_SECONDS` for bot webhook push, `PLAY_OPEN_TO_HUMANS` for the human-catalog
roster) — see the deploy section below. Leaving any of these unset disables that one feature
silently: the server still boots clean and `/health` still returns 200, it just never does
anything. When standing up a new deployment, confirm the ladder is actually alive with a
live check — `GET /games` becomes non-empty and `/leaderboard` counts increase within a
minute — not just `/health`.

`STRENGTH_ELO0`/`STRENGTH_ELO1`/`STRENGTH_ALPHA`/`STRENGTH_BETA`/`STRENGTH_BOOTSTRAP_ITERATIONS`
and `STRENGTH_REFRESH_INTERVAL_SECONDS` are different: they only ever *tune* the `/strength`
SPRT/Bradley-Terry report, each falling back to its own default when unset — none of them
disable anything. The report itself is populated by the rating batch, so it only ever has data
while `RATING_INTERVAL_SECONDS` is set. `STRENGTH_REFRESH_INTERVAL_SECONDS` (default `900`)
is how often that report may be rebuilt (#215): each rebuild folds the entire rated history
`STRENGTH_BOOTSTRAP_ITERATIONS` times over, so it deliberately runs far slower than the batch
poll it rides on.

### Retention (#179)

`RETENTION_INTERVAL_SECONDS` enables a periodic prune of the tables that would otherwise
grow forever: ended `games` snapshots and delivered `outbox`/`client_reports` rows. A delivered
delivery row (`outbox` or `client_reports`) has simply done its job and is pruned by age alone;
an ended snapshot is dead weight only once `game_archive` holds the history and
`GET /games/{id}/history` serves replay from it — nothing reads an ended snapshot after that
(boot resume loads `WHERE status='active'`). Ended snapshots are also the only place per-seat join tokens persist after a
game, so keeping them forever is a small standing liability, not just bytes.

Unset means **nothing is ever deleted** — deliberately, since this is the only scheduled task
that removes data. Knobs: `RETENTION_DAYS` (default `30`) and `RETENTION_BATCH_SIZE`
(default `1000`); a non-positive or unparseable value for either falls back to its default
rather than being honoured (a `RETENTION_DAYS=0` typo would otherwise prune a game the instant
it ended).

Never pruned: `game_archive` (permanent by contract), `game_results`, `bots`, `bot_webhooks`,
anything still active regardless of age, parked outbox and `client_reports` rows
(`failed_permanently`) and the snapshots the outbox foreign key pins — and, as a safety valve,
**any ended non-aborted game with no archive row**. That last case means the snapshot is the only surviving copy of that game's
history, so the pass retains it and reports the count in its log line instead of destroying it.
An aborted game *is* pruned: `GameArchive.payload` excludes it by design, so there is no history
to preserve.

One log line per pass, only when something happened:

```text
[play][retention] cutoff 2026-07-01T…Z: pruned 1000 outbox row(s), 1000 ended snapshot(s), 0 client report(s)
```

Run the archive backfill (below) **before** enabling retention on a deployment that predates
`game_archive` — otherwise the safety valve simply retains everything and the prune reclaims
nothing.

Container — the engine artifact needs a `read:packages` token, passed as a BuildKit secret so it never lands in a layer:

```bash
GITHUB_TOKEN=$(gh auth token) DOCKER_BUILDKIT=1 docker build \
  --secret id=github_token,env=GITHUB_TOKEN --build-arg GITHUB_ACTOR="$USER" \
  -t dicechess-play-api .
IMAGE=dicechess-play-api scripts/smoke-test.sh   # boots the image, asserts it serves (no DB)
```

CI publishes a multi-arch image to `ghcr.io/rabestro/dicechess-play-api` on every push to `main` (build → smoke → push). Deploy on the homelab with `docker-compose.yaml` — set `PLAY_BOT_TOKENS` (and pin `API_TAG=vX.Y.Z`) in `.env`; the API listens on host port `8040`.

The browser play-site calls the API cross-origin, so CORS is enabled. By default any origin may read it, without credentials. Set `PLAY_CORS_ORIGINS` to a comma-separated allow-list of full origins (e.g. `https://play.jc.id.lv,http://localhost:5173`) to restrict it — a non-empty list also enables credentialed CORS, which the account session cookie (ADR-0017) requires; the allow-all default stays deliberately credential-less.

Google sign-in (`/auth/*`, #233) is opt-in and all-or-nothing: it mounts only when persistence plus `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, and `PLAY_SESSION_SECRET` are all set (`PLAY_FRONTEND_URL` defaults to the production SPA). A partial Google configuration warns loudly at boot instead of half-enabling.

### Public deploy via Cloudflare Tunnel

The API is published at `play-api.jc.id.lv` with a Cloudflare Tunnel — automatic TLS + WebSocket, no port-forwarding, origin IP hidden. The `tunnel` service in `docker-compose.yaml` runs `cloudflared`; the public hostname is configured once in the Cloudflare dashboard.

1. **Create the tunnel** (Cloudflare → Zero Trust → Networks → Tunnels → Create → Cloudflared, env *Docker*). Copy the **tunnel token**.
2. **Add a public hostname** to the tunnel: `play-api` . `jc.id.lv` → type **HTTP** → URL **`api:8080`** (the `api` compose service, internal port). Cloudflare creates the proxied DNS record.
3. **`.env`** on the host:
   ```
   API_TAG=latest                 # or a pinned vX.Y.Z
   PLAY_CORS_ORIGINS=https://play.jc.id.lv,https://dicechess-play.pages.dev
   CF_TUNNEL_TOKEN=eyJ...         # account-scoped — never commit
   # Google sign-in (ADR-0017) — all four required to enable /auth/*:
   # GOOGLE_CLIENT_ID=...
   # GOOGLE_CLIENT_SECRET=...    # never commit
   # GOOGLE_REDIRECT_URI=https://play-api.jc.id.lv/auth/callback
   # PLAY_SESSION_SECRET=...     # e.g. openssl rand -base64 48 — never commit
   # PLAY_BOT_TOKENS=team|name|token
   # PLAY_OPEN_TO_HUMANS=gcp|expectimax-onnx-3|ONNX expectimax v3, with book   # ;-separated; opens bots to the human catalog
   # PLAY_RATED_FOR_HUMANS=gcp|expectimax-onnx-3   # ;-separated; operator-only — makes human games against these bots rated
   ```
4. `docker compose pull && docker compose up -d`, then `curl https://play-api.jc.id.lv/health`.
5. **Client:** set `VITE_PLAY_API_URL=https://play-api.jc.id.lv` in the Cloudflare Pages project (Production) and redeploy; the client derives `wss://…` for the game socket.

**Endpoints:** `GET /health` · `GET /version` · the human game surface (`POST /games`, `GET /games/{id}`, `GET /games/{id}/ws?token=…`) · public discovery (`GET /games`, `GET /leaderboard`, `GET /bots/{team}/{name}`) · and the full Bot API under `/bot/…` (identity, challenges, seeks, gameplay, streams, webhooks, ladder). The **complete, authoritative reference** — every route, payload, and the provably-fair procedure — is the docs site: **<https://bots.jc.id.lv/>**.

**Anonymous bots:** `POST /bot/anon?name=…` mints an ephemeral, **unranked** Bearer token bound to `bot:team:anon:<uuid>` — zero registration, so a third party can point a bot at the API and test in minutes (challenge a house bot, or self-play). Tokens are in-memory with a TTL (expired entries pruned), and minting is **per-IP rate-limited** (`429` + `Retry-After`; the client IP is read from the Cloudflare tunnel's `CF-Connecting-IP`). Registered, durable identities come from `POST /bot/register` (or static `PLAY_BOT_TOKENS`), and only they can hold webhooks and join the ladder.

### Owner-run maintenance tasks

Neither task is an endpoint and neither runs on its own — both are operator actions against a
database, with `PLAY_DB_URL`/`PLAY_DB_USER`/`PLAY_DB_PASSWORD` set in the environment.

```bash
mise run ladder:report            # read-only: SPRT + Bradley-Terry strength report (#120)
mise run archive:backfill         # WRITES: one-off game_archive backfill (#199)
```

`archive:backfill` writes the `game_archive` rows (#177) that games finished *before* the archive
existed never got — without it, the replay page (dicechess-play#163) answers "history unavailable"
for every one of them, even though their per-turn history is still in `play.games.snapshot`.
It reuses `GameArchive.payload`, so a back-filled row is identical to a natively written one, and
takes `finished_at` from `game_results` rather than the column default — the replay page shows that
field, so stamping it with the backfill time would date every game to the day of the run.

Batch size is the first argument (`sbt "runMain …ArchiveBackfillMain 1000"`, default 500). Each row
commits on its own and inserts `ON CONFLICT DO NOTHING`, so the run is safe to interrupt and safe to
repeat — a re-run skips what exists and reports `+0`. Progress is one line per batch plus a final
`scanned / inserted / skipped` summary; a non-zero `skipped` is expected and correct, since aborted
games are deliberately never archived.

**Run it before the snapshot-retention pass (#179)** — that prune is what makes the missing history
unrecoverable. Verify afterwards with:

```sql
SELECT count(*) FROM play.games g
WHERE g.status = 'ended'
  AND NOT EXISTS (SELECT 1 FROM play.game_archive a WHERE a.game_id = g.id);
```

Whatever remains should be only aborted games.

## License

[AGPL-3.0](./LICENSE) — inherited from the dice-chess engine this server links.
