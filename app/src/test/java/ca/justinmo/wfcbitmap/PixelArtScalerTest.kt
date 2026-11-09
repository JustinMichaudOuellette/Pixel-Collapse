package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.output.OutputScale
import ca.justinmo.wfcbitmap.output.OutputSettings
import ca.justinmo.wfcbitmap.output.PixelArtScaler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which save sizes each scaling algorithm offers, and keeping a chosen size valid across a switch. */
class PixelArtScalerTest {

  @Test
  fun blocksCanMakeEverySize() {
    assertEquals(OutputScale.entries.toSet(), PixelArtScaler.Nearest.supportedScales)
  }

  @Test
  fun epxOffersOnlyItsPowerOfTwoSizes() {
    assertEquals(
      setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen),
      PixelArtScaler.Epx.supportedScales,
    )
  }

  @Test
  fun scale3xOffersItsThreeTimesFamily() {
    assertEquals(
      setOf(OutputScale.Three, OutputScale.Six, OutputScale.Nine),
      PixelArtScaler.Scale3x.supportedScales,
    )
  }

  @Test
  fun eagleOffersItsDoublingSizes() {
    assertEquals(
      setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen),
      PixelArtScaler.Eagle.supportedScales,
    )
  }

  @Test
  fun smoothOffersItsDoublingSizes() {
    assertEquals(
      setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen),
      PixelArtScaler.Smooth.supportedScales,
    )
  }

  @Test
  fun hq2xOffersItsDoublingSizes() {
    assertEquals(
      setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen),
      PixelArtScaler.Hq2x.supportedScales,
    )
  }

  @Test
  fun theSaiFamilyOffersItsDoublingSizes() {
    for (scaler in listOf(PixelArtScaler.Sai, PixelArtScaler.SuperSai, PixelArtScaler.SuperEagle)) {
      assertEquals(
        "${scaler.label} sizes",
        setOf(OutputScale.Two, OutputScale.Four, OutputScale.Sixteen),
        scaler.supportedScales,
      )
    }
  }

  @Test
  fun switchingToAUpScalerMovesOneTimesToASupportedSize() {
    val before = OutputSettings(scale = OutputScale.One, scaler = PixelArtScaler.Nearest)

    val after = before.copy(scaler = PixelArtScaler.Epx).withSupportedScale()

    assertEquals(OutputScale.Two, after.scale)
    assertTrue(after.scale in after.scaler.supportedScales)
  }

  @Test
  fun switchingBackToBlocksLeavesTheSizeAlone() {
    val before = OutputSettings(scale = OutputScale.Four, scaler = PixelArtScaler.Epx)

    val after = before.copy(scaler = PixelArtScaler.Nearest).withSupportedScale()

    assertEquals(OutputScale.Four, after.scale)
  }

  @Test
  fun switchingToScale3xFromAnUnsupportedSizePicksItsSmallest() {
    val before = OutputSettings(scale = OutputScale.Four, scaler = PixelArtScaler.Epx)

    val after = before.copy(scaler = PixelArtScaler.Scale3x).withSupportedScale()

    assertEquals(OutputScale.Three, after.scale)
  }

  @Test
  fun scale3xKeepsASizeItCanStillMake() {
    val before = OutputSettings(scale = OutputScale.Nine, scaler = PixelArtScaler.Nearest)

    val after = before.copy(scaler = PixelArtScaler.Scale3x).withSupportedScale()

    assertEquals(OutputScale.Nine, after.scale)
  }
}