package com.ykai.kssocket.dns

import com.ykai.kssocket.channels.ADatagramChannel
import kotlinx.coroutines.runBlocking
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

class DNSResolver(dnsHost: Inet4Address, port: Int = 53) {
    val serverChannel: ADatagramChannel = runBlocking {
        ADatagramChannel.open()
    }

    val dnsAddress = InetSocketAddress(dnsHost, port)
    private var sessionId = AtomicInteger(100)

    suspend fun resolve(host: String): List<Inet4Address> {
        // TODO buffer pool
        val buffer = ByteBuffer.allocate(2048)
        val query = DNSPacket.QueryItem(host, 1, 1)
        val pkt = DNSPacket(
            sessionId.getAndIncrement(), DNSPacket.DefaultRequestFlags,
            arrayOf(query), emptyArray(), emptyArray(), emptyArray()
        )
        serverChannel.send(pkt.toByteBuffer(buffer).also { it.flip() }, dnsAddress)
        buffer.clear()
        serverChannel.receive(buffer)
        buffer.flip()
        val resp = DNSPacket.fromByteBuffer(buffer)
        return resp.answers.toList().fold(mutableListOf()) { res, ans ->
            if (ans.type == 1 && ans.dataLength == 4)
                res.add(Inet4Address.getByAddress(ans.data) as Inet4Address)
            res
        }
    }
}