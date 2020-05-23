package com.ykai.kssocket.examples

import com.ykai.kssocket.ASocketChannel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    val sock = ASocketChannel.open(InetSocketAddress(8000))

    repeat(10) { job_i ->
        launch {
            val buffer = ByteBuffer.allocate(4)
            for (i in 0..1) {
                sock.writeAll(ByteBuffer.wrap("echo".toByteArray()))
                println("[$job_i] written: echo")
                buffer.clear()
                sock.readAll(buffer)
                buffer.flip()
                println("[$job_i] read: ${Charsets.UTF_8.decode(buffer)}")
            }
        }
    }

    Unit
}
