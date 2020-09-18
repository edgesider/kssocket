package com.ykai.kssocket.channels

import com.ykai.kssocket.clearAndLimit
import com.ykai.kssocket.emitter.IOEventEmitter
import com.ykai.kssocket.processProxyException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel

class Socks5ASocketChannelImpl(
    nioChannel: SocketChannel,
    private val proxy: Socks5Proxy,
    emitter: IOEventEmitter
) : SimpleASocketChannelImpl(nioChannel, emitter) {
    override suspend fun connect(addr: InetSocketAddress) {
        try {
            super.connect(proxy.addr)
        } catch (e: IOException) {
            throw ProxyServerException("an error occurred while connect socks5 server", e)
        }

        val usePassword = proxy.password != null || proxy.username != null

        try {
            consultAuth(usePassword)
            // 身份验证
            if (usePassword) {
                auth()
            }
            requestConnect(addr)
        } catch (e: Exception) {
            runCatching { close() }
            throw e
        }
    }

    /**
     * 协商验证方法
     */
    private suspend fun consultAuth(usePassword: Boolean) {
        val buf = ByteBuffer.allocate(8)

        buf.put(5)  // version
        buf.put(1)  // 支持的验证方法数
        buf.put((if (usePassword) 2 else 0).toByte())
        buf.flip()
        processProxyException {
            writeAll(buf)
        }

        buf.clearAndLimit(2)  // 读取两个字节
        processProxyException {
            if (!readAll(buf)) {
                throw ProxyServerException("lost connection to socks5 server")
            }
        }
        buf.flip()
        buf.get()
        if (buf.get().toInt() and 0xFF == 0xFF) {
            // 无可接受的方法
            throw ProxyServerException("authentication method is not accepted")
        }
    }

    /**
     * 验证用户名密码
     */
    private suspend fun auth() {
        val user: String = proxy.username ?: ""
        val pass: String = proxy.password ?: ""
        val buf = ByteBuffer.allocate(user.length + pass.length + 16)

        buf.put(1)  // 验证方法版本
        buf.put(user.length.toByte())
        buf.put(user.toByteArray())
        buf.put(pass.length.toByte())
        buf.put(pass.toByteArray())
        processProxyException {
            writeAll(buf)
        }

        buf.clearAndLimit(2)
        processProxyException {
            if (!readAll(buf)) {
                throw ProxyServerException("lost connection to socks5 server")
            }
        }
        buf.get()
        processProxyException {
            if (buf.get().toInt() != 1) {
                throw ProxyServerException("authentication failed")
            }
        }
    }

    /**
     * 请求连接
     */
    private suspend fun requestConnect(addr: InetSocketAddress) {
        val buf = ByteBuffer.allocate(256 + 8)
        buf.put(5)
        buf.put(1)
        buf.put(0)
        if (proxy.remoteDns) {
            // 域名
            buf.put(3)
            val host = addr.hostName
            buf.put(host.length.toByte())
            buf.put(host.toByteArray())
        } else {
            // TODO ipv6
            // ipv4
            buf.put(1)
            buf.put(addr.address.address)
        }
        buf.putShort(addr.port.toShort())
        buf.flip()
        processProxyException {
            writeAll(buf)
        }

        buf.clearAndLimit(5)  // 先读5个字节
        processProxyException {
            if (!readAll(buf)) {
                throw ProxyServerException("lost connection to socks5 server")
            }
            buf.flip()
        }
        buf.get()  // version
        val reply = buf.get().toInt() and 0xFF
        if (reply != 0) {
            // TODO 失败原因分别处理
            // 连接失败
            throw ConnectException("Connection refused")
        }
        buf.get()

        // 判断地址类型
        val remainLength = when (buf.get().toInt() and 0xFF) {
            1 -> 3 + 2 // ipv4
            4 -> 15 + 2 // ipv6
            3 -> buf.get().toInt() and 0xFF // 域名
            else -> throw ProxyServerException("unrecognized response")
        }
        buf.clearAndLimit(remainLength)
        processProxyException {
            if (!readAll(buf)) {
                throw ProxyServerException("lost connection to socks5 server")
            }
        }
    }
}