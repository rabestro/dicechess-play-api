# AGENTS.md

Authoritative real-time Dice Chess server: human-vs-human over WebSocket plus a public
Lichess-shaped Bot API — the server side of the dicechess play platform.

## Project context

- Public repository, AGPL-3.0. Scala 3 on Java 25, cats-effect/fs2/http4s (Ember), sbt.
- Server-authoritative: rooms own dice, clocks, and move legality. Clients only send intents.
- Cross-repo contracts:
  - **Consumes** `lv.id.jc:dicechess-engine-scala` (JVM artifact from GitHub Packages; version
    pinned in `build.sbt`) — the single source of truth for rules; never reimplement legality.
  - **Publishes** the client wire protocol in `src/main/scala/dicechess/play/wire/Codecs.scala`,
    consumed by the `dicechess-play` SvelteKit SPA — verify both sides when changing it.
  - **Publishes** the analytics ingest payload built in `ingest/PlaysiteIngest.scala` (POST to
    dicechess-analytics `/api/games`, `source=playsite`, idempotent first-writer-wins).
  - **Accepts** browser-submitted game reports at `POST /ingest/games` (#212) from the
    `dicechess-play` SPA (its games against in-browser bots) and relays them to analytics —
    the same wire contract, structurally validated at ingress; reports land ONLY in the
    `client_reports` relay queue, never in `game_results`/`game_archive`/`/history`.
  - **Publishes** the public Bot API documented in `docs/bot-api.md`; first-party consumers:
    `rabestro/dicechess-reference-bot` and `docs/examples/random_bot.py`.
- CD publishes a multi-arch image to `ghcr.io/rabestro/dicechess-play-api`.

## Architecture map

Single sbt module. Entry point: `dicechess.play.Main` (IOApp.Simple, Ember on `0.0.0.0:8080`),
which wires opt-in persistence and ingest from env vars. Under `src/main/scala/dicechess/play/`:

- `core/` — domain types: `Protocol`, `Identity` (Seat/Principal), `GameId`, `Seek`, `BotEvent`.
- `dice/DiceSource.scala` — server-only CSPRNG dice with commit-reveal fairness
  (SHA-256 commitment, HMAC-SHA256 rolls with client entropy, length-prefixed framing).
- `game/` — `GameRoom` (actor-style room; see concurrency doctrine below), `EngineOps`
  (the only engine wrapper), `PlayerConnection` (transport-agnostic player handle).
- `server/` — http4s routes and services: `HealthRoutes` (/health, /version), `PlayRoutes`
  (/games + /games/{id}/ws), `LobbyRoutes` (/lobby/seeks), `BotRoutes` (/bot/*),
  `IngestRoutes` (/ingest/games, browser report intake), `AuthRoutes` (/auth/*, Google sign-in,
  #233), `MeRoutes` (guest claims + merged history, #236), plus `GameRegistry`, `Lobby`,
  `Challenges`, `BotAuth`, `AuthSession`, `GoogleAuth`, `Nicknames`, `BotEvents`,
  `AnonMintLimiter`, `SeatGuard`, `Cors`.
- `store/` — `GameStore`/`PgGameStore`: doobie + Flyway, jsonb snapshots; migrations in
  `src/main/resources/db/migration/` (V1 games, V2 outbox, V3 bots, ..., V11 client_reports,
  V12 per-bot `max_concurrent_games`, V13 webhook delivery telemetry, V14 user accounts, V15 human rating state).
- `ingest/` — `PlaysiteIngest` + `IngestDeliverer`: transactional outbox → analytics, plus a
  second deliverer instance draining browser reports from `client_reports` (#212).
- `wire/Codecs.scala` — Circe codecs; the wire contract.

## Commands

Prerequisites first:

- **GitHub auth before any sbt command**: run `gh auth login` once (or export `GITHUB_TOKEN`).
  The engine artifact resolves from GitHub Packages, which requires auth even for public
  packages; `build.sbt` reads the token via `gh auth token`. Failure signature:
  `unresolved dependency: lv.id.jc#dicechess-engine-scala...` — missing auth, not a broken build.
- Toolchain via mise (`mise.toml`): Java temurin-25, native scalafmt 3.11.1, lefthook,
  betterleaks, gh, jq.
- Docker is needed only for two test suites (see Testing). On Rancher Desktop export
  `DOCKER_HOST=unix://$HOME/.rd/docker.sock` and `TESTCONTAINERS_RYUK_DISABLED=true` —
  otherwise `mise run test`/`check` hangs at container startup.

```bash
mise run setup      # install tools (brew sbt) + register lefthook Git hooks
mise run compile    # sbt compile Test/compile
mise run test       # sbt test (full suite; needs Docker for 2 suites)
mise run check      # CI mirror: scalafmtCheckAll clean coverage test coverageReport
mise run format     # sbt scalafmtAll — git add new .scala files FIRST (skips untracked)
mise run run        # start the server on :8080
sbt "testOnly dicechess.play.server.*"   # targeted, Docker-free
```

Also: `mise run coverage`, `format:check`, `clean`, `hook:install`, `hook:run`. Docker image
build uses a BuildKit secret (see README); smoke test: `IMAGE=... scripts/smoke-test.sh`.

Server env vars (all opt-in): `PLAY_DB_URL`/`PLAY_DB_USER`/`PLAY_DB_PASSWORD` (persistence;
unset = fully in-memory, restart drops everything), `INGEST_URL` (the FULL endpoint URL) +
`INGEST_TOKEN` (outbox delivery to analytics), `PLAY_BOT_TOKENS` (`team|name|token` CSV),
`PLAY_CORS_ORIGINS` (empty = allow any), `APP_VERSION` (surfaced at GET /version),
`LADDER_INTERVAL_SECONDS` (+ optional `LADDER_MAX_CONCURRENT_GAMES`, default `8`) — unset
disables automatic ladder pairing entirely, `RATING_INTERVAL_SECONDS` (+ optional
`RATING_BATCH_SIZE`, default `100`, and `LADDER_TIMEOUT_PARK_GAMES`, default `4`) — unset
disables Glicko-2 rating updates **and ladder auto-park** entirely,
`WEBHOOK_TIMEOUT_SECONDS` — unset disables bot webhook push entirely (routes + dispatcher),
`RETENTION_INTERVAL_SECONDS` (+ optional `RETENTION_DAYS`, default `30`, and
`RETENTION_BATCH_SIZE`, default `1000`) — unset disables the retention prune (#179) entirely, so
ended snapshots and delivered outbox rows are kept forever.
`STRENGTH_ELO0`/`STRENGTH_ELO1`/`STRENGTH_ALPHA`/`STRENGTH_BETA`/`STRENGTH_BOOTSTRAP_ITERATIONS`
(#181) — tuning knobs for the `/strength` SPRT/Bradley-Terry report, each falling back to its own
default rather than disabling anything; the report itself is refreshed by the rating batch, so it
is only ever populated while `RATING_INTERVAL_SECONDS` is also set.
`STRENGTH_REFRESH_INTERVAL_SECONDS` (#215, default `900`) — the floor between two rebuilds of that
report, read by the rating batch like `LADDER_TIMEOUT_PARK_GAMES`. A rebuild folds every rated game
ever played `STRENGTH_BOOTSTRAP_ITERATIONS` times, so it must NOT track the batch poll: doing so
pegged a cats-effect worker for a third of all wall-clock time in production. `0` restores the old
rebuild-per-tick behaviour.
Identity on game-start paths (#235, ADR-0017): where a request used to be trusted to name its own guest id
(`POST /games`, `/lobby/seeks`, `/lobby/seeks/{id}/accept`, `/lobby/play-bot`), **the session now wins and the body
field is only an anonymous fallback** — a `user:` principal can never be expressed in a body, and a signed-in caller
cannot be made to act as anyone else. Those fields became optional: required only when there is no session.
A tokenless `GET /games/{id}/ws` also falls back to the session and reconnects a signed-in player to the single seat
they occupy (the fix for a lost `?seat=` URL); two seats held by the same account (friend-by-link before the share
link is used) stays ambiguous, so the join token remains the only way in there.
Human ratings (#247/#248/#238, ADR-0017): accounts and bots share ONE Glicko-2 scale (V15, seeds 1500/350/0.06) —
that is what makes the two populations comparable and what solves cold start. `RatingBatch` rates any mix of the
two, and **eligibility is decided in the batch, not at game creation**: `game_results.rated` records only what the
room was told. The rules, each with its own skip reason: a guest seat is never rated (resetting a guest identity
would make rating free); an account vs a bot counts only if that bot is operator-curated
(`bots.rated_for_humans` via `PLAY_RATED_FOR_HUMANS`, `;`-separated `team|name`), never on the bot's own say-so;
an account vs a bot it OWNS never counts. `applyRatingUpdate` therefore spans two TABLES in one transaction —
atomicity is per game, not per table. Ladder auto-park stays bot-only: a human losing on time is not a dead
endpoint. **Never expose `bots.rated_for_humans` on the bot API**:
its neighbours `on_ladder`/`open_to_humans` are self-service and harmless, but an author who could set THIS one
would register a weak bot and farm rating off it. Both rosters are additive — a typo narrows what is enabled.
Claimed guest history (#236, ADR-0017): `GameResultsStore.playerGamesPage`/`opponentsFor` take a LIST of
external ids — "the requester" is one account plus every guest id it has claimed, and a merged history is a
union at READ time (nothing in `game_results`/`game_archive` is ever rewritten). Self-play exclusion in
`opponentsFor` therefore means "the other seat is also me", which now includes an account vs its own claimed
guest id. The claim set is owner-only: `GET /players/{guestId}/…` must never resolve a guest id to a
nickname, or signing up would retroactively deanonymise that id's past games.
Account deletion (#237, ADR-0017): `DELETE /auth/me` requires the body to echo the account's own nickname —
not CSRF protection (`SameSite=Lax` + a non-simple method already covers that) but a guard against a mis-wired
client irreversibly deleting the wrong account. History is NOT rewritten: identities and guest links cascade
(V14) and the `user:<uuid>` left in `game_results`/`game_archive` simply stops resolving, which anonymises it
without touching immutable records. The freed nickname becomes reusable, and re-signing-in with the same
Google subject mints a FRESH account with no history.
Google sign-in (#233, ADR-0017): `GOOGLE_CLIENT_ID` + `GOOGLE_CLIENT_SECRET` +
`GOOGLE_REDIRECT_URI` + `PLAY_SESSION_SECRET` (all four required, plus persistence) mount the
`/auth/*` routes; `PLAY_FRONTEND_URL` (default `https://play.jc.id.lv`) is where login/callback
redirect back to. A PARTIAL Google config warns loudly at boot instead of the usual silent
absence. With sign-in on, `PLAY_CORS_ORIGINS` must be a non-empty allow-list — that is also what
switches CORS into credentialed mode; the empty allow-all default stays credential-less.

## Quality gates — Definition of Done

- `mise run check` passes locally. It mirrors CI exactly.
- CI (`.github/workflows/ci.yaml`) is **path-filtered** to `src/**`, `build.sbt`, `project/**`,
  `.scalafmt.conf`, and ci.yaml itself. A doc-only PR gets zero checks — that is normal.
  A PR touching only other workflows also gets no CI run; validate those via `gh workflow run`.
- SonarCloud imports the scoverage report (skipped for Dependabot / missing token). No coverage
  minimum is enforced (`coverageFailOnMinimum := false`) — not a license to skip tests.
- `enforce-pr-policy.yaml` validates branch naming and `Closes #n` linking; `cla.yaml` requires
  external contributors to sign `.github/cla-signatures.json` (owner and bots exempt).
- Per-change extras: `wire/Codecs.scala` changed → verify the dicechess-play client codecs
  still match; `dice/DiceSource.scala` or the fairness protocol changed → golden test vectors
  plus the public verification procedure in `docs/bot-api.md` updated in the same PR;
  Bot API routes changed → update `docs/bot-api.md` in the same PR.

## Code conventions

- Scala 3 "fewer braces" style throughout: colon syntax for template bodies and lambdas, no end
  markers. Formatting is law: scalafmt (version/rewrites in `.scalafmt.conf`, maxColumn 120).
- `-Werror -Wunused:all -deprecation -feature -explain`: one unused import fails the build.
- Pure Typelevel FP: everything in cats-effect `IO`. No nulls, no exceptions for control flow —
  return errors as values (e.g. `GameRegistry.create` returns `Left`). `Resource` for
  lifecycles; scope background fibers with `.background`/`.surround` so failures surface.
- **GameRoom concurrency doctrine** (enforced in review): a single consumer fiber is the only
  writer of game state; events fan out via non-blocking `tryOffer` to bounded per-subscriber
  queues (a stalled subscriber is dropped, the room never blocks); rooms depend only on
  `Principal`/`Seat`/`PlayerConnection`, never concrete transports; the server never trusts
  client FEN, dice, clocks, or move legality; dice come only from `DiceSource`.
- Never reimplement rules: `EngineOps` + the engine artifact validate everything; legal moves
  ship on the wire as a prefix tree of UCI micro-moves.
- Comments explain **why**, not what. Zero TODO/FIXME comments exist in `src/` — keep that bar;
  encode decisions as rationale comments instead. 2-space indent everywhere (`.editorconfig`).

## Testing conventions

- munit. Pure logic suites extend `munit.FunSuite`; effectful suites extend
  `munit.CatsEffectSuite`. Four suites add `TestContainerForAll` (postgres:18-alpine) and need
  Docker — `PgGameStoreSuite`, `IngestDelivererSuite`, `RatingBatchSuite`, `HistoryRoutesSuite`;
  everything else is Docker-free.
- **Suites run one at a time** (`Test / parallelExecution := false`, #176). Running the four
  container suites concurrently under scoverage causes real CPU contention — severe enough, measured,
  to delay a cats-effect timer by 133s. Do not re-enable parallel execution to "speed up" CI:
  measured, serial is also the *faster* of the two (30s vs a bimodal 30s/313s), because container
  startup dominates and does not parallelise usefully.
- **A game with an idle seat and no clock deadlocks — do not write tests that wait on one** (#176).
  From the start position only pawns and knights can move, so a roll containing neither makes the
  room auto-pass to the other seat; with `TimeControl.Unlimited` and nobody driving that seat, play
  stops forever. This is dice-dependent, so it flakes at `(4/6)^3 ≈ 30%` and looks like a timeout
  bug — #140 and #176 both misread it as fiber starvation and widened a bound instead. If a test
  needs a specific seat to get an actionable turn, drive the opponent (`BotConnection`, see
  `WebhooksSuite`) rather than assuming the opening roll falls that seat's way.
- Test names are full sentences describing behaviour, e.g. `test("the game-end event reveals
  the server seed")`. Suites are named `<Unit>Suite` and mirror the main package layout.
- Non-flaky patterns (this repo fixed three stream races; follow them): **subscribe before
  acting** — never trigger an event and then attach to the stream; **poll durable state**
  instead of sleeping or racing the live stream; bound every effectful wait with `timeoutTo`.
- Run a single suite Docker-free: `sbt "testOnly dicechess.play.game.GameRoomSuite"`.

## Gotchas

- Warm-cache formatting trap: on a warm `target/`, sbt-scalafmt's incremental cache can skip an
  actually-misformatted file, so a local check passes while CI (fresh checkout) fails. Run scalafmt
  checks after `clean`, or confirm with the native `scalafmt --test <files>`.
- Three scalafmt toolchains must stay in lockstep and be bumped together: `.scalafmt.conf`, the
  native CLI in `mise.toml` (used by lefthook hooks; no version auto-dispatch), and sbt-scalafmt.
- `sbt scalafmtAll` skips untracked files — `git add` new `.scala` files before
  `mise run format`, or the native pre-commit `scalafmt --test` rejects the commit.
- `build.sbt` force-bumps testcontainers-java/docker-java and sets `Test/javaOptions +=
  "-Dapi.version=1.43"` — the wrapper's pinned docker-java speaks a Docker API version rejected
  by modern daemons. Never "simplify" these away.
- The Dockerfile pins `eclipse-temurin:25-*-noble`: the unsuffixed tag drifted to Ubuntu 26.04
  whose uutils coreutils break the sbt-native-packager launcher. Keep the suffix.
- Docker builds pass the GitHub token as a BuildKit secret (`--secret id=github_token`) so it
  never lands in a layer — never convert it to a build-arg.
- `JAVA_OPTS` in Dockerfile/compose carries Java 25 flags cats-effect needs
  (`warnOnNonMainThreadDetected=false`, `--sun-misc-unsafe-memory-access=allow`) — keep them.
- `init: true` on the `api` service is load-bearing, not boilerplate: the ENTRYPOINT execs the JVM,
  so PID 1 in the container is `java`, which never reaps orphans. Without tini every healthcheck
  process reparented to it becomes a permanent zombie (615 on the production host in ~17 h).
- `ThisBuild/version` is frozen at `0.1.0-SNAPSHOT`; real versions come exclusively from git
  tags via the CD workflow (`APP_VERSION` build-arg → GET /version). Do not bump it.
- `PLAY_DB_URL` set without `INGEST_URL`/`INGEST_TOKEN`: finished games and browser reports
  silently accumulate in the outbox/`client_reports` queues (boot warns on stderr); a 4xx from
  analytics parks the row as `failed_permanently`. Note the asymmetry for browser reports
  (#212): the SPA already got its `201` at intake, so a replay-gate rejection is visible only
  in `client_reports.last_error` — never in the client.
- `LADDER_INTERVAL_SECONDS`, `RATING_INTERVAL_SECONDS`, and `WEBHOOK_TIMEOUT_SECONDS` all follow
  an "absence silently disables the feature" idiom, with **no error surfaced anywhere** — the
  server starts clean, `/health` returns 200, but ladder pairing / rating updates / webhook push
  just never happen. A deployment that copies `PLAY_DB_URL`/`INGEST_URL`/`PLAY_BOT_TOKENS` to a
  new host but misses these looks completely healthy while quietly doing nothing (hit this
  moving to a second environment: all three were missed, independently, one at a time). Verify
  a new deployment with a live check — `GET /games` becomes non-empty and `/leaderboard` counts
  increase over a minute — not just `/health`.
- `LADDER_MAX_CONCURRENT_GAMES` (#190) replaces `LADDER_MAX_CONCURRENT_PAIRS`, and
  `LADDER_TIMEOUT_PARK_GAMES` replaces `LADDER_TIMEOUT_PARK_PAIRS` — both because a "pair" was two
  games, so the unit they count changed. **An old name left in place is IGNORED, not translated**:
  the new default applies instead. Only the old *defaults* happen to map onto the new ones (`4`
  pairs = `8` games; `2` pairings = `4` games), so a deployment that had tuned either away from its
  default must rename the var AND double the value — `LADDER_MAX_CONCURRENT_PAIRS=2` meant 4 games
  but now silently yields 8. `Main.warnLegacyLadderVars` logs a loud line at boot for each old name
  still present, precisely so this isn't discovered from behaviour.
- `LADDER_MAX_CONCURRENT_GAMES` is a **server-wide** ceiling and says nothing about any one bot; the
  per-bot limit is `bots.max_concurrent_games` (#189), which **defaults to 1** and is not configurable
  by env at all. Deploying V12 therefore slows the existing ladder down on purpose: with N on-ladder
  bots that never raised their limit, at most `floor(N / 2)` games run at once no matter how high the
  server cap is. That is the intended trade (honest clocks over throughput) — the fix for a bot that
  can genuinely do more is `POST /bot/capacity`, not raising the server cap. A bot in the human catalog
  additionally gives the ladder only `limit - 1` of its slots (floor 1), so a catalog bot at the default
  is paired *and* reachable by a person only one at a time. Static (`PLAY_BOT_TOKENS`) and anonymous
  bots have no row and stay unbounded.
- Webhook delivery telemetry (#225, `bot_webhook_stats`) is Postgres-only, but `Webhooks` itself never
  branches on that — `WebhookStatsStore.noop` is the always-present default, so `deliverTurn` classifies
  and enqueues every attempt in memory-only mode too; the writes just go nowhere. `GET
  /bot/webhook/stats` is the part that is actually gated: 404 without persistence, same idiom as the
  leaderboard/catalog. Recording is fire-and-forget through a bounded queue (`Webhooks.statsLoop`,
  started alongside the delivery loop) — deliberately NOT inline in `deliverTurn`, so a slow or failing
  stats write can never add latency to a turn or touch the room's own clock; queue overflow drops the
  event with one log line rather than blocking. This is the same "report it back" half of #189's load
  contract that #189 itself deferred — `activeGames` in `GET /bot/capacity` answers "am I busy with my
  own declared limit", this answers "is my endpoint actually broken".
- Retention (#179) will not reclaim anything on a deployment whose games predate `game_archive`,
  and this looks like the feature not working: the pass refuses to prune an ended non-aborted
  snapshot that has no archive row, because that snapshot is then the only copy of the game's
  history. Run `mise run archive:backfill` (#199) first; the retained count is in the log line.
- `LADDER_TIMEOUT_PARK_GAMES` (#150) is a `LADDER_*` knob read by the **rating** batch, so it does
  nothing unless `RATING_INTERVAL_SECONDS` is also set: with rating updates off, a dead bot is
  never auto-parked and keeps bleeding rating while inflating every opponent it is paired with.
  The name follows the feature (the ladder), not the component that hosts the check. Renamed from
  `LADDER_TIMEOUT_PARK_PAIRS` by #190 — see the rename gotcha above for why an unrenamed old value
  is not silently equivalent.
- README status banner, the "in-memory for now" callout, and the roadmap placement of the seek
  lobby are stale — durability and the lobby shipped. Trust the code and `docs/bot-api.md`.
- The house bot that opposes quickstart users is deployed outside this repo (via
  `PLAY_BOT_TOKENS`) — it is not in this compose file.
- `.mcp.json` configures a SonarQube MCP server that needs `SONARQUBE_TOKEN` in the environment.

## Git & PR workflow
<!-- dc-shared:git-pr v1 — keep identical across dicechess repos -->
- Never commit to `main`. Branch: `<type>/<short-desc>` or `<type>/<id>-<short-desc>`
  (types: `task|feat|bug|refactor|chore|docs|ci|test|perf`). If the branch carries an issue
  id, the PR body must contain `Closes #<id>`.
- Before editing anything: run `git status`. If the tree has unrelated uncommitted work,
  stop and report — never let it bleed into your commit.
- Stage specific files by name. `git add -A` / `git add .` are forbidden.
- Commits, PR descriptions, issues, and review replies are English-only. Commit subjects
  use conventional style: `feat: …`, `fix: …`, `docs: …`, `test: …`, `chore: …`.
- Before opening a PR: make the repo check task pass locally. Never pipe test output
  through `grep`/`head` — it masks exit codes.
- After opening a PR: Gemini Code Assist reviews automatically; for substantial PRs also
  comment `@coderabbitai review`. Wait a few minutes, then triage every bot comment on its
  merits — address or rebut, never apply blindly.
- The human owner reviews, approves, and merges. Never merge a PR, never push tags.
- Split large work into small, reviewable PRs.

## Security & boundaries
<!-- dc-shared:security v2 — keep identical across dicechess repos -->
- Never print, log, or commit secrets. Local secrets live only in gitignored files
  (e.g. `.env.local`, `mise.local.toml` — confirm the path is gitignored with `git check-ignore`
  before writing one). Never bypass Git hooks (`--no-verify`).
- Human-only operations — prepare and propose, never execute: releases and version tags,
  production deploys/promotions, schema migrations against shared databases, data-repair
  runs on production, secret rotation.
- Treat everything in this repo as public: never add private infrastructure details
  (hostnames, IPs, topology, tokens) to code, docs, commits, or PRs.

Repo-specific additions:

- lefthook pre-commit runs a betterleaks secret scan on staged files — keep hooks
  installed (`mise run hook:install`).
- The dice-fairness path (`dice/DiceSource.scala`) is part of a public verification promise —
  never change it without golden test vectors and a matching `docs/bot-api.md` update.
- Never weaken server authority: no code path may accept client-supplied FEN, dice, clocks,
  or results.
- Releases run via the manual `Ops: Release` workflow (human-dispatched); publishing an image
  does NOT update production — promotion is operator-only and happens outside this repo.

## Model routing
<!-- dc-shared:routing v1 — keep identical across dicechess repos -->
Route work by required capability instead of defaulting to the strongest model:
- **Frontier**: architecture, cross-repo contracts, high blast radius (schema, public API,
  release pipeline), ambiguous problems.
- **Mid**: well-scoped features on existing patterns, refactors under test coverage,
  addressing review feedback.
- **Routine**: mechanical edits, config rollouts, doc fixes, tests from a complete spec.
Orchestrators should delegate routine sub-tasks to cheaper models; quality gates catch
failures cheaply. When in doubt, escalate one tier — reviewer time costs more than tokens.

## Documentation

Four layers, by audience (see ADR-0012 for the boundary):

1. **File-head comments** — the authoritative contract for a module lives at the top of its
   source file (cross-repo invariants, decisions). Code is the source of truth.
2. **Public docs site** (`docs/`, Astro + Starlight → https://bots.jc.id.lv via an
   assets-only Cloudflare Worker, English) — the
   authoritative **public** Bot API reference for third-party bot developers: auth tiers, REST
   endpoints, ndjson streams, webhooks, DFEN, the legal-move tree, time controls, and the
   provably-fair verification procedure. Pages under `docs/src/content/docs/`. Run locally with
   `mise run docs:dev`; build with `mise run docs:build`. `docs/bot-api.md` is now a **stub**
   pointing here (kept because AGENTS.md and external links reference the path).
3. **Contributor docs site** (`contributor-docs/`, Astro + Starlight → GitHub Pages at
   https://jc.id.lv/dicechess-play-api, English) — how the server is built, for people changing
   it: architecture and package map, database schema, concurrency doctrine, configuration,
   development setup, testing conventions. Pages under `contributor-docs/src/content/docs/`.
   Run locally with `mise run contrib-docs:dev`; build with `mise run contrib-docs:build`.
   This slot used to serve the Bot API docs, so `astro.config.mjs` carries **redirects** from
   the old bot-doc slugs to bots.jc.id.lv — extend them if a bot-docs page is ever renamed.
   **Public**: GitHub Pages has no private mode, so nothing may go here that is not already
   derivable from this public repo (no host topology, no env values).
4. **Wiki** (`dicechess-docs` vault, Russian, PRIVATE) — internal design docs, ADRs, roadmap:
   ADR-0007 (server authority), ADR-0008 (dice fairness), ADR-0009 (Bot API), ADR-0012 (docs
   sites). Reference ADRs by number in public docs; never link to the vault.

- Other in-repo docs: `README.md` (orientation; status sections stale, see Gotchas),
  `CONTRIBUTING.md`, `SECURITY.md`, `CLA.md`.
- Update-trigger map: `/bot` routes or protocol semantics changed → the matching **docs site**
  page under `docs/src/content/docs/` (REST → `reference/rest.md`, streams →
  `reference/streaming.md`, webhooks → `reference/webhooks.md`) **AND the machine-readable specs**
  (`docs/public/openapi.yaml` for REST, `docs/public/asyncapi.yaml` for the streams) — these are
  served for client codegen and the OpenAPI is rendered into the `/api/**` reference by
  `starlight-openapi` at build (an invalid spec fails the build, so it can't drift silently); dice
  protocol → `provably-fair.md` (+ `examples/random_bot.py` if affected); a new public API surface
  → a new site page + sidebar entry in `astro.config.mjs`; server env vars or compose → README
  run/deploy sections **AND `contributor-docs/.../configuration.md`**; `wire/Codecs.scala` →
  coordinate with dicechess-play; a new Flyway migration → `contributor-docs/.../database.md`
  (the narrative "why"; the column-level reference is generated in CI); architecture, testing,
  or concurrency conventions changed → the matching contributor-docs page.
- Both sites deploy independently and are paths-filtered: `deploy-docs.yaml` on `docs/**` (→
  Cloudflare) and `deploy-contributor-docs.yaml` on `contributor-docs/**` (→ GitHub Pages);
  backend CI ignores docs-only changes and vice versa. Both `package.json` files are watched by
  Dependabot.
- Markdown rules per `.markdownlint.yaml` (MD013 disabled). All docs in English.
