package dicechess.play.server

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import dicechess.play.core.*

import java.security.SecureRandom
import scala.concurrent.duration.*

/** In-memory pool of open lobby seeks (polling model). A seek is a public game offer anyone can accept; the creator
  * holds a capability `secret` to poll its status and cancel it. Liveness is by TTL: the creator's status poll
  * refreshes the seek, and a background [[sweeper]] drops seeks whose creator has gone quiet — so an abandoned tab
  * never strands a seek.
  *
  * Accepting is atomic (open → claimed) so two accepters can't both seat a game; the game is created with the creator
  * on White and the accepter on Black, and each side's seat token reaches the right player (the accepter in the accept
  * response, the creator on its next status poll).
  */
final class Lobby private (
    seeks: Ref[IO, Map[String, Lobby.Entry]],
    registry: GameRegistry,
    nextId: Ref[IO, Long],
    ttl: FiniteDuration
):
  import Lobby.*

  /** Post an open seek; returns it plus the creator's capability secret (needed to poll its status / cancel it). */
  def create(creator: Principal, timeControl: TimeControl): IO[(Seek, String)] =
    for
      n      <- nextId.getAndUpdate(_ + 1)
      secret <- randomSecret
      now    <- IO.monotonic
      seek = Seek(s"seek-$n", timeControl)
      _ <- seeks.update(_.updated(seek.id, Entry(seek, creator, secret, EntryState.Open, now)))
    yield (seek, secret)

  /** The open (unclaimed) seeks, for the public lobby list. */
  def list: IO[List[Seek]] =
    seeks.get.map(_.values.collect { case e if e.state == EntryState.Open => e.seek }.toList.sortBy(_.id))

  /** Poll a seek's status with the creator's secret, refreshing its liveness. `None` = unknown seek or wrong secret. */
  def status(id: String, secret: String): IO[Option[SeekStatus]] =
    IO.monotonic.flatMap: now =>
      seeks.modify: current =>
        current.get(id) match
          case Some(e) if e.secret == secret =>
            val status = e.state match
              case EntryState.Matched(m) => SeekStatus.Matched(m.gameId, m.token)
              case _                     => SeekStatus.Open
            (current.updated(id, e.copy(lastSeenAt = now)), Some(status))
          case _ => (current, None)

  /** Accept an open seek: seat a game (creator = White, accepter = Black) and return the accepter's game + seat token.
    */
  def accept(id: String, accepter: Principal): IO[Either[Rejected, Match]] =
    claim(id).flatMap {
      case Left(rejected)       => IO.pure(Left(rejected))
      case Right((creator, tc)) =>
        registry.create(creator, accepter, tc).flatMap {
          case Left(error)           => seeks.update(_.removed(id)).as(Left(Rejected.Failed(error)))
          case Right((gameId, room)) =>
            val tokens        = room.joinTokens
            val creatorToken  = tokens.getOrElse(Seat.White, "")
            val accepterToken = tokens.getOrElse(Seat.Black, "")
            seeks
              .update(_.updatedWith(id)(_.map(_.copy(state = EntryState.Matched(Match(gameId.value, creatorToken))))))
              .as(Right(Match(gameId.value, accepterToken)))
        }
    }

  /** The creator cancels its seek (capability secret required). Returns whether a seek was removed. */
  def cancel(id: String, secret: String): IO[Boolean] =
    seeks.modify: current =>
      current.get(id) match
        case Some(e) if e.secret == secret => (current.removed(id), true)
        case _                             => (current, false)

  /** Drop seeks whose creator hasn't polled within the TTL (gone). */
  def sweep: IO[Unit] =
    IO.monotonic.flatMap(now => seeks.update(_.filter((_, e) => now - e.lastSeenAt < ttl)))

  /** Background TTL-sweep loop; start once at boot. */
  def sweeper(interval: FiniteDuration = SweepInterval): IO[Unit] = (IO.sleep(interval) *> sweep).foreverM

  /** Atomically move an open seek to `Claimed`, so two accepters can't both seat a game. */
  private def claim(id: String): IO[Either[Rejected, (Principal, TimeControl)]] =
    seeks.modify: current =>
      current.get(id) match
        case None    => (current, Left(Rejected.NotFound))
        case Some(e) =>
          e.state match
            case EntryState.Open =>
              (current.updated(id, e.copy(state = EntryState.Claimed)), Right((e.creator, e.seek.timeControl)))
            case _ => (current, Left(Rejected.AlreadyTaken))

object Lobby:

  /** How long a seek survives without the creator polling it (creator presumed gone). */
  val DefaultTtl: FiniteDuration = 15.seconds

  /** How often the background sweep runs. */
  val SweepInterval: FiniteDuration = 5.seconds

  /** A game id plus the seat token for one side. */
  final case class Match(gameId: String, token: String)

  /** Status a creator's poll reports back. */
  enum SeekStatus:
    case Open
    case Matched(gameId: String, token: String)

  /** Why an accept was refused. */
  enum Rejected:
    case NotFound
    case AlreadyTaken
    case Failed(reason: String)

  private enum EntryState:
    case Open
    case Claimed
    case Matched(m: Match)

  final private case class Entry(
      seek: Seek,
      creator: Principal,
      secret: String,
      state: EntryState,
      lastSeenAt: FiniteDuration
  )

  def create(registry: GameRegistry, ttl: FiniteDuration = DefaultTtl): IO[Lobby] =
    (Ref.of[IO, Map[String, Entry]](Map.empty), Ref.of[IO, Long](0L))
      .mapN((seeks, nextId) => new Lobby(seeks, registry, nextId, ttl))

  private def randomSecret: IO[String] = IO:
    val bytes = new Array[Byte](16)
    SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString
