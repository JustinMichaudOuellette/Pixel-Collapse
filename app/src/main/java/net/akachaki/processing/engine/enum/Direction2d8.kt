package net.akachaki.processing.engine.enum

enum class Direction2d8(override val dx: Int, override val dy: Int): Direction2d {
  LEFT(dx = -1, dy = 0),
  UP_LEFT(dx = -1, dy = -1),
  UP(dx = 0, dy = -1),
  UP_RIGHT(dx = 1, dy = -1),
  RIGHT(dx = 1, dy = 0),
  DOWN_RIGHT(dx = 1, dy = 1),
  DOWN(dx = 0, dy = 1),
  DOWN_LEFT(dx = -1, dy = 1),
  ;

  fun opposite(): Direction2d8 {
    return when (this) {
      LEFT -> RIGHT
      UP_LEFT -> DOWN_RIGHT
      UP -> DOWN
      UP_RIGHT -> DOWN_LEFT
      RIGHT -> LEFT
      DOWN_RIGHT -> UP_LEFT
      DOWN -> UP
      DOWN_LEFT -> UP_RIGHT
    }
  }

  companion object {
    fun fromDelta(dx: Int, dy: Int): Direction2d8 =
      when (dx) {
        -1 -> {
          when (dy) {
            -1 -> UP_LEFT
            0 -> LEFT
            1 -> DOWN_LEFT
            else ->  throw IllegalStateException("Unknown dx $dx dy $dy")
          }
        }
        0 -> {
          when (dy) {
            -1 -> UP
            1 -> DOWN
            else ->  throw IllegalStateException("Unknown dx $dx dy $dy")
          }
        }
        1 -> {
          when (dy) {
            -1 -> UP_RIGHT
            0 -> RIGHT
            1 -> DOWN_RIGHT
            else ->  throw IllegalStateException("Unknown dx $dx dy $dy")
          }
        }
        else -> throw IllegalStateException("Unknown dx $dx dy $dy")
      }
  }
}