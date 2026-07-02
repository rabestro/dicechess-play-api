-- Warm game snapshots (phase 3b durability). One row per game, upserted on every
-- published event — a fixed dice roll must survive a crash, or a player could
-- re-roll by crashing the server. The snapshot JSON is self-sufficient to resume
-- an active game (DFEN carries the pending dice); no event sourcing.
CREATE TABLE games (
    id         uuid PRIMARY KEY,
    status     text        NOT NULL CHECK (status IN ('active', 'ended')),
    snapshot   jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

-- Boot-time resume scans only live games.
CREATE INDEX games_active_idx ON games (status) WHERE status = 'active';
