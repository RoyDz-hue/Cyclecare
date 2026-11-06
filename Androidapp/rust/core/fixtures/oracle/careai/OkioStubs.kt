package okio

class Buffer(private var bytes: ByteArray) {
    val size: Long get() = bytes.size.toLong()
    fun readByteArray(n: Long): ByteArray {
        val take = minOf(n, size).toInt()
        val out = bytes.copyOfRange(0, take)
        bytes = bytes.copyOfRange(take, bytes.size)
        return out
    }
}

interface Source {
    fun request(n: Long): Boolean
    val buffer: Buffer
}

class ByteArraySource(bytes: ByteArray) : Source {
    override val buffer = Buffer(bytes)
    override fun request(n: Long): Boolean = buffer.size >= n
}
