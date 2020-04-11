package com.ykai.kssocket.examples

import com.ykai.kssocket.AServerSocketChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main(): Unit = runBlocking {
    val sock = AServerSocketChannel.open()
    sock.bind(InetSocketAddress(8000))
    while (true) {
        sock.accept().let { client ->
            println("new client: $client")
            launch {
                val buffer = ByteBuffer.allocate(4)
                try {
                    while (true) {
                        buffer.clear()
                        if (!client.read(buffer)) {
                            println("[$client]: EOF")
                            break
                        }
                        buffer.position(0)
                        println("[$client] read: ${Charsets.UTF_8.decode(buffer)}")
                        buffer.position(0)
                        client.write(buffer)
                        println("[$client] write")
                    }
                } finally {
                    client.close()
                    println("[$client] closed")
                }
            }
        }
    }
}
