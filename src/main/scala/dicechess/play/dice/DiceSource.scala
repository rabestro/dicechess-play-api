package dicechess.play.dice

import cats.effect.IO

import java.security.{MessageDigest, SecureRandom}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.collection.mutable.ListBuffer

/** Source of a turn's three dice. The server is authoritative over the RNG.
  *
  * `roll` is deterministic and **position-independent** — it depends only on the seeds and the ply index, never on the
  * moves played. That is what lets a tournament replay the exact same dice to a colour-swapped mirror game to cancel
  * luck.
  */
trait DiceSource:
  /** The three dice (each in 1..6) for the given ply. */
  def roll(ply: Long): List[Int]

  /** SHA-256 of the server seed, published at game start (the commitment). */
  def commit: String

  /** The server seed (hex), published at game end so any roll can be re-verified. */
  def reveal: String

object DiceSource:

  private val DiceCount = 3
  private val Faces     = 6
  // Largest multiple of 6 below 256; reject bytes >= this to avoid modulo bias.
  private val Cutoff = 252

  /** Commit-reveal dice: `roll(ply) = HMAC-SHA256(serverSeed, clientW|clientB|ply)` mapped to three unbiased values in
    * 1..6. Commit the SHA-256 of `serverSeed` before the game, reveal `serverSeed` after — anyone can then re-derive
    * every roll.
    */
  def commitReveal(serverSeed: Array[Byte], clientSeedW: String, clientSeedB: String): DiceSource =
    val seed = serverSeed.clone()
    new DiceSource:
      def commit: String             = hex(sha256(seed))
      def reveal: String             = hex(seed)
      def roll(ply: Long): List[Int] =
        diceFrom(hmac(seed, s"$clientSeedW|$clientSeedB|$ply".getBytes("UTF-8")))

  /** Mint a fresh commit-reveal source with a 32-byte CSPRNG server seed. */
  def newCommitReveal(clientSeedW: String, clientSeedB: String): IO[DiceSource] =
    IO:
      val seed = new Array[Byte](32)
      SecureRandom().nextBytes(seed)
      commitReveal(seed, clientSeedW, clientSeedB)

  private def diceFrom(bytes: Array[Byte]): List[Int] =
    val dice = ListBuffer.empty[Int]
    var i    = 0
    while dice.size < DiceCount && i < bytes.length do
      val b = bytes(i) & 0xff
      if b < Cutoff then dice += (b % Faces) + 1
      i += 1
    // 32 HMAC bytes with ~98.4% acceptance make exhaustion effectively impossible; this
    // deterministic fallback only exists so the function is total.
    var j = 0
    while dice.size < DiceCount do
      dice += ((bytes(j % bytes.length) & 0xff) % Faces) + 1
      j += 1
    dice.toList

  private def hmac(key: Array[Byte], message: Array[Byte]): Array[Byte] =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    mac.doFinal(message)

  private def sha256(data: Array[Byte]): Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(data)

  private def hex(bytes: Array[Byte]): String =
    bytes.map(b => f"${b & 0xff}%02x").mkString
