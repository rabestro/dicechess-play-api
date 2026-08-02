-- Declared per-bot capacity (#189): how many games a bot is willing to hold at once.
-- Webhook delivery inverts the pull model — the server pushes and the bot must answer — so
-- registration is the only place an author can say "one game at a time". Enforced at SEATING,
-- never at delivery: a delivery held back inside a running game burns that bot's clock.
--
-- The default is deliberately 1, not "unlimited": absence has to mean the conservative policy,
-- or today's behaviour (a bot observed in three simultaneous games, #188) stays the default and
-- every author who never heard of this column keeps losing on time. Opting upward is explicit,
-- via POST /bot/capacity.
--
-- The upper bound is a sanity rail, not a capacity model — it keeps one row from claiming the
-- whole ladder. Static (PLAY_BOT_TOKENS) and anonymous bots have no row here and stay unbounded:
-- they cannot declare anything, and the house bot must serve every quickstart visitor at once.
ALTER TABLE bots
    ADD COLUMN max_concurrent_games integer NOT NULL DEFAULT 1
        CONSTRAINT bots_max_concurrent_games_range CHECK (max_concurrent_games BETWEEN 1 AND 32);
