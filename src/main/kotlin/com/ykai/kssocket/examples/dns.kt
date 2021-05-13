package com.ykai.kssocket.examples

import com.ykai.kssocket.dns.DNSPacket
import com.ykai.kssocket.dns.DNSResolver
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Inet4Address
import java.nio.ByteBuffer

fun main() = runBlocking {
    val resolver = DNSResolver(Inet4Address.getByName("114.114.114.114") as Inet4Address)
    val ans = resolver.resolve("google.com")
    println(ans)
    Unit
}

fun test() {
    val f = File("./binpkt/dns-req.bin")
    val inS = f.inputStream()
    val ba = inS.readBytes()
    val pkt = DNSPacket.fromByteBuffer(ByteBuffer.wrap(ba))
    val pkt2 = DNSPacket.fromByteBuffer(
        pkt.toByteBuffer(ByteBuffer.allocate(2000))
            .also { it.flip() }
    )
    println(pkt2)

    /*
    val sock = DatagramSocket()
    sock.connect(InetSocketAddress("202.117.80.3", 53))
    sock.send(DatagramPacket(ba, ba.size))
    val resp = DatagramPacket(ByteArray(500), 500)
    sock.receive(resp)
    println("received ${resp.length} bytes")
    val pkt = DNSPacket.fromByteBuffer(ByteBuffer.wrap(resp.data.copyOf(resp.length)))
    println(pkt)
     */
}
