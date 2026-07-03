package dicechess.play.core

/** A public, open game offer in the lobby that anyone may accept. `kind`/`name` say WHO is offering — so a human can
  * see (and choose) a bot opponent — without ever leaking private ids: bots show their public team-qualified name,
  * humans stay anonymous. The creator's principal and capability secret stay server-side (see the server `Lobby`).
  */
final case class Seek(id: String, timeControl: TimeControl, kind: PlayerKind, name: Option[String])
