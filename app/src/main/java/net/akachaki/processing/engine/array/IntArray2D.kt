package net.akachaki.processing.engine.array

class IntArray2D(
        val width: Int,
        val height: Int,
        val data: IntArray,
    ) {

        inline fun index(x: Int, y: Int): Int {
//            require(x in 0 until width)
//            require(y in 0 until height)
            return y * width + x
        }

        inline operator fun get(x: Int, y: Int): Int {
            return data[index(x, y)]
        }

        inline operator fun set(x: Int, y: Int, value: Int) {
            data[index(x, y)] = value
        }

        fun getOrNull(x: Int, y: Int): Int? {
            return if (x in 0 until width && y in 0 until height) {
                data[index(x, y)]
            } else {
                null
            }
        }

        inline fun forEach(action: (x: Int, y: Int, value: Int) -> Unit) {
            for (i in data.indices) {
                val x = i % width
                val y = i / width
                action(x, y, data[i])
            }
        }


    companion object {
            inline fun create(width: Int, height: Int, initial: (x: Int, y: Int) -> Int): IntArray2D =
                IntArray2D(
                    width,
                    height,
                    data = IntArray(width * height) { i ->
                        val x = i % width
                        val y = i / width
                        initial(x, y)
                    })
        }
    }