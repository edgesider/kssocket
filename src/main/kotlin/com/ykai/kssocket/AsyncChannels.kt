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
        val buf = ByteBuffer.allocate(32)
        makeRequest(buf, addr, proxy.remoteDns)
        buf.flip()
        try {
            writeAll(buf)
        } catch (e: IOException) {
            runCatching { close() }
            throw ProxyServerException("an error occurred while request socks4 server", e)
        }
        buf.clear()

        buf.limit(8) // 读取八个字节
        try {
            if (!readAll(buf)) {
                throw ProxyServerException("an error occurred while request socks4 server")
            }
        } catch (e: IOException) {
            runCatching { close() }
            throw ProxyServerException("an error occurred while request socks4 server", e)
        }

        buf.position(0)
        try {
            checkResponse(buf)
        } catch (e: ProxyServerException) {
            runCatching { close() }
            throw e
        }
    }

    private fun makeRequest(buf: ByteBuffer, addr: InetSocketAddress, remoteDns: Boolean) {
        buf.put(4)  // version 4
        buf.put(1)  // CONNECT request
        buf.putShort(addr.port.toShort())
        if (remoteDns) {
            buf.put(byteArrayOf(0, 0, 0, 1))  // 0.0.0.1
            buf.put(0)  // null for ip
            buf.put(addr.hostName.toByteArray(Charsets.US_ASCII))
            buf.put(0)  // null for hostname
        } else {
            buf.put(addr.address.address)
            buf.put(0)  // null for ip
        }
    }

    private fun checkResponse(buf: ByteBuffer) {
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

class Socks5ASocketChannelImpl()

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
