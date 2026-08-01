---
title: Testing
description: munit conventions, why suites run serially, the non-flaky patterns this repo learned the hard way, and the deadlock that masquerades as a timeout bug.
---

Tests use munit. Pure logic suites extend `munit.FunSuite`; effectful suites extend
`munit.CatsEffectSuite`. Suites are named `<Unit>Suite` and mirror the main package layout, and
test names are full sentences describing behaviour:

```scala
test("the game-end event reveals the server seed")
```

Four suites need Docker, because they run against a real Postgres via Testcontainers:
`PgGameStoreSuite`, `IngestDelivererSuite`, `RatingBatchSuite`, `HistoryRoutesSuite`.
Everything else is Docker-free.

## Suites run one at a time

`Test / parallelExecution := false`. This is not a workaround left in place out of caution:
running the four container suites concurrently under scoverage caused enough real CPU
contention to **delay a cats-effect timer by 133 seconds**. Serial is also the *faster* option
as measured — a steady 30 seconds versus a bimodal 30-or-313 — because container startup
dominates and does not parallelise usefully.

Do not re-enable parallel execution to "speed up" CI.

## The deadlock that looks like a timeout bug

A game with an **idle seat and no clock stops forever**. From the starting position only pawns
and knights can move, so a roll containing neither makes the room auto-pass to the other seat;
with `TimeControl.Unlimited` and nobody driving that seat, the game never progresses.

The failure is dice-dependent — roughly `(4/6)³ ≈ 30%` — so it presents as an intermittent
timeout. It has been misdiagnosed **twice** as fiber starvation and "fixed" by widening a
bound. If a test needs a specific seat to get an actionable turn, drive the opponent (see
`WebhooksSuite` and its `BotConnection`) instead of assuming the opening roll falls that seat's
way.

## Non-flaky patterns

This repository has fixed three separate stream races. The patterns that came out of them:

- **Subscribe before acting.** Never trigger an event and then attach to the stream.
- **Poll durable state** rather than sleeping or racing the live stream.
- **Bound every effectful wait** with `timeoutTo`.

## Shared-database assertions

`PgGameStoreSuite` runs every test against one Postgres instance with no reset between tests.
Aggregates computed over the whole table therefore cannot be asserted as if they belonged to a
single test. This has produced false failures twice in one day, and both times the code was
right and the assertion was wrong — scope assertions to the rows the test created.

## Hard bugs get a failing test first

For a non-trivial bug, land the failing test **before** the fix, as its own pull request, marked
suspended (`.fail`, not skipped). Reference the issue with `refs #N` in that test-only pull
request — not `Closes`, since the bug is not fixed yet.

## Running one suite

```bash
sbt "testOnly dicechess.play.game.GameRoomSuite"
```
