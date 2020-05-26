package com.ykai.kssocket

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.*

abstract class AsyncChannel {
    abstract val nioChannel: SelectableChannel

    @Suppress("BlockingMethodInNonBlockingContext")
    open suspend fun close() = nioChannel.let {
        DefaultIOEventEmitter.unregister(it)
        it.close()
    }

    fun closeBlocking() = runBlocking {
        close()
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
class ASocketChannel private constructor(override val nioChannel: SocketChannel) : AsyncChannel() {
    companion object {
        suspend fun open(addr: SocketAddress? = null) =
            ASocketChannel(SocketChannel.open()).also {
                DefaultIOEventEmitter.register(it.nioChannel)
                if (addr != null)
                    it.connect(addr)
            }

        suspend fun wrap(socketChannel: SocketChannel) =
            ASocketChannel(socketChannel).also {
                DefaultIOEventEmitter.register(socketChannel)
            }

        fun openBlocking(addr: SocketAddress? = null) = runBlocking {
            open(addr)
        }

        fun wrapBlocking(socketChannel: SocketChannel) = runBlocking {
            wrap(socketChannel)
        }
    }

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

    suspend fun connect(addr: SocketAddress) {
        nioChannel.connect(addr)
        wait(InterestOp.Connect)
        nioChannel.finishConnect()
    }

    /**
     * 尝试读取buffer.remaining()个字节到[buffer]
     * @return 表明是否读满[buffer]。返回false意味着到了EOF，并且[buffer]未被读满。
     */
    suspend fun readAll(buffer: ByteBuffer): Boolean {
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
    suspend fun writeAll(buffer: ByteBuffer) {
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
    suspend fun read(buffer: ByteBuffer): Int {
        readLock.withLock {
            wait(InterestOp.Read)
            return nioChannel.read(buffer)
        }
    }

    /**
     * 执行一次写入操作以尝试写入若干字节，长度不确定。
     * @return 写入的字节数，可能为0。
     */
    suspend fun write(buffer: ByteBuffer): Int {
        writeLock.withLock {
            wait(InterestOp.Write)
            return nioChannel.write(buffer)
        }
    }

    private suspend fun wait(event: InterestOp) {
        try {
            DefaultIOEventEmitter.waitEvent(nioChannel, event)
        } catch (ex: UnregisterException) {
            throw ClosedChannelException()
        }
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
class AServerSocketChannel private constructor(override val nioChannel: ServerSocketChannel) : AsyncChannel() {
    companion object {
        suspend fun open() = AServerSocketChannel(ServerSocketChannel.open()).also {
            DefaultIOEventEmitter.register(it.nioChannel)
        }
    }

    init {
        nioChannel.configureBlocking(false)
    }

    val localAddress = nioChannel.localAddress

    fun bind(addr: SocketAddress) {
        nioChannel.bind(addr)
    }

    suspend fun accept(): ASocketChannel {
        DefaultIOEventEmitter.waitEvent(
            nioChannel,
            InterestOp.Accept
        )
        return ASocketChannel.wrap(nioChannel.accept())
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
class ADatagramChannel private constructor(override val nioChannel: DatagramChannel) : AsyncChannel() {
    companion object {
        suspend fun open() = ADatagramChannel(DatagramChannel.open()).also {
            DefaultIOEventEmitter.register(it.nioChannel)
        }

        suspend fun wrap(datagramChannel: DatagramChannel) =
            ADatagramChannel(datagramChannel).also {
                DefaultIOEventEmitter.register(it.nioChannel)
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
            DefaultIOEventEmitter.waitEvent(nioChannel, InterestOp.Read)
            return nioChannel.receive(buffer)
        }
    }

    suspend fun send(buffer: ByteBuffer, target: SocketAddress) {
        if (buffer.remaining() <= 0)
            return
        writeLock.withLock {
            DefaultIOEventEmitter.waitEvent(nioChannel, InterestOp.Write)
            nioChannel.send(buffer, target)
        }
    }
}
