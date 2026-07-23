-- Catalog of bots that offer games to human visitors (ADR-0014, epic #152).
-- `open_to_humans` is the catalog opt-in, independent of `on_ladder` — a bot can do either, both,
-- or neither. `description` is the catalog card's blurb (algorithm/platform), nullable.
ALTER TABLE bots
    ADD COLUMN open_to_humans boolean NOT NULL DEFAULT false,
    ADD COLUMN description     text;
