-- Rating state for accounts (#247/#238, ADR-0017): registered humans join the SAME Glicko-2 scale the
-- bots already live on. Same column types, same seeds (1500 / 350 / 0.06) as V4 gave `bots` — that is
-- load-bearing, not cosmetic: one shared scale is what makes "who is strongest" answerable across both
-- populations at all, and it is also what solves cold start (human-vs-human traffic is thin, bots are
-- always available to be measured against).
ALTER TABLE users
    ADD COLUMN glicko_rating double precision NOT NULL DEFAULT 1500,
    ADD COLUMN glicko_rd     double precision NOT NULL DEFAULT 350,
    ADD COLUMN glicko_vol    double precision NOT NULL DEFAULT 0.06;

-- Whether a game between this bot and a HUMAN counts for rating (#247/#238).
--
-- DELIBERATELY NOT SELF-SERVICE — do not "fix the inconsistency" by exposing this on /bot/*.
-- The two neighbouring flags are set by the bot's own bearer token: `on_ladder` (V4) and
-- `open_to_humans` (V8). That is fine for them, because a bot choosing to play cannot corrupt anyone
-- else's rating. This flag can: a bot author who could set it would register a deliberately weak bot,
-- open it, and farm rating off their own creation. So it is an OPERATOR decision, set declaratively at
-- boot (see `CatalogRoster` and `PLAY_RATED_FOR_HUMANS`) or by hand — never by the rated party.
--
-- Default false: absence must select the conservative policy, exactly as `max_concurrent_games` (V12)
-- argued. A human-vs-bot game is casual until an operator says that particular bot is a fair yardstick.
ALTER TABLE bots
    ADD COLUMN rated_for_humans boolean NOT NULL DEFAULT false;
