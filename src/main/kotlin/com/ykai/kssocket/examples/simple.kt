package com.ykai.kssocket.examples

import com.ykai.kssocket.channels.ASocketChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main(): Unit = runBlocking {
    ASocketChannel.open().let { sock ->
        sock.connect(InetSocketAddress("localhost", 8000))
        sock.writeAll(ByteBuffer.wrap("kssocket".toByteArray()))
        sock.close()
    }
    Unit
}