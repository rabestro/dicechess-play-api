-- Immutable, sanitized history record (#177) — play's own durable representation of a finished game, independent of
-- the analytics wire contract and of `games` snapshot retention (#179 prunes ended snapshots once this becomes the
-- serving path for replay, GET /games/{id}/history, #178).
-- No FOREIGN KEY to games(id) — same rationale as game_results (V5): this archive must outlive a future snapshot
-- prune.
-- Indexes: PK only. Access is always by game id (there is no listing surface over the archive).
CREATE TABLE game_archive (
    game_id     uuid PRIMARY KEY,
    payload     jsonb NOT NULL,
    finished_at timestamptz NOT NULL DEFAULT now()
);
