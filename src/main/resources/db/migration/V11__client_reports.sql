-- Browser-submitted game reports (#212): finished games the SPA played against its OWN in-browser bots, reported by
-- the client and relayed to analytics. These games never had a `games` snapshot on this server — hence no FOREIGN KEY,
-- and a separate table rather than `outbox`: what this server PLAYED (trusted, enqueued transactionally at game end)
-- and what a client TOLD it (forgeable, structurally validated at ingress only) must never mix. Rows here feed the
-- deliverer exclusively; nothing from this table may reach game_results/game_archive/history.
-- report_id is the payload's own idempotency UUID (UUIDv5 of the client-local game id), so a re-POST dedups here and
-- a redelivery dedups again on the analytics side.
CREATE TABLE client_reports (
    report_id    uuid PRIMARY KEY,
    payload      jsonb       NOT NULL,
    attempts     int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    -- Same parking semantics as outbox: a 4xx (e.g. the analytics replay gate's 422) never succeeds on retry.
    failed_permanently boolean NOT NULL DEFAULT false,
    last_error   text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    delivered_at timestamptz
);

-- The deliverer polls only undelivered, non-parked, due rows — mirror of outbox_due_idx.
CREATE INDEX client_reports_due_idx ON client_reports (next_attempt_at)
    WHERE delivered_at IS NULL AND NOT failed_permanently;
