package dicechess.play.wire

import dicechess.play.core.*
import io.circe.generic.semiauto.deriveCodec
import io.circe.{Codec, Decoder, Encoder, Json, KeyDecoder}

/** JSON wire codecs for the transport-neutral protocol. The WebSocket edge (and later the Bot API) are codecs over
  * these types — the game core never imports JSON.
  *
  * Simple enums serialize as their case name; ADTs use Circe's discriminated-object form (e.g.
  * `{"SubmitTurn":{"moves":[...]}}`).
  */
object Codecs:

  // Total, exception-free enum codec: decode by name lookup, encode as the case name.
  private def nameCodec[A](label: String, values: Array[A]): Codec[A] =
    val byName = values.iterator.map(v => v.toString -> v).toMap
    Codec.from(
      Decoder.decodeString.emap(s => byName.get(s).toRight(s"invalid $label: $s")),
      Encoder.encodeString.contramap(_.toString)
    )

  given Codec[Side]        = nameCodec("Side", Side.values)
  given Codec[Seat]        = nameCodec("Seat", Seat.values)
  given Codec[Termination] = nameCodec("Termination", Termination.values)
  given Codec[PlayerKind]  = nameCodec("PlayerKind", PlayerKind.values)

  // MoveTree is recursive, so it can't be derived: a node encodes as the plain object of its children (sorted for a
  // stable wire), and any JSON object decodes back into nodes.
  private def encodeMoveTree(node: MoveTree): Json =
    Json.obj(node.children.toList.sortBy(_._1).map((move, child) => move -> encodeMoveTree(child))*)

  given Codec[MoveTree] = Codec.from(
    Decoder.recursive(rec => Decoder.decodeMap(using KeyDecoder.decodeKeyString, rec).map(MoveTree.apply)),
    Encoder.instance(encodeMoveTree)
  )

  given Codec[GameResult]      = deriveCodec
  given Codec[GameOver]        = deriveCodec
  given Codec[GameStatus]      = deriveCodec
  given Codec[TimeControl]     = deriveCodec
  given Codec[Seek]            = deriveCodec
  given Codec[Clocks]          = deriveCodec
  given Codec[ClientSeeds]     = deriveCodec
  given Codec[Principal]       = deriveCodec
  given Codec[PublicPlayer]    = deriveCodec
  given Codec[Players]         = deriveCodec
  given Codec[PublicGameState] = deriveCodec
  given Codec[GameMoves]       = deriveCodec
  given Codec[GameCommand]     = deriveCodec
  given Codec[GameEvent]       = deriveCodec
  given Codec[Challenge]       = deriveCodec
  given Codec[BotEvent]        = deriveCodec
