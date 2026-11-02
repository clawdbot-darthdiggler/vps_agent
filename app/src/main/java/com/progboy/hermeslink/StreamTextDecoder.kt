package com.progboy.hermeslink

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Keeps incomplete UTF-8 bytes across SSH reads; one instance per output stream. */
class StreamTextDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = byteArrayOf()

    fun append(bytes: ByteArray, count: Int): String {
        require(count in 0..bytes.size)
        val input = ByteBuffer.wrap(pending + bytes.copyOf(count))
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        output.flip()
        return output.toString()
    }
}
