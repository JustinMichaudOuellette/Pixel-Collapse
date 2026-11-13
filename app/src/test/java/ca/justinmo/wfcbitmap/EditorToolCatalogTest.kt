package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.ui.EditorToolId
import ca.justinmo.wfcbitmap.ui.editorToolInfos
import ca.justinmo.wfcbitmap.ui.editorToolInfosFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The camera tool is a debug-only entry, and removing it leaves the rest of the toolbar alone. */
class EditorToolCatalogTest {

  @Test
  fun theCameraToolOnlyExistsWhenAskedFor() {
    assertTrue(
      "the debug catalog must offer the camera tool",
      editorToolInfosFor(includeCamera = true).any { it.id == EditorToolId.PhotoCamera },
    )
    assertFalse(
      "the release catalog must not offer the camera tool",
      editorToolInfosFor(includeCamera = false).any { it.id == EditorToolId.PhotoCamera },
    )
    // Unit tests run against the debug variant, so the shipping catalog here is the debug one.
    assertTrue(
      "the debug build must actually use the catalog that includes the camera",
      editorToolInfos.any { it.id == EditorToolId.PhotoCamera },
    )
  }

  @Test
  fun hidingTheCameraToolKeepsEveryOtherToolInOrder() {
    val withCamera = editorToolInfosFor(includeCamera = true)
    val withoutCamera = editorToolInfosFor(includeCamera = false)
    assertEquals(withCamera.filterNot { it.id == EditorToolId.PhotoCamera }, withoutCamera)
  }
}
