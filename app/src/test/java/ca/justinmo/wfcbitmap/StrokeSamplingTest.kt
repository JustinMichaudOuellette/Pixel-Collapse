package ca.justinmo.wfcbitmap

import androidx.compose.ui.geometry.Offset
import ca.justinmo.wfcbitmap.ui.sampleSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The stroke sampler behind hold-and-drag painting: a drag reports positions one input event at a time,
 * and a fast drag can cross several cells between two of them, so the line between two reports has to be
 * walked. Pure geometry, so it runs on the JVM.
 */
class StrokeSamplingTest {

  private fun samples(from: Offset, to: Offset, step: Float): List<Offset> {
    val points = mutableListOf<Offset>()
    sampleSegment(from, to, step) { points.add(it) }
    return points
  }

  @Test
  fun theSegmentEndsExactlyOnItsEndPoint() {
    val end = samples(Offset.Zero, Offset(35f, 0f), step = 10f).last()

    assertEquals(35f, end.x, 0.001f)
    assertEquals(0f, end.y, 0.001f)
  }

  @Test
  fun consecutiveSamplesAreNoFurtherApartThanTheStep() {
    val points = samples(Offset(4f, 9f), Offset(300f, 180f), step = 8f)

    var previous = Offset(4f, 9f)
    for (point in points) {
      val gap = (point - previous).getDistance()
      assertTrue("gap of $gap at $point", gap <= 8f + 0.001f)
      previous = point
    }
  }

  @Test
  fun aShortDragIsSplitIntoSamplesNoFurtherApartThanTheStep() {
    // 25px with a 10px step needs three hops, spread evenly rather than three full steps and a stub.
    val points = samples(Offset.Zero, Offset(25f, 0f), step = 10f)

    assertEquals(3, points.size)
    points.forEachIndexed { index, point ->
      assertEquals((index + 1) * 25f / 3f, point.x, 0.001f)
    }
  }

  @Test
  fun aSegmentShorterThanTheStepStillReportsItsEnd() {
    val points = samples(Offset(100f, 100f), Offset(102f, 100f), step = 50f)

    assertEquals(1, points.size)
    assertEquals(102f, points.single().x, 0.001f)
  }

  @Test
  fun aStationarySampleIsReportedOnce() {
    // A press that does not move still has to paint the cell it is on.
    val points = samples(Offset(12f, 7f), Offset(12f, 7f), step = 10f)

    assertEquals(1, points.size)
    assertEquals(12f, points.single().x, 0.001f)
    assertEquals(7f, points.single().y, 0.001f)
  }

  @Test
  fun theSamplingIsEvenlySpacedAlongTheLine() {
    val points = samples(Offset.Zero, Offset(0f, 40f), step = 10f)

    assertEquals(4, points.size)
    points.forEachIndexed { index, point ->
      assertEquals((index + 1) * 10f, point.y, 0.001f)
      assertEquals(0f, point.x, 0.001f)
    }
  }

  @Test(expected = IllegalArgumentException::class)
  fun aNonPositiveStepIsRejected() {
    sampleSegment(Offset.Zero, Offset(1f, 1f), step = 0f) { }
  }

  @Test
  fun aLongDiagonalCrossesEveryCellItPassesThrough() {
    // The stroke sampler exists so that a jump between two touch reports paints the cells between them;
    // walking a 300px diagonal with a 10px step must not skip a 10px cell.
    val points = samples(Offset.Zero, Offset(300f, 300f), step = 10f)

    var previous = Offset.Zero
    for (point in points) {
      assertTrue("sample at $point is off the line", abs(point.x - point.y) < 0.001f)
      assertTrue("sample at $point is too far from $previous", (point - previous).getDistance() <= 10f + 0.001f)
      previous = point
    }
    assertEquals(300f, points.last().x, 0.001f)
  }
}