package dicechess.play.dice

import java.security.MessageDigest

class DiceSourceSuite extends munit.FunSuite:

  private val seed: Array[Byte] = (0 until 32).map(_.toByte).toArray

  private def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString

  test("every roll is three dice in 1..6"):
    val source = DiceSource.commitReveal(seed, "alice", "bob")
    (0L until 200L).foreach: ply =>
      val dice = source.roll(ply)
      assertEquals(dice.size, 3, s"ply $ply")
      assert(dice.forall(d => d >= 1 && d <= 6), s"out of range at ply $ply: $dice")

  test("rolls are deterministic for the same seeds"):
    val a = DiceSource.commitReveal(seed, "alice", "bob")
    val b = DiceSource.commitReveal(seed, "alice", "bob")
    assertEquals((0L until 50L).map(a.roll).toList, (0L until 50L).map(b.roll).toList)

  test("commit is the SHA-256 of the revealed seed"):
    val source   = DiceSource.commitReveal(seed, "alice", "bob")
    val revealed = source.reveal.grouped(2).map(h => Integer.parseInt(h, 16).toByte).toArray
    assertEquals(source.commit, sha256Hex(revealed))

  test("different client seeds produce different roll sequences"):
    val a = DiceSource.commitReveal(seed, "alice", "bob")
    val c = DiceSource.commitReveal(seed, "carol", "dave")
    assertNotEquals((0L until 50L).map(a.roll).toList, (0L until 50L).map(c.roll).toList)

  test("client-seed boundaries do not collide (canonical encoding)"):
    // ("a|b","c") and ("a","b|c") must not hash to the same dice stream.
    val a = DiceSource.commitReveal(seed, "a|b", "c")
    val b = DiceSource.commitReveal(seed, "a", "b|c")
    assertNotEquals((0L until 30L).map(a.roll).toList, (0L until 30L).map(b.roll).toList)
