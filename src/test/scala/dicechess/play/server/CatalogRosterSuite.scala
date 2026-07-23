package dicechess.play.server

import dicechess.play.core.Principal
import dicechess.play.store.{BotCatalogState, BotStore}

/** Parsing edge cases for the env roster, and that applying it opens exactly the listed registered bots (with their
  * descriptions) while skipping an unregistered identity — the admin gate for bots that cannot self-flag (ADR-0014).
  */
class CatalogRosterSuite extends munit.CatsEffectSuite:

  import CatalogRoster.{Entry, Result}

  test("parse handles bare entries, descriptions with commas, whitespace, and junk"):
    assertEquals(CatalogRoster.parse(""), Nil)
    assertEquals(CatalogRoster.parse("   "), Nil)
    assertEquals(CatalogRoster.parse("gcp|scala-monte-carlo"), List(Entry("gcp", "scala-monte-carlo", None)))
    assertEquals(
      CatalogRoster.parse(" gcp | expectimax-onnx-3 | ONNX expectimax v3, with book "),
      List(Entry("gcp", "expectimax-onnx-3", Some("ONNX expectimax v3, with book")))
    )
    // stray separators, an empty entry, a name-less entry, and a bare token are all ignored
    assertEquals(
      CatalogRoster.parse("a|b|first, one;c|d;;garbage;e|"),
      List(Entry("a", "b", Some("first, one")), Entry("c", "d", None))
    )

  test("apply opens exactly the listed registered bots with their descriptions, and skips an unregistered one"):
    for
      store   <- BotStore.inMemory
      _       <- store.register("t", "alpha", "hash-alpha")
      _       <- store.register("t", "beta", "hash-beta")
      results <- CatalogRoster.apply(store, "t|alpha|Aggressive, with book;t|beta;t|ghost")
      pool    <- store.openToHumansBots
    yield
      assertEquals(
        results,
        List(
          Result.Opened(
            Entry("t", "alpha", Some("Aggressive, with book")),
            BotCatalogState(openToHumans = true, Some("Aggressive, with book"))
          ),
          Result.Opened(Entry("t", "beta", None), BotCatalogState(openToHumans = true, None)),
          Result.Skipped(Entry("t", "ghost", None))
        )
      )
      assert(pool.contains(Principal.Bot("t", "alpha")), s"alpha must be opened, got $pool")
      assert(pool.contains(Principal.Bot("t", "beta")), s"beta must be opened, got $pool")
      assert(!pool.contains(Principal.Bot("t", "ghost")), s"ghost must be skipped, got $pool")
