---
title: Database Schema
description: The six tables of the play-api Postgres schema, what each is for, and the deliberate absence of some foreign keys.
---

Persistence is **opt-in**: with no database URL configured the server runs fully in memory and
a restart drops everything. When a database is configured, Flyway applies the migrations in
`src/main/resources/db/migration/` at boot, and doobie does the querying.

:::note
This page explains *why* each table exists, which the SQL cannot. For the column-level
reference — every type, default, constraint, and index — see
[Schema Reference](/dicechess-play-api/reference/schema/), which is generated from the
migrations and therefore always current.
:::

## The tables

### `games` — resumable snapshots

One upserted row per game, active or ended, holding an opaque `jsonb` snapshot that is
self-sufficient to resume play. This is deliberately **not** event sourcing: the server
restores a room by decoding one snapshot, not by replaying a log. A partial index on
`status = 'active'` serves the boot-time resume scan, and a check constraint pins `status` to
`active` or `ended`.

### `outbox` — transactional delivery to analytics

The finished game's analytics payload, written **in the same transaction as the terminal
snapshot**. That is the whole point of the pattern: a game cannot end without its ingest row
existing, and the HTTP call is decoupled from the game's commit. `IngestDeliverer` polls a
partial index of rows that are undelivered, not permanently parked, and due; failures back off
via `attempts` / `next_attempt_at`, and a 4xx parks the row as `failed_permanently` with the
error preserved.

### `bots` — durable identity plus ladder state

A bot's identity survives restarts here. Only a **hash** of the bearer token is stored, with a
unique constraint so one token maps to exactly one identity. The same row carries the Glicko-2
triple (`glicko_rating`, `glicko_rd`, `glicko_vol`, seeded at 1500 / 350 / 0.06), the
`on_ladder` flag, and the human-facing catalog opt-in (`open_to_humans`, `description`).
Primary key is `(team, name)`.

### `bot_webhooks` — verified callback registration

Where the server POSTs on a bot's turn. A row exists only after the ownership handshake
succeeded (`verified_at`). Note the asymmetry with `bots`: the per-bot HMAC `secret` is stored
in plaintext because the server must read it back to sign requests, whereas the bearer token is
only ever compared as a hash. Deleting the bot cascades to its webhook.

### `game_results` — the queryable projection

Finished games, decoded out of the opaque snapshot so the ladder scheduler, the rating batch,
and the strength report can query by participant, result, rated flag, or ladder origin without
touching JSON. It carries the revealed `server_seed`, the `termination`, the `time_control`,
and `rating_applied_at` as the rating batch's work-queue marker. `result` follows a white-POV
convention — `1` white won, `-1` black won, `0` draw — enforced by application convention, not
by a check constraint.

### `game_archive` — immutable history

A sanitized, immutable record of a finished game: play's own durable representation of history,
independent of both the analytics wire contract and snapshot retention. Access is always by
game id, so the primary key is the only index.

## Two deliberate design choices

**`game_results` and `game_archive` have no foreign key to `games`.** This is intentional, not
an oversight: both must outlive the snapshot. Retention prunes ended snapshots, and a foreign
key would either block that or cascade away the very history these tables exist to keep.

**V10 drops nothing, despite its name.** The migration is called `drop_crn_pairing`, but
`pairing_id` and its partial index remain — historical CRN-paired rows must stay interpretable
by the strength report. What V10 actually does is *add* the `ladder` boolean that now marks
ladder-origin games, taking over the role `pairing_id` used to imply. New rows leave
`pairing_id` null. Across V1–V10, no column is ever dropped.

## Changing the schema

- Add a new numbered migration; never edit one that has been applied anywhere.
- Regenerate the reference with `mise run contrib-docs:schema` and commit it in the same pull
  request — CI applies the migrations to a throwaway Postgres and fails if the committed page
  is stale.
- Migrations against a shared database are an operator action, not a CI action.
- The four suites that touch Postgres run against Testcontainers, so a migration that fails to
  apply fails the build — see [Testing](/dicechess-play-api/testing/).
