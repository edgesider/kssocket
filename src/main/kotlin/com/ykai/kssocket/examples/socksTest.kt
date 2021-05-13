package com.ykai.kssocket.examples

import com.ykai.kssocket.channels.ASocketChannel
import com.ykai.kssocket.channels.Socks4Proxy
import com.ykai.kssocket.channels.Socks5Proxy
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    val sock = createSocks4()
    sock.writeAll(
        ByteBuffer.wrap(
            ("GET / HTTP/1.1\r \nHost: google.com\r\n" +
                    "User-Agent: curl/7.72.0\r\nAccept: */*\r\n\r\n")
                .toByteArray()
        )
    )
    println("written")
    val buf = ByteBuffer.allocate(1000)
    val n = sock.read(buf)
    println("read $n")
    buf.flip()
    println(String(ByteArray(buf.limit()).also { buf.get(it) }))
    Unit
}

suspend fun createSocks5() = ASocketChannel.open(
    proxy = Socks5Proxy(
        "127.0.0.1", 1079, remoteDns = true
    )
).also {
    it.connect(InetSocketAddress("google.com", 80))
    println("connected")
}

suspend fun createSocks4() = ASocketChannel.open(
    InetSocketAddress("google.com", 80),
    proxy = Socks4Proxy("127.0.0.1", 1079, remoteDns = false)
)
