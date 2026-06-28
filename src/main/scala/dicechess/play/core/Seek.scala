package dicechess.play.core

/** A public, open game offer in the lobby that anyone may accept. Only the id and the time control are public; the
  * creator's identity and capability secret stay server-side (see the server `Lobby`).
  */
final case class Seek(id: String, timeControl: TimeControl)
