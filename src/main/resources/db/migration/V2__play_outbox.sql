-- Durable handoff to analytics (phase 3b): when a game ends, its GameIngest payload is
-- enqueued here in the SAME transaction as the final snapshot, and a background deliverer
-- POSTs it to the analytics ingest endpoint with retry/backoff. One row per game.
CREATE TABLE outbox (
    game_id      uuid PRIMARY KEY REFERENCES games (id),
    payload      jsonb       NOT NULL,
    attempts     int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    -- A 4xx from the ingest endpoint (e.g. the replay gate's 422) will not succeed on retry:
    -- the row is parked for manual inspection instead of being retried forever.
    failed_permanently boolean NOT NULL DEFAULT false,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz
);

-- The deliverer polls only undelivered, non-parked, due rows.
CREATE INDEX outbox_due_idx ON outbox (next_attempt_at)
    WHERE delivered_at IS NULL AND NOT failed_permanently;
