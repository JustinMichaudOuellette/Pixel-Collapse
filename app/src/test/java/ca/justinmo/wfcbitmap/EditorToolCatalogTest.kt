package ca.justinmo.wfcbitmap

import ca.justinmo.wfcbitmap.ui.EditorToolId
import ca.justinmo.wfcbitmap.ui.allEditorToolInfos
import ca.justinmo.wfcbitmap.ui.editorToolInfos
import ca.justinmo.wfcbitmap.ui.editorToolInfosFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The disabled-feature flags are the single source of truth for what the toolbar offers. */
class EditorToolCatalogTest {

  private val disabled = listOf(
    EditorToolId.Triangle to FeatureFlags.TRIANGLE_TOOL_ENABLED,
    EditorToolId.PhotoCamera to FeatureFlags.CAMERA_TOOL_ENABLED,
    EditorToolId.BestEffort to FeatureFlags.BEST_EFFORT_TOOL_ENABLED,
  )

  @Test
  fun disabledFeaturesAreNotInTheCatalog() {
    for ((tool, enabled) in disabled) {
      assertFalse("$tool must stay disabled by its feature flag", enabled)
      assertFalse(
        "the catalog must not offer $tool",
        editorToolInfos.any { it.id == tool },
      )
    }
  }

  @Test
  fun filteringDisabledToolsKeepsEveryOtherToolInOrder() {
    assertEquals(
      allEditorToolInfos.filterNot { info -> disabled.any { it.first == info.id } },
      editorToolInfosFor(),
    )
  }
}
