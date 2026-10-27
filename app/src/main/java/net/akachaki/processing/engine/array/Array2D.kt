package net.akachaki.processing.engine.array

class Array2D<T>(
    val width: Int,
    val height: Int,
    val data: Array<T>,
) {

    inline fun index(x: Int, y: Int): Int {
//        require(x in 0 until width)
//        require(y in 0 until height)
        return y * width + x
    }

    inline operator fun get(x: Int, y: Int): T {
        return data[index(x, y)]
    }

    inline operator fun set(x: Int, y: Int, value: T) {
        data[index(x, y)] = value
    }

    inline fun getOrNull(x: Int, y: Int): T? {
        return if (x in 0 until width && y in 0 until height) {
            data[index(x, y)]
        } else {
            null
        }
    }

    inline fun forEach(action: (x: Int, y: Int, value: T) -> Unit) {
        for (i in data.indices) {
            val x = i % width
            val y = i / width
            action(x, y, data[i])
        }
    }

    companion object {
        inline fun <reified T> create(width: Int, height: Int, initial: (x: Int, y: Int) -> T): Array2D<T> =
            Array2D(
                width,
                height,
                data = Array(width * height) { i ->
                    val x = i % width
                    val y = i / width
                    initial(x, y)
                })
    }
}
