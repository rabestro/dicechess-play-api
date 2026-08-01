-- Dropping CRN mirrored pairs (#190): the ladder no longer shares one dice seed between two
-- colour-swapped games, so `pairing_id` no longer identifies a matched pair — new rows leave it
-- NULL. The column and its partial index stay: historical CRN rows remain interpretable by the
-- strength report's pentanomial grouping (StrengthReport.scala), and this table is designed to
-- outlive the JSONB snapshot it projects (see V5's own header).
--
-- `pairing_id` was also, incidentally, the only marker distinguishing a ladder-scheduled game from
-- a directly-challenged rated game between the same two bots (RatingBatch.shouldPark's auto-park
-- streak, #150, depended on it for exactly that — a casual/challenge timeout must never park a
-- bot). `ladder` replaces that role explicitly, decoupled from pairing/CRN entirely.
ALTER TABLE game_results ADD COLUMN ladder boolean NOT NULL DEFAULT false;

-- shouldPark's cursor: the last N ladder games for a bot, newest first. Historical rows backfill
-- to false (accurate: nothing before this migration recorded itself as ladder-originated), so
-- auto-park's streak simply restarts counting from here rather than misreading old rows.
--
-- Plain CREATE INDEX, deliberately not CONCURRENTLY (a linter will suggest otherwise): the column is
-- added `DEFAULT false` two statements up, so the partial predicate matches ZERO rows and the index is
-- built empty. The write lock lasts one sequential scan — measured at 28 ms over this table in
-- production (61 647 rows / 34 MB). CONCURRENTLY would cost two scans instead of one, cannot run inside
-- Flyway's transaction (so it needs its own non-transactional migration), and leaves an INVALID index
-- needing manual cleanup if it fails. That is real operational risk traded for ~28 ms of blocking.
CREATE INDEX game_results_ladder_idx ON game_results (ladder) WHERE ladder;
