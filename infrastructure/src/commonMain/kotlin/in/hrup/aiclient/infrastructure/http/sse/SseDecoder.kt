package `in`.hrup.aiclient.infrastructure.http.sse

internal class SseDecoder {
    private var lineBytes = ByteArray(256)
    private var lineSize = 0
    private var firstLine = true
    private var afterCarriageReturn = false
    private val data = StringBuilder()
    private var type = ""
    private var id = ""

    suspend fun decode(bytes: ByteArray, emit: suspend (SseEvent) -> Unit) {
        for (byte in bytes) {
            val followsCarriageReturn = afterCarriageReturn
            afterCarriageReturn = byte == CR
            when (byte) {
                CR -> readLine()?.let { emit(it) }
                LF -> if (!followsCarriageReturn) readLine()?.let { emit(it) }
                else -> {
                    if (lineSize == lineBytes.size) lineBytes = lineBytes.copyOf(lineBytes.size * 2)
                    lineBytes[lineSize++] = byte
                }
            }
        }
    }

    private fun readLine(): SseEvent? {
        var line = lineBytes.decodeToString(endIndex = lineSize)
        lineSize = 0
        if (firstLine) {
            line = line.removePrefix("\uFEFF")
            firstLine = false
        }
        if (line.isEmpty()) return dispatch()
        if (line.startsWith(':')) return null

        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
            "data" -> data.append(value).append('\n')
            "event" -> type = value
            "id" -> if ('\u0000' !in value) id = value
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        val event = if (data.isEmpty()) null else SseEvent(
            data = data.substring(0, data.length - 1),
            type = type.ifEmpty { "message" },
            id = id,
        )
        data.clear()
        type = ""
        return event
    }

    private companion object {
        const val CR = 13.toByte()
        const val LF = 10.toByte()
    }
}
