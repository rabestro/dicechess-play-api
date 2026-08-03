-- Ownership became a queryable relation in #253: `GET /me/bots` looks bots up BY owner, which the column
-- created in V4 was never indexed for (nothing wrote it until now, so nothing read it either). Without this
-- the owner's own page scans the whole table on every request — small today, and exactly the kind of thing
-- that is never revisited once it is merely "fine".
CREATE INDEX bots_owner_idx ON bots (owner_external_id) WHERE owner_external_id IS NOT NULL;
