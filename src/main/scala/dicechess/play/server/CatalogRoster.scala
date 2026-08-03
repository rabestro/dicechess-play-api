package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.store.{BotCatalogState, BotStore}

/** The env-configured operator rosters: an admin gate applied at startup, mirroring how `PLAY_BOT_TOKENS` seeds the
  * static bot roster. Two independent rosters, deliberately two env vars rather than one grammar with a flag column —
  * they answer different questions and one is far more consequential than the other:
  *   - `PLAY_OPEN_TO_HUMANS` (ADR-0014) — may a visitor start a game against this bot;
  *   - `PLAY_RATED_FOR_HUMANS` (#247) — does such a game count for rating. This one exists ONLY here because it must
  *     not be self-service: a bot author who could set it would register a weak bot and farm rating off it (see V15).
  *
  * The catalog roster exists because a registered bot normally opts in itself via `POST /bot/open-to-humans`, and a bot
  * whose registration token was not kept has no way to — so the single author flags it declaratively instead.
  *
  * Both rosters are idempotent and additive: they only ever SET the flag on the bots listed; neither clears it on a bot
  * absent from the list, so they compose with token-based self-flagging rather than fighting it, and a typo narrows
  * rather than widens what is enabled. An entry naming an unregistered identity is logged and skipped — no row to flag.
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
    case Rated(entry: Entry)   // marked curated for rating (#247)
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
          IO.println(s"[play][rating] ${entry.team}/${entry.name} is curated: human games against it are rated")
            .as(Result.Rated(entry))
        case None =>
          Console[IO]
            .errorln(s"[play][rating] $RatedEnvVar names ${entry.team}/${entry.name}, not a registered bot; skipped")
            .as(Result.Skipped(entry))
      }
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
