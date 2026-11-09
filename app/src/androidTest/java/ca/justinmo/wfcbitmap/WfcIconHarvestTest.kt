package ca.justinmo.wfcbitmap

import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.justinmo.wfcbitmap.generation.WfcGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Harvests a large set of generations from spiral-like source drawings, for icon selection.
 *
 * Two of the first harvest's results were picked out as good by eye, both from the spiral source, so this
 * run concentrates there: spiral and near-spiral drawings, three grid sizes that divide the icon's 72dp
 * safe box evenly, both symmetry settings, and twelve seeds each -- with the edges wrapped, which both of
 * the liked results were.
 *
 * Prints one line per generation so the whole run fits in the log:
 *   ICONG <source> <side> <wrap> <mirror> <seed> <tiles> <row0>|<row1>|...
 */
@RunWith(AndroidJUnit4::class)
class WfcIconHarvestTest {

  private val ink = 0xFF000000.toInt()
  private val paper = 0xFFFFFFFF.toInt()

  private fun pixelsOf(rows: List<String>): IntArray {
    val width = rows.first().length
    val pixels = IntArray(width * rows.size)
    for (y in rows.indices) {
      for (x in 0 until width) {
        pixels[y * width + x] = if (rows[y][x] == '#') ink else paper
      }
    }
    return pixels
  }

  private fun mirrorOf(rows: List<String>) = rows.map { it.reversed() }

  /** Spiral and near-spiral drawings: the family the two chosen results came from. */
  private val sources: List<Pair<String, List<String>>> = listOf(
    "spiral" to listOf(
      "#########.", "#.......#.", "#.#####.#.", "#.#...#.#.",
      "#.#.#.#.#.", "#.#...#.#.", "#.#####.#.", "#.......#.",
    ),
    "spiral-tight" to listOf(
      "#######.", "#.....#.", "#.###.#.", "#.#.#.#.",
      "#.###.#.", "#.....#.", "#######.", "........",
    ),
    "spiral-double" to listOf(
      "#####...####", "#...#...#..#", "#.#.#.#.#.#.", "#.#...#...#.",
      "#.#####.###.", "#...........#", "#####.#####.#", "............",
    ),
    "spiral-open" to listOf(
      "#########...", "#.........#.", "#.#######.#.", "#.#.....#.#.",
      "#.#.###.#.#.", "#.#.....#.#.", "###########.", "............",
    ),
    "whirl" to listOf(
      "####...#####", "###....####.", "##.....###..", "#.....##....",
      "....###.....", "..#####.....", ".#######....", "########....",
    ),
    "rings" to listOf(
      "....####....", "..########..", ".####..####.", "###......###",
      "###......###", ".####..####.", "..########..", "....####....",
    ),
    "rings-small" to listOf(
      "..####..", ".######.", "###..###", "##....##",
      "########", "..####..", "........", "........",
    ),
    "diagonals" to listOf(
      "#...#...#...", ".#...#...#..", "..#...#...#.", "#..#...#...#",
      ".#..#...#...", "..#..#....#.", "#..#..#....#", ".#..##...#..",
    ),
    "chevron" to listOf(
      ".....##.....", "....####....", "...##.##....", "..##...##...",
      ".##.....##..", "##.......##.", ".........##.", "..........##",
    ),
    "knot" to listOf(
      "##....##....", "###..###....", ".######.....", "..####......",
      "..####......", ".######.....", "###..###....", "##....##....",
    ),
    "plates" to listOf(
      "####..####..", "####..####..", "....##....##", "....##....##",
      "##....##....", "##....##....", "..####..####", "..####..####",
    ),
    "blobs" to listOf(
      "..##...##...", ".####.####..", "..##...##...", "............",
      "...##..###..", "..####.####.", "...##...##..", "............",
    ),
  ).flatMap { (name, rows) ->
    if (name.startsWith("spiral")) {
      // A mirrored spiral is a second tile set, not another copy of the first: it winds the other way.
      listOf(name to rows, "$name-mirror" to mirrorOf(rows))
    } else {
      listOf(name to rows)
    }
  }

  @Test
  fun harvest() {
    var count = 0
    for ((name, rows) in sources) {
      val pixels = pixelsOf(rows)
      val width = rows.first().length
      val height = rows.size
      for (side in SIDES) {
        for (mirror in booleanArrayOf(true, false)) {
          for (seed in SEEDS) {
            val output = try {
              runBlocking {
                WfcGenerator.generate(
                  pixels = pixels,
                  sourceWidth = width,
                  sourceHeight = height,
                  seed = seed,
                  columns = side,
                  rows = side,
                  wrapEdges = true,
                  mirrorHorizontal = mirror,
                  mirrorVertical = false,
                )
              }
            } catch (e: Exception) {
              continue
            }
            val scale = WfcGenerator.PIXELS_PER_CELL
            val lines = ArrayList<String>(side)
            for (row in 0 until side) {
              val line = StringBuilder(side)
              for (col in 0 until side) {
                val colour = output.bitmap.getPixel(col * scale + scale / 2, row * scale + scale / 2)
                line.append(if (colour == ink) '#' else '.')
              }
              lines.add(line.toString())
            }
            println(
              "ICONG $name $side true $mirror $seed ${output.tileCount} " + lines.joinToString("|"),
            )
            count++
          }
        }
      }
    }
    println("ICONDONE generations=$count")
  }

  private companion object {
    val SIDES = intArrayOf(9, 12, 18)
    val SEEDS = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)
  }
}