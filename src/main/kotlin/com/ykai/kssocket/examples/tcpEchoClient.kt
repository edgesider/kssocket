package com.ykai.kssocket.examples

import com.ykai.kssocket.ASocketChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    val sock = ASocketChannel.open(InetSocketAddress(8000))
    val buffer = ByteBuffer.allocate(4)
    for (i in 0..100) {
        sock.write(ByteBuffer.wrap("echo".toByteArray()))
        println("written: echo")
        buffer.clear()
        sock.read(buffer)
        buffer.position(0)
        println("read: ${Charsets.UTF_8.decode(buffer)}")
    }
}
