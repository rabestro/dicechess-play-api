package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.store.{BotCatalogState, BotStore}

/** The env-configured operator rosters: an admin gate applied at startup, mirroring how `PLAY_BOT_TOKENS` seeds the
  * static bot roster. Three independent rosters, deliberately three env vars rather than one grammar with a flag column
  * — they answer different questions and they are not equally consequential:
  *   - `PLAY_OPEN_TO_HUMANS` (ADR-0014) — may a visitor start a game against this bot;
  *   - `PLAY_RATED_FOR_HUMANS` (#247) — is such a game ELIGIBLE to count for rating (the batch that would act on it
  *     lands in #248). This roster exists ONLY here because the flag must not be self-service: a bot author who could
  *     set it would register a weak bot and farm rating off it (see V15).
  *   - `PLAY_RETIRED_BOTS` — take a bot out of service: off the ladder and out of the human catalog.
  *
  * All three exist for the same reason: a registered bot normally flags itself over the bot API with its Bearer token,
  * and a bot whose registration token was not kept has no way to — so the single author does it declaratively instead.
  * Only the server's SHA-256 of a token is stored, so a lost token is lost for good, and a bot in that state was
  * previously **un-retirable**: it stayed listed and kept being paired no matter what happened to the process behind
  * it.
  *
  * '''Retirement is the only roster that clears rather than sets, and that does not weaken the "a typo narrows"
  * rule.''' The two additive rosters compose with token-based self-flagging instead of fighting it, and a typo there
  * fails to enable something. A typo in the retirement roster fails to *retire* something — it leaves a working bot
  * working, and cannot reach a bot the operator did not name. Both failure directions are the safe one, which is why
  * this stays a roster of explicit names and `PLAY_OPEN_TO_HUMANS` was NOT made authoritative instead: an authoritative
  * open-roster would turn one typo into every unnamed bot going dark.
  *
  * '''Retirement is a one-shot action at boot, not a ban.''' Nothing stops a bot that still holds its token from
  * rejoining afterwards via `POST /bot/ladder/join`, and that is deliberate: banning would need a new column, hence a
  * migration, to express a state the token-less case does not need. Retirement runs after the additive rosters, so a
  * name present in both wins here — and that overlap is an operator mistake, so [[conflicts]] reports it at boot rather
  * than letting the two lists quietly fight on every restart.
  *
  * Every roster is idempotent. An entry naming an unregistered identity is logged and skipped — no row to flag.
  */
object CatalogRoster:

  /** Env var holding the roster: `;`-separated entries, each `team|name` or `team|name|description`. Entries are
    * `;`-separated (not comma, unlike `PLAY_BOT_TOKENS`) precisely so a description may contain commas; a description
    * must not itself contain `;`.
    */
  private val EnvVar = "PLAY_OPEN_TO_HUMANS"

  /** One parsed roster entry. */
  final case class Entry(team: String, name: String, description: Option[String])

  /** What happened to one entry when applied. */
  enum Result:
    case Opened(entry: Entry, state: BotCatalogState)
    case Rated(entry: Entry)
    case Retired(entry: Entry)
    case Skipped(entry: Entry) // named an unregistered identity — no row to flag

  /** Parse the roster spec. Blank and malformed entries (missing team or name) are ignored, so a stray separator or a
    * trailing `;` is harmless. Everything after the second `|` is the description (so it may contain `|`), with
    * surrounding whitespace trimmed off each field.
    */
  def parse(spec: String): List[Entry] =
    spec
      .split(';')
      .toList
      .flatMap { raw =>
        raw.split("\\|", 3).map(_.trim) match
          case Array(team, name) if team.nonEmpty && name.nonEmpty              => Some(Entry(team, name, None))
          case Array(team, name, description) if team.nonEmpty && name.nonEmpty =>
            Some(Entry(team, name, Option.when(description.nonEmpty)(description)))
          case _ => None
      }

  /** Env var holding the curated-for-rating roster (#247): `;`-separated `team|name` entries. The same grammar as
    * `PLAY_OPEN_TO_HUMANS` so an operator does not learn two syntaxes; a third field, if present, is ignored — there is
    * no description to set here.
    */
  private val RatedEnvVar = "PLAY_RATED_FOR_HUMANS"

  /** Apply the curated-for-rating roster read from `PLAY_RATED_FOR_HUMANS` (#247). Additive like its sibling: it only
    * ever marks the listed bots, never un-marks one that is absent — so a typo silently narrows what counts as rated
    * rather than silently widening it, and the operator sees a skip line for an unregistered name.
    */
  def applyRatedFromEnv(store: BotStore): IO[List[Result]] =
    applyRated(store, sys.env.getOrElse(RatedEnvVar, ""))

  def applyRated(store: BotStore, spec: String): IO[List[Result]] =
    parse(spec).traverse { entry =>
      store.setRatedForHumans(entry.team, entry.name, ratedForHumans = true).flatMap {
        case Some(_) =>
          IO.println(s"[play][rating] ${entry.team}/${entry.name} is eligible: human games against it may be rated")
            .as(Result.Rated(entry))
        case None =>
          Console[IO]
            .errorln(s"[play][rating] $RatedEnvVar names ${entry.team}/${entry.name}, not a registered bot; skipped")
            .as(Result.Skipped(entry))
      }
    }

  /** Env var holding the retirement roster: `;`-separated `team|name` entries, the same grammar as its two siblings so
    * an operator does not learn a third syntax. A description field, if present, is ignored — there is nothing to
    * describe about a bot being taken out of service.
    */
  private val RetiredEnvVar = "PLAY_RETIRED_BOTS"

  /** The bots named by both an additive roster and the retirement roster — an operator mistake in every case, since
    * retirement runs last and wins. Pure, so `Main` can report it at boot without another env read.
    */
  def conflicts(openSpec: String, ratedSpec: String, retiredSpec: String): List[Entry] =
    val retired = parse(retiredSpec).map(e => (e.team, e.name)).toSet
    (parse(openSpec) ++ parse(ratedSpec))
      .filter(e => retired.contains((e.team, e.name)))
      .distinctBy(e => (e.team, e.name))

  /** Apply the retirement roster read from `PLAY_RETIRED_BOTS`. */
  def applyRetiredFromEnv(store: BotStore): IO[List[Result]] =
    applyRetired(store, sys.env.getOrElse(RetiredEnvVar, ""))

  /** Take each listed, registered bot out of service: off the ladder and out of the human catalog.
    *
    * Both halves are needed and neither implies the other — `on_ladder` stops the scheduler pairing it,
    * `open_to_humans` stops a visitor picking it out of the lobby — so a bot retired from only one is still reachable
    * through the other. The two writes are not ordered against each other because this runs at startup, before the
    * pairing and webhook loops exist; there is no window for either to matter.
    *
    * A skip needs BOTH writes to miss: they key on the same `bots` row, so one `Some` is proof the identity exists and
    * the retirement landed.
    */
  def applyRetired(store: BotStore, spec: String): IO[List[Result]] =
    parse(spec).traverse { entry =>
      for
        rating  <- store.setOnLadder(entry.team, entry.name, onLadder = false)
        catalog <- store.closeToHumans(entry.team, entry.name)
        result  <- (rating, catalog) match
          case (None, None) =>
            Console[IO]
              .errorln(
                s"[play][catalog] $RetiredEnvVar names ${entry.team}/${entry.name}, not a registered bot; skipped"
              )
              .as(Result.Skipped(entry))
          case _ =>
            IO.println(
              s"[play][catalog] retired ${entry.team}/${entry.name}: off the ladder and closed to human games"
            ).as(Result.Retired(entry))
      yield result
    }

  /** Apply the roster read from `PLAY_OPEN_TO_HUMANS`. */
  def applyFromEnv(store: BotStore): IO[List[Result]] =
    apply(store, sys.env.getOrElse(EnvVar, ""))

  /** Open each listed, registered bot to human games (setting its description in the same write), one log line per
    * entry. Returns what happened, for tests and any caller that wants to react; `Main` discards it.
    */
  def apply(store: BotStore, spec: String): IO[List[Result]] =
    parse(spec).traverse { entry =>
      store.openToHumans(entry.team, entry.name, entry.description).flatMap {
        case Some(state) =>
          IO.println(s"[play][catalog] opened ${entry.team}/${entry.name} to human games")
            .as(Result.Opened(entry, state))
        case None =>
          Console[IO]
            .errorln(s"[play][catalog] $EnvVar names ${entry.team}/${entry.name}, not a registered bot; skipped")
            .as(Result.Skipped(entry))
      }
    }
