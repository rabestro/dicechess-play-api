---
title: Configuration
description: Every server environment variable, what it enables, and the silent-disable idiom that has burned three deployments.
---

Every subsystem is opt-in through the environment. The server reads these once at boot in
`Main` and wires up only what is configured.

:::danger[Absence disables the feature, silently]
`LADDER_INTERVAL_SECONDS`, `RATING_INTERVAL_SECONDS`, `WEBHOOK_TIMEOUT_SECONDS` and
`RETENTION_INTERVAL_SECONDS` all follow the same idiom: **unset means the feature simply never
runs, with no error anywhere**. The server starts clean and `/health` returns 200 while ladder
pairing, rating updates, webhook push, or pruning quietly do nothing.

This has bitten a real deployment three separate times, one variable at a time. Verify a new
environment with a *live* check — `GET /games` becomes non-empty and `/leaderboard` counts
increase over a minute — never with `/health` alone. When copying configuration between
environments, enumerate the variables from the source (`grep -rhoE '"[A-Z][A-Z0-9_]{3,}"'
src/main/scala`) rather than from documentation, which drifts.
:::

## Persistence

| Variable | Effect |
| --- | --- |
| `PLAY_DB_URL`, `PLAY_DB_USER`, `PLAY_DB_PASSWORD` | Enable Postgres persistence. Unset means fully in-memory: a restart drops every game. |

## Analytics ingest

| Variable | Effect |
| --- | --- |
| `INGEST_URL` | The **full** endpoint URL, not a base. Enables outbox delivery. |
| `INGEST_TOKEN` | Bearer token for that endpoint. |

Setting `PLAY_DB_URL` without these is a trap: finished games accumulate in the outbox
undelivered. Boot warns on stderr, and nothing else complains.

## Ladder and rating

| Variable | Effect |
| --- | --- |
| `LADDER_INTERVAL_SECONDS` | Enables automatic ladder pairing. Unset disables pairing entirely. |
| `LADDER_MAX_CONCURRENT_PAIRS` | Optional, default `4`. |
| `RATING_INTERVAL_SECONDS` | Enables Glicko-2 rating updates **and** ladder auto-park. Unset disables both. |
| `RATING_BATCH_SIZE` | Optional, default `100`. |
| `LADDER_TIMEOUT_PARK_PAIRS` | Optional, default `2`. Despite the `LADDER_` prefix it is read by the *rating* batch — with rating off, a dead bot is never parked and keeps bleeding rating while inflating every opponent it meets. The name follows the feature, not the component. |
| `STRENGTH_ELO0`, `STRENGTH_ELO1`, `STRENGTH_ALPHA`, `STRENGTH_BETA`, `STRENGTH_BOOTSTRAP_ITERATIONS` | Tuning knobs for the SPRT / Bradley-Terry report. Each falls back to its own default rather than disabling anything — but the report is refreshed by the rating batch, so it is only ever populated while `RATING_INTERVAL_SECONDS` is set. |

## Webhooks, retention, and the rest

| Variable | Effect |
| --- | --- |
| `WEBHOOK_TIMEOUT_SECONDS` | Enables bot webhook push — both the routes and the dispatcher. Unset disables the feature. |
| `RETENTION_INTERVAL_SECONDS` | Enables the retention prune. Unset keeps ended snapshots and delivered outbox rows forever. |
| `RETENTION_DAYS` | Optional, default `30`. |
| `RETENTION_BATCH_SIZE` | Optional, default `1000`. |
| `PLAY_BOT_TOKENS` | Statically configured bots, as `team\|name\|token` CSV. |
| `PLAY_CORS_ORIGINS` | Allowed origins; empty allows any. |
| `APP_VERSION` | Surfaced at `GET /version`. Set by the CD workflow from the git tag. |

:::caution[Retention looks broken on an unbackfilled deployment]
The prune refuses to remove an ended, non-aborted snapshot that has no `game_archive` row —
that snapshot would be the only copy of the game's history. On a deployment whose games predate
the archive table, retention therefore reclaims nothing and appears not to work. Run the
archive backfill first; the retained count appears in the log line.
:::
