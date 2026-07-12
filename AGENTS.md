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
  (/games + /games/{id}/ws), `LobbyRoutes` (/lobby/seeks), `BotRoutes` (/bot/*), plus
  `GameRegistry`, `Lobby`, `Challenges`, `BotAuth`, `BotEvents`, `AnonMintLimiter`, `Cors`.
- `store/` — `GameStore`/`PgGameStore`: doobie + Flyway, jsonb snapshots; migrations in
  `src/main/resources/db/migration/` (V1 games, V2 outbox, V3 bots).
- `ingest/` — `PlaysiteIngest` + `IngestDeliverer`: transactional outbox → analytics.
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
`PLAY_CORS_ORIGINS` (empty = allow any), `APP_VERSION` (surfaced at GET /version).

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
  `munit.CatsEffectSuite`; only `PgGameStoreSuite` and `IngestDelivererSuite` add
  `TestContainerForAll` (postgres:18-alpine) and need Docker — everything else is Docker-free.
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
- `ThisBuild/version` is frozen at `0.1.0-SNAPSHOT`; real versions come exclusively from git
  tags via the CD workflow (`APP_VERSION` build-arg → GET /version). Do not bump it.
- `PLAY_DB_URL` set without `INGEST_URL`/`INGEST_TOKEN`: finished games silently accumulate in
  the outbox (boot warns on stderr); a 4xx from analytics parks the row as `failed_permanently`.
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

- In-repo: `README.md` (orientation; status sections stale, see Gotchas), `docs/bot-api.md`
  (authoritative public Bot API reference: auth, endpoints, DFEN, provably-fair protocol,
  legal-move prefix trees, time controls), `CONTRIBUTING.md`, `SECURITY.md`, `CLA.md`.
- Design ADRs live outside this repo in the `dicechess-docs` vault: ADR-0007 (server
  authority), ADR-0008 (dice fairness), ADR-0009 (Bot API).
- Update-trigger map: `/bot` routes or protocol semantics changed → `docs/bot-api.md`; dice
  protocol → bot-api.md fairness section (+ `random_bot.py` if affected); server env vars or
  compose → README run/deploy sections; `wire/Codecs.scala` → coordinate with dicechess-play.
- Markdown rules per `.markdownlint.yaml` (MD013 disabled). All docs in English.
