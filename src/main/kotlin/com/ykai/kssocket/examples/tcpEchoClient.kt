package com.ykai.kssocket.examples

import com.ykai.kssocket.channels.ASocketChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    repeat(20) {
        launch {
            val sock = ASocketChannel.open()
            delay(1000)
            try {
                sock.connect(InetSocketAddress(8000))
            } catch (e: Exception) {
                println(e)
                delay(5000)
                return@launch
            }

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
        }
    }

    Unit
}
