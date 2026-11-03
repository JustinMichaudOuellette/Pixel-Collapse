package ca.justinmo.wfcbitmap
import ca.justinmo.wfcbitmap.generation.*
import ca.justinmo.wfcbitmap.output.*
import ca.justinmo.wfcbitmap.video.*
import ca.justinmo.wfcbitmap.storage.*
import ca.justinmo.wfcbitmap.palette.*
import ca.justinmo.wfcbitmap.editor.*

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
      ;Description: Originally created for <a href="http://nykra.com/">NYKRA</a>
      ;Colors: 32
      FFbe4a2f
      FFd77643
      FFead4aa
    """.trimIndent()

    val stream = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
    val palette = PaletteStore.parsePalette("endesga-32.txt", stream)

    assertEquals("endesga-32.txt", palette.id)
    assertEquals("Endesga 32", palette.name)
    assertEquals(
      "Originally created for <a href=\"http://nykra.com/\">NYKRA</a>",
      palette.description,
    )
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
  fun keepsAnEmptyFileEmptyInsteadOfInventingColours() {
    val stream = ByteArrayInputStream("".toByteArray(Charsets.UTF_8))
    val palette = PaletteStore.parsePalette("empty.txt", stream)

    assertEquals("empty.txt", palette.id)
    assertEquals("A file with no colours has none", emptyList<Color>(), palette.colors)
  }

  @Test
  fun theDefaultPaletteIsEndesga32WhereverItAppearsInTheList() {
    val other = Palette("mulfok32.txt", "mulfok32", listOf(Color.White))
    val endesga = Palette("endesga-32.txt", "Endesga 32", listOf(Color.Black))

    assertEquals(endesga, PaletteStore.defaultPalette(listOf(other, endesga)))
  }

  @Test
  fun theDefaultPaletteIsMatchedWithoutWorryingAboutCase() {
    val endesga = Palette("Endesga-32.TXT", "Endesga 32", listOf(Color.Black))
    val other = Palette("other.txt", "Other", listOf(Color.White))

    assertEquals(endesga, PaletteStore.defaultPalette(listOf(other, endesga)))
  }

  @Test
  fun fallsBackToTheFirstPaletteWhenEndesgaIsMissing() {
    val only = Palette("other.txt", "Other", listOf(Color.White))

    assertEquals(only, PaletteStore.defaultPalette(listOf(only)))
  }

  @Test
  fun closestColorIsTheNearestEntryInRgb() {
    val palette = listOf(
      Color(0xFFBE4A2F),
      Color(0xFF3E8948),
      Color(0xFF124E89),
    )

    assertEquals(Color(0xFFBE4A2F), PaletteStore.closestColor(Color(0xFFC05035), palette))
    assertEquals(Color(0xFF3E8948), PaletteStore.closestColor(Color(0xFF4F9A58), palette))
    assertEquals(Color(0xFF124E89), PaletteStore.closestColor(Color(0xFF1A5A96), palette))
  }

  @Test
  fun closestColorReturnsTheColorWhenThePaletteIsEmpty() {
    val color = Color(0xFFBE4A2F)

    assertEquals(color, PaletteStore.closestColor(color, emptyList()))
    assertEquals(color, Palette("empty.txt", "Empty", emptyList()).closestColor(color))
  }

  @Test
  fun orderingMovesEndesgaToTheFront() {
    val other = Palette("mulfok32.txt", "mulfok32", listOf(Color.White))
    val endesga = Palette("endesga-32.txt", "Endesga 32", listOf(Color.Black))

    assertEquals(
      listOf(endesga, other),
      PaletteStore.orderWithDefaultFirst(listOf(other, endesga)),
    )
  }
}