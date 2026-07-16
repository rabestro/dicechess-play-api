-- Bot rating ladder state (#100): Glicko-2 parameters plus the ladder opt-in flag, and a forward-looking owner slot
-- for when human accounts arrive (nullable — nothing populates it yet; adding it now avoids a later migration).
ALTER TABLE bots
    ADD COLUMN glicko_rating     double precision NOT NULL DEFAULT 1500,
    ADD COLUMN glicko_rd         double precision NOT NULL DEFAULT 350,
    ADD COLUMN glicko_vol        double precision NOT NULL DEFAULT 0.06,
    ADD COLUMN on_ladder         boolean          NOT NULL DEFAULT false,
    ADD COLUMN owner_external_id text;
