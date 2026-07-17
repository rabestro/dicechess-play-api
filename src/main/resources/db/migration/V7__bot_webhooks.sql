-- Webhook callback registration for registered bots (F.2, #104; design: ADR-0013): when it is the bot's turn,
-- the server POSTs the game state to `url` and applies the response body as the move — serverless push instead
-- of polling. One webhook per bot identity; a row exists only AFTER the ownership handshake succeeded (the
-- endpoint echoed the server's nonce), so `verified_at` is NOT NULL by construction.
--
-- `secret` is stored in PLAINTEXT on purpose, unlike bots.token_hash: it is the per-bot HMAC key the server
-- uses to SIGN every outbound delivery (the bot verifies X-DiceChess-Signature with its copy), so the server
-- must be able to read it back — a hash could sign nothing. It is not a credential INTO play-api: possession
-- grants no API access whatsoever.
CREATE TABLE bot_webhooks (
    team        text NOT NULL,
    name        text NOT NULL,
    url         text NOT NULL,
    secret      text NOT NULL,
    verified_at timestamptz NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (team, name),
    -- A webhook is an attribute of a registered identity; if the identity ever goes, its webhook goes with it.
    FOREIGN KEY (team, name) REFERENCES bots (team, name) ON DELETE CASCADE
);
