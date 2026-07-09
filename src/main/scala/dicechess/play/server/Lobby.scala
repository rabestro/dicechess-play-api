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
    ttl: FiniteDuration,
    botTtl: FiniteDuration,
    maxOpenSeeksPerBot: Int
):
  import Lobby.*

  /** Post an open seek; returns it plus the creator's capability secret (needed to poll its status / cancel it). The
    * seek carries the creator's public face (`kind`/`name`) so humans can see — and choose — bot opponents. A bot's
    * open seeks are capped (`Left` when spent); guests keep the uncapped 15s-poll semantics.
    */
  def create(creator: Principal, timeControl: TimeControl): IO[Either[CreateRejected, (Seek, String)]] =
    (nextId.getAndUpdate(_ + 1), randomSecret, IO.monotonic).flatMapN: (n, secret, now) =>
      val face = PublicPlayer.of(creator)
      val seek = Seek(s"seek-$n", timeControl, face.kind, face.name)
      seeks.modify: current =>
        val openByCreator = current.values.count(e => e.creator == creator && e.state == EntryState.Open)
        if face.kind == PlayerKind.Bot && openByCreator >= maxOpenSeeksPerBot then
          (current, Left(CreateRejected.TooManyOpenSeeks))
        else (current.updated(seek.id, Entry(seek, creator, secret, EntryState.Open, now)), Right((seek, secret)))

  /** Only seeks still `Open` — claimed/matched ones are hidden from the public list. */
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

  /** Accept an open seek: seat a game (assign creator and accepter randomly to White/Black) and return the accepter's
    * game + seat token.
    */
  def accept(id: String, accepter: Principal): IO[Either[Rejected, Match]] =
    (claim(id, accepter), randomBoolean).flatMapN {
      case (Left(rejected), _)               => IO.pure(Left(rejected))
      case (Right((creator, tc)), swapColor) =>
        val (white, black) = if swapColor then (accepter, creator) else (creator, accepter)
        registry.create(white, black, tc).flatMap {
          case Left(error)           => seeks.update(_.removed(id)).as(Left(Rejected.Failed(error)))
          case Right((gameId, room)) =>
            val tokens                      = room.joinTokens
            val (creatorSeat, accepterSeat) = if swapColor then (Seat.Black, Seat.White) else (Seat.White, Seat.Black)
            (tokens.get(creatorSeat), tokens.get(accepterSeat)) match
              case (Some(creatorToken), Some(accepterToken)) =>
                seeks
                  .update(
                    _.updatedWith(id)(_.map(_.copy(state = EntryState.Matched(Match(gameId.value, creatorToken)))))
                  )
                  .as(Right(Match(gameId.value, accepterToken)))
              case _ =>
                seeks.update(_.removed(id)).as(Left(Rejected.Failed("missing seat token")))
        }
    }

  /** Cancel only a still-`Open` seek (secret-gated): a claimed/matched seek has a game in flight, so removing it would
    * strand the creator's seat token. Returns whether a seek was removed.
    */
  def cancel(id: String, secret: String): IO[Boolean] =
    seeks.modify: current =>
      current.get(id) match
        case Some(e) if e.secret == secret && e.state == EntryState.Open => (current.removed(id), true)
        case _                                                           => (current, false)

  /** Drop seeks whose creator hasn't polled within its TTL (gone). Bot seeks get the longer `botTtl`, sized for a
    * poll-only bot on a lazy timer holding a standing offer.
    */
  def sweep: IO[Unit] =
    IO.monotonic.flatMap(now => seeks.update(_.filter((_, e) => now - e.lastSeenAt < ttlOf(e))))

  private def ttlOf(e: Entry): FiniteDuration = if e.seek.kind == PlayerKind.Bot then botTtl else ttl

  /** Background TTL-sweep loop; start once at boot. */
  def sweeper(interval: FiniteDuration = SweepInterval): IO[Unit] = (IO.sleep(interval) *> sweep).foreverM

  /** Atomically move an open seek to `Claimed`, so two accepters can't both seat a game. A creator cannot accept its
    * own seek: the room seats principals, and one principal on both seats has no distinguishable seat.
    */
  private def claim(id: String, accepter: Principal): IO[Either[Rejected, (Principal, TimeControl)]] =
    seeks.modify: current =>
      current.get(id) match
        case None                             => (current, Left(Rejected.NotFound))
        case Some(e) if e.creator == accepter => (current, Left(Rejected.OwnSeek))
        case Some(e)                          =>
          e.state match
            case EntryState.Open =>
              (current.updated(id, e.copy(state = EntryState.Claimed)), Right((e.creator, e.seek.timeControl)))
            case _ => (current, Left(Rejected.AlreadyTaken))

object Lobby:

  /** How long a seek survives without the creator polling it (creator presumed gone). */
  val DefaultTtl: FiniteDuration = 15.seconds

  /** Bot seeks live longer between polls: a poll-only bot on a ~1-minute timer must be able to hold a standing offer
    * without a 15s heartbeat. Bots are authenticated and their open seeks are capped, so the looser TTL is safe.
    */
  val DefaultBotTtl: FiniteDuration = 2.minutes

  /** Cap on one bot's simultaneously OPEN seeks — bounds the lobby against a seek-spamming bot. */
  val DefaultMaxOpenSeeksPerBot: Int = 3

  /** How often the background sweep runs. */
  val SweepInterval: FiniteDuration = 5.seconds

  /** A game id plus the seat token for one side. */
  final case class Match(gameId: String, token: String)

  /** Status a creator's poll reports back. */
  enum SeekStatus:
    case Open
    case Matched(gameId: String, token: String)

  /** Why a create was refused. */
  enum CreateRejected:
    case TooManyOpenSeeks // the bot is at its open-seeks cap

  /** Why an accept was refused. */
  enum Rejected:
    case NotFound
    case AlreadyTaken
    case OwnSeek // the creator itself tried to accept
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

  def create(
      registry: GameRegistry,
      ttl: FiniteDuration = DefaultTtl,
      botTtl: FiniteDuration = DefaultBotTtl,
      maxOpenSeeksPerBot: Int = DefaultMaxOpenSeeksPerBot
  ): IO[Lobby] =
    (Ref.of[IO, Map[String, Entry]](Map.empty), Ref.of[IO, Long](0L))
      .mapN((seeks, nextId) => new Lobby(seeks, registry, nextId, ttl, botTtl, maxOpenSeeksPerBot))

  private def randomSecret: IO[String] = IO:
    val bytes = new Array[Byte](16)
    SecureRandom().nextBytes(bytes)
    bytes.map("%02x".format(_)).mkString

  private def randomBoolean: IO[Boolean] = IO(SecureRandom().nextBoolean())
