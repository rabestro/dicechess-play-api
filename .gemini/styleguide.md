# Code review style guide

Generated extract of the review-relevant rules in AGENTS.md — keep the two in sync manually.

## Architecture invariants (highest priority)

- Server authority is absolute: no code path may accept client-supplied FEN, dice, clocks,
  results, or move legality. Dice come only from `DiceSource`; legality only from `EngineOps`
  (the engine artifact). Flag any re-implementation of chess rules.
- GameRoom concurrency doctrine: a single consumer fiber is the only writer of game state;
  fan-out uses non-blocking `tryOffer` to bounded per-subscriber queues; rooms depend only on
  `Principal`/`Seat`/`PlayerConnection` abstractions, never concrete transports. Flag any
  mutation outside the consumer fiber or any blocking call inside a room.
- Changes to `wire/Codecs.scala` are wire-contract changes consumed by the dicechess-play SPA;
  changes to `dice/DiceSource.scala` affect the public provably-fair verification procedure —
  both require matching doc/test updates in the same PR (`docs/bot-api.md`).

## Scala conventions

- Scala 3 "fewer braces" style; scalafmt is law (maxColumn 120). No end markers.
- Pure Typelevel FP: cats-effect `IO` everywhere; no nulls, no exceptions for control flow —
  errors are returned as values (`Either`/typed responses). `Resource` for lifecycles;
  background fibers scoped with `.background`/`.surround` so failures surface.
- `-Werror -Wunused:all`: flag unused imports and dead code — they fail the build.
- Comments must explain why, not what. No TODO/FIXME — the codebase has zero; decisions are
  encoded as rationale comments.

## Testing expectations

- New behaviour needs tests: pure logic in `munit.FunSuite`, effectful in `CatsEffectSuite`;
  suites named `<Unit>Suite`, mirroring the main package layout; test names are full sentences.
- Flag race-prone test patterns: attaching to an event stream after triggering the event,
  bare sleeps, unbounded waits (require subscribe-before-act, polling durable state, `timeoutTo`).
- Only `PgGameStoreSuite` and `IngestDelivererSuite` may depend on Docker/testcontainers.

## Process

- English only in code, comments, commits, and PR descriptions.
- Do not accept changes that weaken quality config: build-time pins in `build.sbt`
  (testcontainers/docker-java force-bumps, `-Dapi.version`), the Dockerfile base-image
  `-noble` suffix, BuildKit secret usage, or `JAVA_OPTS` cats-effect flags.
- Version bumps of the engine artifact must state how the change was verified against this
  server (tests passing is the minimum).
