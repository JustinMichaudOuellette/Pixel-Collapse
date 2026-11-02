package ca.justinmo.wfcbitmap

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream

class PaletteStoreTest {

  @Test
  fun parsesEndesga32PaletteFile() {
    val content = """
      ;paint.net Palette File
      ;Downloaded from Lospec.com/palette-list
      ;Palette Name: Endesga 32
      ;Description: Originally created for NYKRA
      ;Colors: 32
      FFbe4a2f
      FFd77643
      FFead4aa
    """.trimIndent()

    val stream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
    val palette = PaletteStore.parsePalette("endesga-32.txt", stream)

    assertEquals("endesga-32.txt", palette.id)
    assertEquals("Endesga 32", palette.name)
    assertEquals(3, palette.colors.size)
    assertEquals(Color(0xFFBE4A2F.toInt()), palette.colors[0])
    assertEquals(Color(0xFFD77643.toInt()), palette.colors[1])
    assertEquals(Color(0xFFEAD4AA.toInt()), palette.colors[2])
  }

  @Test
  fun parsesMulfok32PaletteFile() {
    val content = """
      ;paint.net Palette File
      ;Downloaded from Lospec.com/palette-list
      ;Palette Name: mulfok32
      ;Description: A colour palette containing soft, pastel colours. My personal palette!
      ;Colors: 32
      FF5ba675
      FF6bc96c
    """.trimIndent()

    val stream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
    val palette = PaletteStore.parsePalette("mulfok32.txt", stream)

    assertEquals("mulfok32.txt", palette.id)
    assertEquals("mulfok32", palette.name)
    assertEquals(2, palette.colors.size)
    assertEquals(Color(0xFF5BA675.toInt()), palette.colors[0])
    assertEquals(Color(0xFF6BC96C.toInt()), palette.colors[1])
  }

  @Test
  fun parsesColorHexFormats() {
    val color8 = PaletteStore.parseColor("FFBE4A2F")
    assertNotNull(color8)
    assertEquals(Color(0xFFBE4A2F.toInt()).toArgb(), color8?.toArgb())

    val color6 = PaletteStore.parseColor("BE4A2F")
    assertNotNull(color6)
    assertEquals(Color(0xFFBE4A2F.toInt()).toArgb(), color6?.toArgb())
  }

  @Test
  fun fallsBackToDefaultPaletteWhenFileIsEmpty() {
    val stream = ByteArrayInputStream("".toByteArray(Charsets.UTF_8))
    val palette = PaletteStore.parsePalette("empty.txt", stream)

    assertEquals("empty.txt", palette.id)
    assertEquals(PaletteStore.DEFAULT_PALETTE_COLORS, palette.colors)
  }
}
