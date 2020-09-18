package com.ykai.kssocket.channels

import com.ykai.kssocket.clearAndLimit
import com.ykai.kssocket.emitter.IOEventEmitter
import com.ykai.kssocket.processProxyException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

class Socks4ASocketChannelImpl(
    nioChannel: SocketChannel,
    private val proxy: Socks4Proxy,
    emitter: IOEventEmitter
) : SimpleASocketChannelImpl(nioChannel, emitter) {
    override suspend fun connect(addr: InetSocketAddress) {
        try {
            super.connect(proxy.addr)
        } catch (e: IOException) {
            throw ProxyServerException("an error occurred while connect socks4 server", e)
        }
        try {
            requestConnect(addr)
        } catch (e: Exception) {
            runCatching { close() }
            throw e
        }
    }

    private suspend fun requestConnect(addr: InetSocketAddress) {
        val buf = ByteBuffer.allocate(32)
        buf.put(4)  // version 4
        buf.put(1)  // CONNECT request
        buf.putShort(addr.port.toShort())
        if (proxy.remoteDns) {
            buf.put(byteArrayOf(0, 0, 0, 1))  // 0.0.0.1
            buf.put(0)  // null for ip
            buf.put(addr.hostName.toByteArray(Charsets.US_ASCII))
            buf.put(0)  // null for hostname
        } else {
            buf.put(addr.address.address)
            buf.put(0)  // null for ip
        }
        buf.flip()
        processProxyException {
            writeAll(buf)
        }

        buf.clearAndLimit(8)  // 读取八个字节
        processProxyException {
            if (!readAll(buf)) {
                throw ProxyServerException("lost connection to socks5 server")
            }
            buf.flip()
        }
        val version = buf.get()
        val cmd = buf.get().toInt() and 0xFF
        if (version.toInt() != 0)
            throw ProxyServerException("unrecognized response")
        when (cmd) {
            // 连接成功
            90 -> return
            // 连接失败
            91 -> throw ConnectException("Connection refused")
            else -> throw ProxyServerException("Authentication failed")
        }
    }
}
