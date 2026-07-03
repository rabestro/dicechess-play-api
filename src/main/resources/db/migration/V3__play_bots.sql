-- Durable self-service bot identities (#70): a registered bot survives server restarts,
-- unlike the in-memory anonymous tokens. Only the SHA-256 of the Bearer token is stored —
-- the plaintext is shown exactly once at registration/rotation and never persisted.
CREATE TABLE bots (
    team       text NOT NULL,
    name       text NOT NULL,
    -- SHA-256 of the Bearer token, hex. Unique: a token maps to exactly one identity, and
    -- authentication is a single indexed lookup by the presented token's hash.
    token_hash text NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    -- Rotation bookkeeping (the row keeps its identity; only the hash changes).
    rotated_at timestamptz,
    PRIMARY KEY (team, name)
);
