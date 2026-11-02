package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Patterns are the drawing plus the palette it was made with, kept in the app's private files as a
 * lossless PNG.
 *
 * The metadata is readable on its own so the load dialog can list patterns without their pixels, and it
 * carries the palette's id, name and colours so loading can put the palette back.
 */
class PatternStoreTest {

  @get:Rule
  val temporary = TemporaryFolder()

  private lateinit var store: PatternStore

  @Before
  fun setUp() {
    store = PatternStore.forDirectory(temporary.root)
  }

  @Test
  fun aSavedPatternComesBackWithItsDrawingAndPalette() {
    val palette = Palette(
      id = "endesga-32.txt",
      name = "Endesga 32",
      colors = listOf(Color(0xFFBE4A2F), Color(0xFF3E8948)),
    )
    val snapshot = snapshot()

    val metadata = store.save("My Pattern", palette, snapshot)

    assertNotNull(metadata)
    assertEquals("My Pattern", metadata!!.name)
    assertEquals(palette.id, metadata.paletteId)
    assertEquals(palette.name, metadata.paletteName)
    assertEquals(palette.colors, metadata.paletteColors)
    assertEquals(snapshot.columns, metadata.columns)
    assertEquals(snapshot.rows, metadata.rows)

    val loaded = store.load(metadata.id)
    assertNotNull(loaded)
    assertEquals(metadata, loaded!!.metadata)
    assertEquals(snapshot, loaded.snapshot)
  }

  @Test
  fun theListReadsMetadataWithoutNeedingTheDrawing() {
    val palette = Palette("pico-8.txt", "Pico-8", listOf(Color(0xFF000000)))
    store.save("First", palette, snapshot(seed = 1))
    store.save("Second", palette, snapshot(seed = 2))

    val patterns = store.list()

    assertEquals(2, patterns.map { it.name }.toSet().size)
    assertTrue(patterns.any { it.name == "First" && it.paletteId == "pico-8.txt" })
    assertTrue(patterns.any { it.name == "Second" && it.paletteName == "Pico-8" })
  }

  @Test
  fun aPatternIsSavedInsideThePrivateDirectory() {
    val palette = Palette("pico-8.txt", "Pico-8", listOf(Color(0xFF000000)))
    val metadata = store.save("Stored", palette, snapshot())

    assertNotNull(metadata)
    val file = java.io.File(temporary.root, "patterns/${metadata!!.id}")
    assertTrue("the pattern file exists", file.isFile)
    assertTrue("it is inside the app's private files", file.path.startsWith(temporary.root.path))
  }

  @Test
  fun aBlankNameIsNotWritten() {
    val palette = Palette("pico-8.txt", "Pico-8", listOf(Color(0xFF000000)))

    assertNull(store.save("   ", palette, snapshot()))
    assertTrue(store.list().isEmpty())
  }

  @Test
  fun aPatternIsALosslessPngWithItsMetadata() {
    val palette = Palette(
      id = "endesga-32.txt",
      name = "Endesga 32",
      colors = listOf(Color(0xFFBE4A2F), Color(0xFF3E8948)),
    )
    val snapshot = snapshot()
    val metadata = store.save("Png Pattern", palette, snapshot)
    assertNotNull(metadata)
    assertTrue("a pattern is a .png", metadata!!.id.endsWith(".png"))

    val file = java.io.File(temporary.root, "patterns/${metadata.id}")
    val bytes = file.readBytes()
    assertEquals("PNG signature", 0x89.toByte(), bytes[0])
    assertEquals('P'.code.toByte(), bytes[1])
    assertEquals('N'.code.toByte(), bytes[2])
    assertEquals('G'.code.toByte(), bytes[3])

    val image = javax.imageio.ImageIO.read(file)
    assertNotNull("an ordinary image reader can open it", image)
    assertEquals(snapshot.columns, image!!.width)
    assertEquals(snapshot.rows, image.height)
    for (row in 0 until snapshot.rows) {
      for (column in 0 until snapshot.columns) {
        assertEquals(
          "pixel ($column, $row) is lossless",
          snapshot.pixels[row * snapshot.columns + column],
          image.getRGB(column, row),
        )
      }
    }
  }

  @Test
  fun aMissingPatternLoadsAsNull() {
    assertNull(store.load("nothing.png"))
  }

  @Test
  fun aPatternCanBeDeleted() {
    val palette = Palette("pico-8.txt", "Pico-8", listOf(Color(0xFF000000)))
    val metadata = store.save("Delete me", palette, snapshot())
    assertNotNull(metadata)

    assertTrue(store.delete(metadata!!.id))

    assertNull(store.load(metadata.id))
    assertTrue("the list no longer offers it", store.list().none { it.id == metadata.id })
  }

  private fun snapshot(seed: Int = 7): EditorSnapshot {
    val columns = 8
    val rows = 7
    return EditorSnapshot(
      columns = columns,
      rows = rows,
      pixels = IntArray(columns * rows) { index ->
        if ((index + seed) % 2 == 0) 0xFFBE4A2F.toInt() else 0xFF3E8948.toInt()
      },
      seed = seed,
      colorArgb = 0xFFBE4A2F.toInt(),
      aspectName = OutputAspect.Portrait9x16.name,
      previewName = PreviewScale.Half.name,
      scaleName = OutputScale.One.name,
      wrapEdges = true,
      mirrorHorizontal = true,
      mirrorVertical = false,
      paletteId = "endesga-32.txt",
    )
  }
}
