package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

import androidx.compose.ui.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The gallery is the pattern store now: a pattern save has to appear in the app's own picker, come back
 * with its drawing and palette, and be removable from the gallery.
 */
@RunWith(AndroidJUnit4::class)
class PatternGalleryTest {

  @Test
  fun savedPatternAppearsInThePickerAndCanBeDeleted() = runBlocking {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val gallery = PatternGallery(context)
    val palette = Palette(
      id = "test-palette-${System.currentTimeMillis()}",
      name = "Test palette",
      colors = listOf(Color(0xFFBE4A2F), Color(0xFF3E8948)),
    )
    val snapshot = EditorSnapshot(
      columns = 8,
      rows = 7,
      pixels = IntArray(56) { index -> if (index % 2 == 0) 0xFFBE4A2F.toInt() else 0xFF3E8948.toInt() },
      seed = 7,
      colorArgb = 0xFFBE4A2F.toInt(),
      aspectName = OutputAspect.Portrait9x16.name,
      previewName = PreviewScale.Half.name,
      scaleName = OutputScale.One.name,
      scalerName = PixelArtScaler.Epx.name,
      kernelName = KernelSize.Three.name,
      wrapEdges = true,
      mirrorHorizontal = true,
      mirrorVertical = false,
      paletteId = palette.id,
    )

    val previewWidth = 16
    val previewHeight = 14
    val preview = IntArray(previewWidth * previewHeight) { index ->
      if (index % 3 == 0) 0xFFF2AA4C.toInt() else 0xFF101820.toInt()
    }
    val uri = gallery.save(palette, snapshot, preview, previewWidth, previewHeight)
    assertNotNull("the pattern was not written to the gallery", uri)
    val listed = gallery.list()
    val saved = listed.firstOrNull { it.metadata.paletteId == palette.id }
    assertNotNull("the saved pattern is missing from the picker", saved)
    assertEquals(snapshot, saved!!.snapshot)
    assertEquals(palette.colors, saved.metadata.paletteColors)
    assertTrue("the PNG preview must be the saved image", saved.previewPixels!!.contentEquals(preview))

    assertTrue(gallery.delete(saved.metadata.id))
    assertTrue(
      "a deleted pattern must disappear from the picker",
      gallery.list().none { it.metadata.paletteId == palette.id },
    )
  }
}
