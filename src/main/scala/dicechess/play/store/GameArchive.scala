package dicechess.play.store

import cats.syntax.all.*
import dicechess.play.core.*
import dicechess.play.dice.DiceSource
import dicechess.play.game.EngineOps
import dicechess.play.ingest.PlaysiteIngest
import dicechess.play.wire.Codecs.given
import io.circe.Json
import io.circe.syntax.*

/** The immutable, sanitized history record for a finished game (#177) — play's own durable representation of game
  * history, independent of the analytics wire contract (`PlaysiteIngest`) and of `games` snapshot retention (#179
  * prunes ended snapshots once this becomes the serving path for replay, `GET /games/{id}/history`, #178).
  *
  * Unlike `GameSnapshot` this drops the live secrets a client should never retain past the game (seat join tokens);
  * unlike the analytics payload it keeps raw `external_id`s (this table is server-private — anonymization is the
  * READING endpoint's job, via the same `PublicPlayer` rules the live wire uses) and the full fairness block (commit +
  * server seed + client seeds), stored unconditionally. Gating what a caller actually SEES (the CRN partner-ended
  * reveal check, #115) happens at read time in the endpoint, not here — gating at write time would need a second write
  * once a slower partner game finishes, which is strictly worse.
  */
object GameArchive:

  /** The archive payload for a finished, non-aborted game, or `None` — mirrors `PlaysiteIngest.payload`'s own
    * active/aborted exclusions exactly, so the two representations of "should this game be recorded" never drift. Also
    * `None` if `players` is unexpectedly missing a seat (a malformed snapshot, not the normal "still active" case) —
    * the same anomaly `PgGameStore.finishedGameOf` guards against and logs; guarding here too means a malformed row
    * simply has no archive, never one with a null seat.
    */
  def payload(snapshot: GameSnapshot): Option[Json] =
    snapshot.status match
      case GameStatus.Active                                  => None
      case GameStatus.Ended(GameOver(_, Termination.Aborted)) => None
      case GameStatus.Ended(GameOver(result, termination))    =>
        (snapshot.players.get(Seat.White), snapshot.players.get(Seat.Black)).mapN { (white, black) =>
          Json.obj(
            "started_at"      -> snapshot.createdAtEpochMs.asJson,
            "rated"           -> snapshot.rated.getOrElse(false).asJson,
            "pairing_id"      -> snapshot.pairingId.asJson,
            "partner_game_id" -> snapshot.partnerGameId.asJson,
            "time_control"    -> snapshot.timeControl.asJson,
            "result"          -> PlaysiteIngest.resultOf(result).asJson,
            "termination"     -> PlaysiteIngest.terminationOf(termination).asJson,
            "players"         -> Json.obj("white" -> white.externalId.asJson, "black" -> black.externalId.asJson),
            "initial_dfen"    -> EngineOps.InitialDfen.asJson, // every game starts here (GameRegistry never passes a
            // custom DFEN) — same invariant PlaysiteIngest's own start-position constant relies on.
            "turns"    -> snapshot.turns.map(t => Json.fromJsonObject(TurnRecord.json(t))).asJson,
            "fairness" -> Json.obj(
              "commit"       -> commitOf(snapshot.serverSeed).asJson,
              "server_seed"  -> snapshot.serverSeed.asJson,
              "client_seeds" -> Json.obj(
                "white" -> seedFor(snapshot.clientSeeds, Seat.White, white).asJson,
                "black" -> seedFor(snapshot.clientSeeds, Seat.Black, black).asJson
              )
            )
          )
        }

  /** `None` only if `serverSeed` fails to parse as hex — practically impossible (it is always CSPRNG-generated), but a
    * parse failure must not lose the archive row entirely: the row is still written, just without a computed
    * commitment.
    */
  private def commitOf(hexSeed: String): Option[String] =
    DiceSource.fromHexSeed(hexSeed).toOption.map(_.commit)

  /** Mirrors `GameRoom.Session.seedFor`: the seed ACTUALLY folded into the dice, not just what was submitted. A seat
    * that never submitted a client seed before the grace elapsed falls back to its own external id — `clientSeeds`
    * alone (as persisted on `GameSnapshot`) would be missing that seat's entry, understating the fairness block.
    */
  private def seedFor(clientSeeds: Map[Seat, String], seat: Seat, player: Principal): String =
    clientSeeds.getOrElse(seat, player.externalId)
