@file:JvmName("AMQPStreams")
package net.corda.serialization.internal.amqp

import net.corda.serialization.internal.ByteBufferInputStream
import net.corda.serialization.internal.ByteBufferOutputStream
import net.corda.serialization.internal.DEFAULT_BYTEBUFFER_SIZE
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

fun InputStream.asByteBuffer(): ByteBuffer {
    return if (this is ByteBufferInputStream) {
        byteBuffer // BBIS has no other state, so this is perfectly safe.
    } else {
        ByteBuffer.wrap(ByteBufferOutputStream(DEFAULT_BYTEBUFFER_SIZE).let {
            copyTo(it)
            it.toByteArray()
        })
    }
}

fun <T> OutputStream.alsoAsByteBuffer(remaining: Int, task: (ByteBuffer) -> T): T {
    return if (this is ByteBufferOutputStream) {
        alsoAsByteBuffer(remaining, task)
    } else {
        ByteBufferOutputStream(DEFAULT_BYTEBUFFER_SIZE).let {
            val result = it.alsoAsByteBuffer(remaining, task)
            it.copyTo(this)
            result
        }
    }
}
