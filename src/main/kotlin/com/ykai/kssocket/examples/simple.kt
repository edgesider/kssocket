package com.ykai.kssocket.examples

import com.ykai.kssocket.ASocketChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    ASocketChannel.open().let { sock ->
        sock.connect(InetSocketAddress("localhost", 8000))
        sock.write(ByteBuffer.wrap("kssocket".toByteArray()))
        sock.close()
    }
}