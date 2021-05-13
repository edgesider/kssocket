package com.ykai.kssocket.examples

import com.ykai.kssocket.channels.NioASocketChannel
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    val ch = NioASocketChannel()
    val field = ch.nioChannel.javaClass.getDeclaredField("fdVal")
    field.isAccessible = true
    val fd = field.get(ch.nioChannel)
    ch.connect(InetSocketAddress(8000))
    ch.write(ByteBuffer.wrap(byteArrayOf('1'.toByte(), '2'.toByte(), '3'.toByte())))
    println(ch.read(ByteBuffer.allocate(100)))
    Unit
}
