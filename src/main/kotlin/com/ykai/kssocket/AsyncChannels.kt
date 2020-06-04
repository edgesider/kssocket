@file:Suppress("BlockingMethodInNonBlockingContext")

package com.ykai.kssocket

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*

abstract class AsyncChannel {
    abstract val nioChannel: SelectableChannel
    abstract val emitter: IOEventEmitter

    open suspend fun close() = nioChannel.let {
        emitter.unregister(it)
        it.close()
    }

    fun closeBlocking() = runBlocking {
        close()
    }
}

interface IAStreamChannel {
    suspend fun read(buffer: ByteBuffer): Int
    suspend fun write(buffer: ByteBuffer): Int
    suspend fun readAll(buffer: ByteBuffer): Boolean
    suspend fun writeAll(buffer: ByteBuffer)
}

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

class Socks5ASocketChannelImpl(
    nioChannel: SocketChannel,
    private val proxy: Socks5Proxy,
    emitter: IOEventEmitter
) : SimpleASocketChannelImpl(nioChannel, emitter) {
    override suspend fun connect(addr: InetSocketAddress) {
        try {
            super.connect(proxy.addr)
        } catch (e: IOException) {
            throw ProxyServerException("an error occurred while connect socks4 server", e)
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

private fun ByteBuffer.clearAndLimit(limit: Int) {
    clear()
    limit(limit)
}

private suspend fun processProxyException(action: suspend () -> Any) {
    try {
        action()
    } catch (e: ProxyServerException) {
        throw e
    } catch (e: Exception) {
        throw ProxyServerException("an error occurred while request socks5 server", e)
    }
}

open class SimpleASocketChannelImpl(
    final override val nioChannel: SocketChannel,
    final override val emitter: IOEventEmitter
) : ASocketChannel() {
    init {
        nioChannel.configureBlocking(false)
    }

    private val readLock = Mutex()
    private val writeLock = Mutex()

    val isOpen get() = nioChannel.isOpen
    val isConnected get() = nioChannel.isConnected
    val remoteAddress: SocketAddress get() = nioChannel.remoteAddress
    val localAddress: SocketAddress get() = nioChannel.localAddress

    fun shutdownInput(): SocketChannel = nioChannel.shutdownInput()
    fun shutdownOutput(): SocketChannel = nioChannel.shutdownOutput()

    override suspend fun connect(addr: InetSocketAddress) {
        nioChannel.connect(addr)
        wait(InterestOp.Connect)
        nioChannel.finishConnect()
    }

    /**
     * 尝试读取buffer.remaining()个字节到[buffer]
     * @return 表明是否读满[buffer]。返回false意味着到了EOF，并且[buffer]未被读满。
     */
    override suspend fun readAll(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() <= 0)
            return true
        readLock.withLock {
            while (true) {
                wait(InterestOp.Read)
                if (nioChannel.read(buffer) == -1) {
                    // EOF
                    break
                }
                if (buffer.remaining() == 0) {
                    // enough
                    break
                }
            }
            return buffer.remaining() == 0
        }
    }

    /**
     * 尝试写入buffer.remaining()个字节
     */
    override suspend fun writeAll(buffer: ByteBuffer) {
        if (buffer.remaining() <= 0)
            return
        writeLock.withLock {
            while (true) {
                wait(InterestOp.Write)
                nioChannel.write(buffer)
                if (buffer.remaining() == 0) {
                    // 写入完毕，只有这一个break可以正常退出循环
                    break
                }
            }
            return
        }
    }

    /**
     * 执行一次读取操作以尝试读取若干字节，长度不确定。
     * @return 已读字节数，可能为0。如果到达EOF，则返回-1。
     */
    override suspend fun read(buffer: ByteBuffer): Int {
        readLock.withLock {
            wait(InterestOp.Read)
            return nioChannel.read(buffer)
        }
    }

    /**
     * 执行一次写入操作以尝试写入若干字节，长度不确定。
     * @return 写入的字节数，可能为0。
     */
    override suspend fun write(buffer: ByteBuffer): Int {
        writeLock.withLock {
            wait(InterestOp.Write)
            return nioChannel.write(buffer)
        }
    }

    private suspend fun wait(event: InterestOp) {
        try {
            emitter.waitEvent(nioChannel, event)
        } catch (ex: UnregisterException) {
            throw ClosedChannelException()
        }
    }
}

// TODO asynchronize dns query

abstract class ASocketChannel : AsyncChannel(), IAStreamChannel {
    companion object Builder {
        suspend fun open(
            addr: InetSocketAddress? = null,
            emitter: IOEventEmitter = DefaultIOEventEmitter,
            proxy: Proxy = NoProxy
        ): ASocketChannel =
            when (proxy) {
                is NoProxy -> SimpleASocketChannelImpl(SocketChannel.open(), emitter)
                is Socks4Proxy -> Socks4ASocketChannelImpl(SocketChannel.open(), proxy = proxy, emitter = emitter)
                is Socks5Proxy -> Socks5ASocketChannelImpl(SocketChannel.open(), proxy = proxy, emitter = emitter)
                else -> throw UnsupportedProxyException()
            }.also {
                emitter.register(it.nioChannel)
                if (addr != null)
                    it.connect(addr)
            }

        suspend fun wrap(
            socketChannel: SocketChannel,
            emitter: IOEventEmitter = DefaultIOEventEmitter
        ): ASocketChannel =
            SimpleASocketChannelImpl(socketChannel, emitter).also {
                emitter.register(socketChannel)
            }
    }

    abstract suspend fun connect(addr: InetSocketAddress)
}

class AServerSocketChannel private constructor(
    override val nioChannel: ServerSocketChannel,
    override val emitter: IOEventEmitter
) : AsyncChannel() {
    companion object {
        suspend fun open(emitter: IOEventEmitter = DefaultIOEventEmitter) =
            AServerSocketChannel(ServerSocketChannel.open(), emitter).also {
                emitter.register(it.nioChannel)
            }
    }

    init {
        nioChannel.configureBlocking(false)
    }

    val localAddress = nioChannel.localAddress

    fun bind(addr: SocketAddress, backlog: Int = -1) {
        if (backlog != -1) {
            nioChannel.bind(addr, backlog)
        } else {
            nioChannel.bind(addr)
        }
    }

    suspend fun accept(): ASocketChannel {
        emitter.waitEvent(
            nioChannel,
            InterestOp.Accept
        )
        return ASocketChannel.wrap(nioChannel.accept(), emitter)
    }
}

class ADatagramChannel private constructor(
    override val nioChannel: DatagramChannel,
    override val emitter: IOEventEmitter
) : AsyncChannel() {
    companion object {
        suspend fun open(emitter: IOEventEmitter = DefaultIOEventEmitter) =
            ADatagramChannel(DatagramChannel.open(), emitter).also {
                emitter.register(it.nioChannel)
            }

        suspend fun wrap(
            datagramChannel: DatagramChannel,
            emitter: IOEventEmitter = DefaultIOEventEmitter
        ) = ADatagramChannel(datagramChannel, emitter).also {
            emitter.register(it.nioChannel)
        }
    }

    init {
        nioChannel.configureBlocking(false)
    }

    private val readLock = Mutex()
    private val writeLock = Mutex()

    val isConnected = nioChannel.isConnected
    val localAddress = nioChannel.localAddress
    val remoteAddress = nioChannel.remoteAddress

    suspend fun receive(buffer: ByteBuffer): SocketAddress? {
        if (buffer.remaining() <= 0)
            return null
        readLock.withLock {
            emitter.waitEvent(nioChannel, InterestOp.Read)
            return nioChannel.receive(buffer)
        }
    }

    suspend fun send(buffer: ByteBuffer, target: SocketAddress) {
        if (buffer.remaining() <= 0)
            return
        writeLock.withLock {
            emitter.waitEvent(nioChannel, InterestOp.Write)
            nioChannel.send(buffer, target)
        }
    }
}

class UnsupportedProxyException : Exception()
class ProxyServerException(msg: String, cause: Throwable? = null) : Exception(msg, cause)
