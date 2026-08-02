package dicechess.play.store

import cats.effect.IO

import java.time.Instant

/** A registered player (#231/#232, ADR-0017). `id` is a UUID minted by this server at first login — it is the stable
  * half of `Principal.User(id).externalId` and therefore must never be derived from anything a login provider controls
  * (see V14's rationale for why email in particular is not an identity key).
  */
final case class UserAccount(
    id: String,
    nickname: String,
    createdAt: Instant,
    lastLoginAt: Option[Instant],
    isActive: Boolean
)

/** The three ways a nickname change can land. A distinct `Taken` (rather than folding it into an error channel) because
  * it is the one outcome the route must turn into a client-visible 409, not a 5xx.
  */
enum NicknameUpdate:
  case Updated, Taken, UserNotFound

/** The outcome of claiming a guest id. Idempotent by design: re-claiming an id already linked to the SAME account is
  * `Linked` again, not an error — the client cannot tell (and must not need to tell) whether a retry raced its first
  * attempt. `ClaimedByAnother` is terminal: `user_guest_links.guest_id` is a primary key, one account ever.
  */
enum GuestLink:
  case Linked, ClaimedByAnother, UserNotFound

/** Persistence seam for player accounts. Like the catalog and leaderboard this is a Postgres-only feature: without
  * `PLAY_DB_URL` the auth surface is simply never mounted, so no in-memory implementation exists.
  */
trait UserStore:

  /** The single write behind a completed sign-in: find the `(provider, subject)` identity, or create the account it
    * should point at. First login mints the user id server-side and names the account via `freshNickname`, retrying
    * with a new candidate on a nickname collision; repeat logins refresh `last_login_at` and the stored email.
    *
    * `freshNickname` is a dependency, not a stored value, so the generator (its word lists, its collision widening) can
    * evolve in the auth layer without touching persistence.
    *
    * Concurrency: two simultaneous first logins with the same subject race on the `user_identities` primary key; the
    * loser's transaction rolls back and its retry finds the winner's identity, so exactly one account is ever created.
    */
  def upsertOnLogin(
      provider: String,
      subject: String,
      email: Option[String],
      freshNickname: IO[String]
  ): IO[UserAccount]

  /** The per-request session check reads the live row — the JWT is never trusted for `isActive`/existence. */
  def userById(id: String): IO[Option[UserAccount]]

  /** Rename, enforcing the case-insensitive uniqueness the V14 index defines. Format validation (length, alphabet,
    * reserved words) is the route's job — this store only knows what the database can enforce.
    */
  def updateNickname(userId: String, nickname: String): IO[NicknameUpdate]

  /** Claim a guest id for an account (proof of ownership = presenting the id, the restore-code trust model). */
  def linkGuest(userId: String, guestId: String): IO[GuestLink]

  /** The guest ids this account has claimed, oldest link first — the id set merged-history reads expand over. */
  def guestsOf(userId: String): IO[List[String]]

  /** Self-service account deletion (#237). Identities and guest links go via `ON DELETE CASCADE`; `game_results` and
    * `game_archive` are untouched on purpose — the account's `user:<uuid>` external id simply stops resolving, which
    * anonymizes the history without rewriting immutable records. `false` when the user was already gone.
    */
  def deleteUser(userId: String): IO[Boolean]
