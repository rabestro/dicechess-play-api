package dicechess.play.wire

import dicechess.play.core.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder}

import scala.util.Try

/** JSON wire codecs for the transport-neutral protocol. The WebSocket edge (and later the Bot API) are codecs over
  * these types — the game core never imports JSON.
  *
  * Simple enums serialize as their case name; ADTs use Circe's discriminated-object form (e.g.
  * `{"SubmitTurn":{"moves":[...]}}`).
  */
object Codecs:

  private def nameCodec[A](label: String, read: String => A): Codec[A] =
    Codec.from(
      Decoder.decodeString.emap(s => Try(read(s)).toEither.left.map(_ => s"invalid $label: $s")),
      Encoder.encodeString.contramap(_.toString)
    )

  given Codec[Side]        = nameCodec("Side", Side.valueOf)
  given Codec[Seat]        = nameCodec("Seat", Seat.valueOf)
  given Codec[Termination] = nameCodec("Termination", Termination.valueOf)

  given Codec[GameResult]      = deriveCodec
  given Codec[GameOver]        = deriveCodec
  given Codec[GameStatus]      = deriveCodec
  given Codec[Principal]       = deriveCodec
  given Codec[PublicGameState] = deriveCodec
  given Codec[GameCommand]     = deriveCodec
  given Codec[GameEvent]       = deriveCodec
