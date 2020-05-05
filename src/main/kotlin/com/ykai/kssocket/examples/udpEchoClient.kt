package com.ykai.kssocket.examples

import com.ykai.kssocket.ADatagramChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer


fun main() = runBlocking {
    val sock = ADatagramChannel.open()
    val buffer = ByteBuffer.allocate(10)
    for (i in 0..100) {
        buffer.clear()
        sock.send(ByteBuffer.wrap("echo".toByteArray()), InetSocketAddress(8000))
        println("written: echo")
        buffer.clear()
        val srcAddr = sock.receive(buffer)
        buffer.flip()
        println("read [$srcAddr]: ${Charsets.UTF_8.decode(buffer).replace(Regex("\\n"), "<CR>")}")
    }
}