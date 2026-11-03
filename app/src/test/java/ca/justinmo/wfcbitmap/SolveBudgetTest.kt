package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a solve is given before the drawing is reported as one that cannot be tiled.
 *
 * The rule is that a solve gets four times what the last one cost, not a fixed timeout: a search that has
 * already run that long, against recent evidence of how fast this device solves, is not going to produce a
 * picture while the drawing is still being looked at. These tests pin the multiple and the two clamps,
 * which are the whole of the policy -- the search itself is in C and cannot run here.
 */
class SolveBudgetTest {

  @Test
  fun aSolveGetsFourTimesWhatTheLastOneCost() {
    assertEquals("a solve that took 80 ms buys 320 ms", 320L, WfcGenerator.solveBudgetMillis(80))
    assertEquals("a solve that took 250 ms buys 1000 ms", 1_000L, WfcGenerator.solveBudgetMillis(250))
  }

  @Test
  fun theFirstGenerationAfterLaunchGetsAFixedBudget() {
    // Nothing has been measured yet, so the multiple has nothing to multiply. A slow but possible solve
    // is about a second, which is what it may have.
    assertEquals(1_000L, WfcGenerator.solveBudgetMillis(0))
    assertEquals(1_000L, WfcGenerator.solveBudgetMillis(-5))
  }

  @Test
  fun aVeryFastSolveStillBuysEnoughTimeToNotGiveUpOnAHiccup() {
    // Four times a 5 ms collapse is 20 ms, which a GC pause alone could exceed: the floor is what keeps a
    // drawing that tiles perfectly well from being reported as untileable by a scheduling artefact.
    assertEquals(150L, WfcGenerator.solveBudgetMillis(5))
    assertEquals(150L, WfcGenerator.solveBudgetMillis(1))
  }

  @Test
  fun oneSlowGenerationCannotMakeTheNextOneWaitForIt() {
    // Without the ceiling a pathological generation would raise every later budget towards a wait, which is
    // exactly what this policy exists to remove.
    assertEquals(4_000L, WfcGenerator.solveBudgetMillis(1_200))
    assertEquals(4_000L, WfcGenerator.solveBudgetMillis(60_000))
  }

  @Test
  fun theBudgetNeverFallsAsTheLastSolveGetsSlower() {
    // The policy has to be monotonic, or an edit that made the solve slower could *shorten* the wait it
    // gets on the next one. (The very first generation is exempt: it is a guess, not a measurement, and a
    // measured fast solve is allowed to buy less than the initial second.)
    var previous = WfcGenerator.solveBudgetMillis(1)
    for (millis in 2L..5_000L step 7L) {
      val budget = WfcGenerator.solveBudgetMillis(millis)
      assertTrue("budget fell from $previous to $budget at $millis ms", budget >= previous)
      previous = budget
    }
  }
}