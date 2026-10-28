package ca.justinmo.wfcbitmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The file name is the only part of saving a picture that can be checked without a device: the rest of
 * [ImageSaver] talks to MediaStore, which only exists on a running Android.
 */
class ImageSaverTest {

  /** The same format [ImageSaver.fileName] uses, in the same (default) time zone. */
  private fun stamp(millis: Long) =
    SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(millis))

  @Test
  fun fileNameCarriesTheTimestampAndThePngExtension() {
    val millis = 1_790_771_696_000L
    val name = ImageSaver.fileName(millis)

    assertTrue("expected a .png name, got $name", name.endsWith(".png"))
    assertTrue("expected the album prefix, got $name", name.startsWith("wfc-"))
    assertTrue("expected ${stamp(millis)} in $name", name.contains(stamp(millis)))
  }

  @Test
  fun twoSavesInTheSameSecondGetDifferentNames() {
    val start = 1_790_771_696_000L

    assertNotEquals(ImageSaver.fileName(start + 100), ImageSaver.fileName(start + 900))
  }

  @Test
  fun everyNameIsUniqueForEveryMillisecondInASecond() {
    val start = 1_790_771_696_000L
    val names = (0 until 1000).map { ImageSaver.fileName(start + it) }

    assertEquals(names.size, names.toSet().size)
  }

  @Test
  fun legacyWritePermissionIsOnlyNeededBeforeApi29() {
    assertTrue(ImageSaver.needsLegacyWritePermission(sdk = 26))
    assertTrue(ImageSaver.needsLegacyWritePermission(sdk = 28))
    assertTrue(!ImageSaver.needsLegacyWritePermission(sdk = 29))
    assertTrue(!ImageSaver.needsLegacyWritePermission(sdk = 37))
  }
}
