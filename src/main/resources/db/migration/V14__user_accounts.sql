-- Player accounts (#231/#232, ADR-0017): registered humans behind Google sign-in. Three tables
-- because three different things are being keyed:
--
--   * `users`            — the account itself, keyed by a UUID *we* mint. This id is what
--                          `Principal.User(id).externalId` embeds into `game_results`, so it must
--                          never be an attribute a provider can change.
--   * `user_identities`  — the login method, keyed by (provider, subject). Google's stable `sub`
--                          claim is the subject; email is deliberately a mutable ATTRIBUTE here,
--                          never an identity key — an address change must not fork the account
--                          (the lab/analytics predecessors keyed users by email and could not
--                          survive one). One row per provider identity, many-to-one to `users`,
--                          so a second provider later is a row, not a schema change.
--   * `user_guest_links` — anonymous history claimed by an account. `guest_id` is the PRIMARY
--                          KEY on purpose: one guest identity belongs to at most one account,
--                          ever. History is linked, not rewritten — `game_results` keeps its
--                          `guest:` external ids and readers union over the linked set.
CREATE TABLE users (
    id            uuid PRIMARY KEY,
    nickname      text NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    -- The kill switch re-checked on every authenticated request (the session JWT is never
    -- trusted for authorization state), so deactivation takes effect immediately.
    is_active     boolean NOT NULL DEFAULT true
);

-- Case-insensitive uniqueness via a functional index rather than citext: no extension to
-- install, and the store's collision handling only needs the violation, not a special type.
CREATE UNIQUE INDEX users_nickname_ci_idx ON users (lower(nickname));

CREATE TABLE user_identities (
    provider   text NOT NULL,
    subject    text NOT NULL,
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    -- Refreshed on login for the owner's own profile view; absent when the provider omits it.
    email      text,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, subject)
);

-- Account deletion and "which logins does this account have" both look up by user.
CREATE INDEX user_identities_user_idx ON user_identities (user_id);

CREATE TABLE user_guest_links (
    guest_id  uuid PRIMARY KEY,
    user_id   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    linked_at timestamptz NOT NULL DEFAULT now()
);

-- The merged-history read (`/me/games`) expands an account into its linked guest ids.
CREATE INDEX user_guest_links_user_idx ON user_guest_links (user_id);
