package dicechess.play.server

import cats.effect.IO
import cats.effect.std.Console
import cats.syntax.all.*
import dicechess.play.store.{BotCatalogState, BotStore}

/** The env-configured catalog roster (ADR-0014): an admin gate that opens named bots to human games at startup,
  * mirroring how `PLAY_BOT_TOKENS` seeds the static bot roster. A registered bot normally opts in itself via
  * `POST /bot/open-to-humans`, but a bot whose registration token was not kept has no way to — so the single author
  * flags it declaratively instead.
  *
  * Idempotent and additive: it only ever OPENS the listed bots (refreshing their description); it never closes one that
  * is absent from the list, so it composes with token-based self-flagging rather than fighting it. An entry naming an
  * unregistered identity is logged and skipped — there is no row to flag.
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
