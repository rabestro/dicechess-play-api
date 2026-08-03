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

  test("applyRated marks exactly the listed registered bots as curated, and skips an unregistered one"):
    for
      store   <- BotStore.inMemory
      _       <- store.register("t", "curated", "hash-curated")
      _       <- store.register("t", "plain", "hash-plain")
      results <- CatalogRoster.applyRated(store, "t|curated;t|ghost")
      curated <- store.ratingOf("t", "curated")
      plain   <- store.ratingOf("t", "plain")
    yield
      assertEquals(results, List(Result.Rated(Entry("t", "curated", None)), Result.Skipped(Entry("t", "ghost", None))))
      assertEquals(curated.map(_.ratedForHumans), Some(true))
      assertEquals(plain.map(_.ratedForHumans), Some(false), "a bot absent from the roster stays uncurated")

  test("the two rosters are independent: marking a bot rated never opens it to humans"):
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "rated-only", "hash-rated-only")
      _     <- CatalogRoster.applyRated(store, "t|rated-only")
      after <- store.ratingOf("t", "rated-only")
      pool  <- store.openToHumansBots
    yield
      assertEquals(after.map(_.ratedForHumans), Some(true))
      assert(
        !pool.contains(Principal.Bot("t", "rated-only")),
        s"eligibility for rating must not advertise the bot in the human catalog, got $pool"
      )

  test("the two rosters are independent: opening a bot to humans never makes its games rated"):
    for
      store <- BotStore.inMemory
      _     <- store.register("t", "open", "hash-open")
      _     <- CatalogRoster.apply(store, "t|open|Plays anyone")
      after <- store.ratingOf("t", "open")
      pool  <- store.openToHumansBots
    yield
      assert(pool.contains(Principal.Bot("t", "open")))
      assertEquals(
        after.map(_.ratedForHumans),
        Some(false),
        "open_to_humans is self-service; rated_for_humans must never ride along with it"
      )
