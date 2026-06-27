package dicechess.play.core

import cats.effect.IO

import java.util.UUID

/** Opaque game identifier (a random UUID string). */
opaque type GameId = String

object GameId:
  def apply(value: String): GameId = value
  def random: IO[GameId]           = IO(UUID.randomUUID().toString)

  extension (id: GameId) def value: String = id
