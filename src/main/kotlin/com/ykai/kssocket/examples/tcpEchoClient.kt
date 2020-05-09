package com.ykai.kssocket.examples

import com.ykai.kssocket.ASocketChannel
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main() = runBlocking {
    val sock = ASocketChannel.open(InetSocketAddress(8000))

    val jobs = mutableListOf<Job>()
    for (job_i in 0..10) {
        GlobalScope.launch {
            val buffer = ByteBuffer.allocate(4)
            for (i in 0..100) {
                sock.write(ByteBuffer.wrap("echo".toByteArray()))
                println("[$job_i] written: echo")
                buffer.clear()
                sock.read(buffer)
                buffer.flip()
                println("[$job_i] read: ${Charsets.UTF_8.decode(buffer)}")
            }
        }.let {
            jobs.add(it)
        }
    }
    jobs.forEach { it.join() }

    Unit
}
