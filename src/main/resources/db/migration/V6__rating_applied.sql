-- Claim-based work queue for the Glicko-2 rating batch (#119): a game_results row with rating_applied_at IS NULL is
-- rated-but-not-yet-applied. The batch selects unapplied rated games in finished_at order and, per game, updates both
-- bots' glicko_* columns AND stamps this column in ONE transaction — so a game is applied exactly once, crashes
-- included. A flag on the row (rather than a timestamp cursor) is deliberate: rating updates are NOT idempotent, and
-- a finished_at cursor is subject to the commit-order race documented on GameResultsStore.finishedRatedSince — a
-- slower-committing game could slip behind an already-advanced cursor and be skipped forever, while with a claim flag
-- an out-of-order commit is simply picked up on the next poll. This is the one exception to game_results rows being
-- write-once: a single bookkeeping stamp, set once, never changed again.
ALTER TABLE game_results
    ADD COLUMN rating_applied_at timestamptz;

-- The batch's poll: small (only the unapplied backlog), ordered by the queue's own sort key.
CREATE INDEX game_results_rating_queue_idx
    ON game_results (finished_at)
    WHERE rated AND rating_applied_at IS NULL;
