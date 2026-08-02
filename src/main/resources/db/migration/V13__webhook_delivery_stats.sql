-- Webhook delivery telemetry (#225): a bucketed histogram, not a row per delivery. An author asking "why does
-- my bot get no turns?" faces three indistinguishable causes since per-bot capacity (#189) — declared capacity in
-- use, a broken endpoint, or simply not being picked — and only the middle one was invisible until now. This
-- table makes it diagnosable without ever holding up a turn: recording is fire-and-forget, off the delivery path
-- (see `Webhooks.scala`).
--
-- One row per (bot, hour, outcome, latency bucket) — bounded growth: at most a few dozen rows per bot per hour
-- (the number of distinct outcomes actually seen times ~14 latency buckets), never one row per delivery. `outcome`
-- folds the HTTP status into the string itself (e.g. `http_503`) rather than a separate nullable column, so the
-- whole classification stays a single NOT NULL text and fits cleanly in the primary key.
CREATE TABLE bot_webhook_stats (
    team           text NOT NULL,
    name           text NOT NULL,
    hour           timestamptz NOT NULL, -- truncated to the hour (UTC); rolls up trivially into a day or a week
    outcome        text NOT NULL,
    latency_bucket smallint NOT NULL,    -- index into the fixed log-spaced boundaries in WebhookStats.scala
    count          bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (team, name, hour, outcome, latency_bucket),
    FOREIGN KEY (team, name) REFERENCES bots (team, name) ON DELETE CASCADE
);

-- The read side always filters by (team, name) and a recency cutoff on `hour` — this is that query's own index,
-- distinct from the primary key's leading columns only in that it's declared for the range scan on `hour`.
CREATE INDEX bot_webhook_stats_recent_idx ON bot_webhook_stats (team, name, hour);

-- "Report it back" also means the one delivery an author can't see in a histogram: the most recent failure and
-- why. Columns on `bot_webhooks` itself (one row per bot already) rather than a second single-row table — both
-- are nullable because a bot with a clean delivery history, or no deliveries yet, has neither.
ALTER TABLE bot_webhooks
    ADD COLUMN last_failure_at     timestamptz,
    ADD COLUMN last_failure_reason text;
