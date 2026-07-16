-- Queryable projection of finished games (#98): the games table's snapshot is opaque JSONB (only `status` is
-- indexed), so the ladder scheduler and rating batch can enumerate finished games by participant / result / rated /
-- pairing without decoding JSON. One row per finished game, written once (in the same transaction as the terminal
-- snapshot write, PgGameStore.save) and never updated afterward.
CREATE TABLE game_results (
    game_id            uuid PRIMARY KEY,
    white_external_id  text NOT NULL,
    black_external_id  text NOT NULL,
    -- White-POV: 1 white won, -1 black won, 0 draw — same convention as the analytics ingest wire
    -- (PlaysiteIngest.resultOf). Nullable for forward-compat; GameResult has no "unknown" case today.
    result             smallint,
    termination        text NOT NULL,
    rated              boolean NOT NULL,
    time_control       text NOT NULL,
    server_seed        text NOT NULL,
    pairing_id         uuid,
    finished_at        timestamptz NOT NULL DEFAULT now()
);

-- The rating batch's own cursor query: rated games finished since its last run.
CREATE INDEX game_results_rated_finished_idx ON game_results (rated, finished_at);

-- recentResultsFor(externalId) needs the top N by finished_at for either seat. A plain single-column index per side
-- would force Postgres to bitmap-OR the two, then sort ALL of a prolific bot's matching rows before applying LIMIT —
-- O(history size), not O(limit). Composite (participant, finished_at DESC) lets the query below run each side as its
-- own LIMIT-bounded, already-ordered index scan instead.
CREATE INDEX game_results_white_finished_idx ON game_results (white_external_id, finished_at DESC);
CREATE INDEX game_results_black_finished_idx ON game_results (black_external_id, finished_at DESC);

-- pairFor(pairingId): most games aren't part of a CRN pair, so a partial index keeps it small.
CREATE INDEX game_results_pairing_idx ON game_results (pairing_id) WHERE pairing_id IS NOT NULL;
