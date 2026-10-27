package net.akachaki.processing.engine.enum

enum class Direction2d4(override val dx: Int, override val dy: Int): Direction2d {
  LEFT(dx = -1, dy = 0),
  TOP(dx = 0, dy = -1),
  RIGHT(dx = 1, dy = 0),
  BOTTOM(dx = 0, dy = 1),
}