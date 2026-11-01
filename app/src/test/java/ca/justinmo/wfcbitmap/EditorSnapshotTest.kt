package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session that survives the app being closed.
 *
 * Two halves, and both matter: the snapshot has to come back byte-for-byte the same, and the store has to
 * refuse anything that is not one -- because the alternative to refusing is restoring a corrupt drawing
 * over someone's work. The file work runs on the JVM against a temporary directory, so no device is needed
 * to know the format is sound.
 */
class EditorSnapshotTest {

  private val temp = File(System.getProperty("java.io.tmpdir"), "wfc-snapshot-test").apply {
    deleteRecursively()
    mkdirs()
  }

  @After
  fun cleanUp() {
    temp.deleteRecursively()
  }

  // Both sides at least MIN_GRID_SIZE: a smaller grid is not one the editor can make, so decode refuses it.
  private fun snapshot(columns: Int = 8, rows: Int = 7) = EditorSnapshot(
    columns = columns,
    rows = rows,
    pixels = IntArray(columns * rows) { 0xFF000000.toInt() or (it * 7 + 11) },
    seed = 42,
    colorArgb = 0xFFBE4A2F.toInt(),
    aspectName = "Square",
    previewName = "Two",
    scaleName = "Sixteen",
    wrapEdges = true,
    mirrorSymmetry = true,
  )

  @Test
  fun aSnapshotSurvivesTheRoundTrip() {
    val original = snapshot()
    val decoded = EditorSnapshot.decode(original.encode())
    assertEquals("every field has to come back", original, decoded)
  }

  @Test
  fun thePixelsComeBackInTheSameOrder() {
    // The drawing is row-major and every cell is a distinct colour here, so a transposed or reversed loop
    // would show up as a difference rather than as a surprisingly plausible picture.
    val original = snapshot(columns = 9, rows = 7)
    val decoded = EditorSnapshot.decode(original.encode())!!
    assertTrue(original.pixels.contentEquals(decoded.pixels))
  }

  @Test
  fun aFileThatIsNotASnapshotIsRefused() {
    assertNull(EditorSnapshot.decode(ByteArray(0)))
    assertNull(EditorSnapshot.decode("not a drawing".toByteArray()))
    assertNull(EditorSnapshot.decode(ByteArray(4096)))
  }

  @Test
  fun aTruncatedSnapshotIsRefused() {
    val bytes = snapshot().encode()
    // Every prefix short of the whole thing is a file that was interrupted mid-write.
    for (length in listOf(4, 12, 20, bytes.size / 2, bytes.size - 1)) {
      assertNull("a $length-byte prefix must be refused", EditorSnapshot.decode(bytes.copyOf(length)))
    }
  }

  @Test
  fun aSnapshotFromAnotherVersionIsRefused() {
    val bytes = snapshot().encode()
    // The version is the second int in the header.
    bytes[7] = (bytes[7] + 1).toByte()
    assertNull(EditorSnapshot.decode(bytes))
  }

  @Test
  fun aGridSizeThatDisagreesWithItsPixelsIsRefused() {
    assertFalse(snapshot().copy(pixels = IntArray(3)).isValid)
    assertTrue(snapshot().isValid)
  }

  @Test
  fun anImpossiblyLargeGridIsRefused() {
    // A corrupt file must not be able to ask for a gigabyte of pixels.
    val huge = snapshot().copy(columns = 5_000, rows = 5_000)
    assertFalse(huge.isValid)
    assertNull(EditorSnapshot.decode(huge.encode()))
  }

  // ---------------------------------------------------------------------------
  // The store
  // ---------------------------------------------------------------------------

  @Test
  fun theStoreGivesBackWhatItWasGiven() {
    val store = DrawingStore.forDirectory(temp)
    assertNull("nothing saved yet", store.load())

    assertTrue(store.save(snapshot()))
    assertEquals(snapshot(), store.load())
  }

  @Test
  fun aSecondSaveReplacesTheFirst() {
    val store = DrawingStore.forDirectory(temp)
    store.save(snapshot())
    val second = snapshot(columns = 9, rows = 8)
    store.save(second)
    assertEquals(second, store.load())
  }

  @Test
  fun aCorruptFileOnDiskIsNoSessionRatherThanACrash() {
    val store = DrawingStore.forDirectory(temp)
    File(temp, DrawingStore.FILE_NAME).writeText("half a drawing")
    assertNull(store.load())
  }

  @Test
  fun clearingForgetsTheSession() {
    val store = DrawingStore.forDirectory(temp)
    store.save(snapshot())
    store.clear()
    assertNull(store.load())
  }

  // ---------------------------------------------------------------------------
  // Putting it back in the grid
  // ---------------------------------------------------------------------------

  @Test
  fun restoringADrawingReplacesTheGridAndItsHistory() {
    val grid = BitmapEditor.PixelGridState(initialColumns = 8, initialRows = 7)
    grid.paintOver(1, 1, Color.Red)
    assertTrue("something to undo first", grid.canUndo())

    val saved = snapshot(columns = 9, rows = 8)
    grid.restoreDrawing(saved.columns, saved.rows, saved.pixels)

    assertEquals(9, grid.columns)
    assertEquals(8, grid.rows)
    assertEquals(Color(saved.pixels[0]), grid.getPixel(0, 0))
    assertEquals(Color(saved.pixels[9]), grid.getPixel(0, 1))
    assertEquals("a restored drawing has nothing behind it to undo", 0, grid.undoDepth)
    assertEquals(0, grid.redoDepth)
  }

  @Test
  fun aSnapshotThatDoesNotFitTheGridIsIgnored() {
    val grid = BitmapEditor.PixelGridState(initialColumns = 8, initialRows = 7)
    grid.restoreDrawing(columns = 9, rows = 9, pixels = IntArray(10))
    assertEquals("the grid must be left alone", 8, grid.columns)
    assertEquals(7, grid.rows)
  }
}