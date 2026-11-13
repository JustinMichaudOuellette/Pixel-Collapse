package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.ui.EditorToolId
import ca.justinmo.wfcbitmap.ui.editorToolInfos
import ca.justinmo.wfcbitmap.ui.editorToolInfosFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The debug-only tools are filtered as a group, and removing them leaves the toolbar order alone. */
class EditorToolCatalogTest {

  private val debugOnly = listOf(EditorToolId.PhotoCamera, EditorToolId.BestEffort)

  @Test
  fun debugOnlyToolsOnlyExistWhenAskedFor() {
    for (tool in debugOnly) {
      assertTrue(
        "the debug catalog must offer $tool",
        editorToolInfosFor(includeDebugTools = true).any { it.id == tool },
      )
      assertFalse(
        "the release catalog must not offer $tool",
        editorToolInfosFor(includeDebugTools = false).any { it.id == tool },
      )
      // Unit tests run against the debug variant, so the shipping catalog here is the debug one.
      assertTrue(
        "the debug build must actually use the catalog that includes $tool",
        editorToolInfos.any { it.id == tool },
      )
    }
  }

  @Test
  fun hidingDebugToolsKeepsEveryOtherToolInOrder() {
    val withDebugTools = editorToolInfosFor(includeDebugTools = true)
    val withoutDebugTools = editorToolInfosFor(includeDebugTools = false)
    assertEquals(
      withDebugTools.filterNot { it.id in debugOnly },
      withoutDebugTools,
    )
  }
}
