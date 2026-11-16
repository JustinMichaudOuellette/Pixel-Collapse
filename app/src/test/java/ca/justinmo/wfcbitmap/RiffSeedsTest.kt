package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.video.DEFAULT_RIFF_PATTERNS
import ca.justinmo.wfcbitmap.video.MAX_RIFF_PATTERNS
import ca.justinmo.wfcbitmap.video.MIN_RIFF_PATTERNS
import ca.justinmo.wfcbitmap.video.riffSeed
import ca.justinmo.wfcbitmap.video.riffSeeds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The seed sequence a Riff clip solves: one distinct seed per frame, starting on the editor's own. */
class RiffSeedsTest {

  @Test
  fun theFirstFrameKeepsTheEditorsSeed() {
    assertEquals(12345, riffSeeds(baseSeed = 12345, count = 24).first())
  }

  @Test
  fun everyFrameGetsASeedNoEarlierFrameUsed() {
    for (count in MIN_RIFF_PATTERNS..MAX_RIFF_PATTERNS) {
      val seeds = riffSeeds(baseSeed = 7, count = count)
      assertEquals("$count frames", count, seeds.size)
      assertEquals("$count frames", count, seeds.toSet().size)
    }
  }

  @Test
  fun consecutiveFramesNeverShareASeed() {
    val seeds = riffSeeds(baseSeed = -99, count = MAX_RIFF_PATTERNS)
    for (index in 1 until seeds.size) {
      assertNotEquals("frame $index", seeds[index - 1], seeds[index])
    }
  }

  @Test
  fun theSequenceIsDeterministicAndAPrefixOfALongerOne() {
    val long = riffSeeds(baseSeed = 2026, count = MAX_RIFF_PATTERNS)
    assertEquals(long, riffSeeds(baseSeed = 2026, count = MAX_RIFF_PATTERNS))
    assertEquals(long.take(10), riffSeeds(baseSeed = 2026, count = 10))
  }

  @Test
  fun aDifferentBaseSeedGivesADifferentRiff() {
    val first = riffSeeds(baseSeed = 1, count = 12)
    val second = riffSeeds(baseSeed = 2, count = 12)
    assertNotEquals(first, second)
    assertTrue(first.toSet().intersect(second.toSet()).size < first.size)
  }

  @Test
  fun theMixerMovesFarBetweenFrames() {
    // A seed that merely stepped by one would solve nearly the same random stream each frame; the
    // mixer is what makes consecutive arrangements look unrelated.
    val seeds = riffSeeds(baseSeed = 0, count = 8)
    for (index in 1 until seeds.size) {
      assertTrue(
        "frame $index is ${seeds[index]}, previous is ${seeds[index - 1]}",
        Math.abs(seeds[index].toLong() - seeds[index - 1].toLong()) > 1_000_000L,
      )
    }
  }

  @Test
  fun anEmptyOrSingleFrameRiffIsStillWellDefined() {
    assertTrue(riffSeeds(baseSeed = 42, count = 0).isEmpty())
    assertEquals(listOf(42), riffSeeds(baseSeed = 42, count = 1))
  }

  @Test
  fun theDefaultIsInsideTheOfferedRange() {
    assertTrue(DEFAULT_RIFF_PATTERNS in MIN_RIFF_PATTERNS..MAX_RIFF_PATTERNS)
    assertNotEquals(riffSeed(baseSeed = 0, index = 0), riffSeed(baseSeed = 0, index = 1))
  }
}
