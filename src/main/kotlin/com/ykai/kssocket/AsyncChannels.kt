package com.ykai.kssocket

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.DatagramChannel
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

@Suppress("BlockingMethodInNonBlockingContext")
class ASocketChannel private constructor(private val socketChannel: SocketChannel) {
    companion object {
        suspend fun open(addr: SocketAddress? = null) =
            ASocketChannel(SocketChannel.open()).also {
                DefaultIOEventEmitter.register(it.socketChannel)
                if (addr != null)
                    it.connect(addr)
            }

        suspend fun wrap(socketChannel: SocketChannel) =
            ASocketChannel(socketChannel).also {
                DefaultIOEventEmitter.register(socketChannel)
            }
    }

    init {
        socketChannel.configureBlocking(false)
    }

    private val readLock = Mutex()
    private val writeLock = Mutex()

    val isOpen get() = socketChannel.isOpen
    val isConnected get() = socketChannel.isConnected
    val remoteAddress: SocketAddress get() = socketChannel.remoteAddress
    val localAddress: SocketAddress get() = socketChannel.localAddress

    fun shutdownInput(): SocketChannel = socketChannel.shutdownInput()
    fun shutdownOutput(): SocketChannel = socketChannel.shutdownOutput()

    suspend fun connect(addr: SocketAddress) {
        socketChannel.connect(addr)
        wait(InterestOp.Connect)
        socketChannel.finishConnect()
    }

    /**
     * 尝试读取buffer.remaining()个字符到[buffer]
     * @return 表明是否读满[buffer]。返回false意味着到了EOF，并且[buffer]未被读满。
     */
    suspend fun read(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() <= 0)
            return true
        readLock.withLock {
            while (true) {
                wait(InterestOp.Read)
                if (socketChannel.read(buffer) == -1) {
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
     * 尝试写入buffer.remaining()个字符
     */
    suspend fun write(buffer: ByteBuffer) {
        if (buffer.remaining() <= 0)
            return
        writeLock.withLock {
            while (true) {
                wait(InterestOp.Write)
                socketChannel.write(buffer)
                if (buffer.remaining() == 0) {
                    // 写入完毕，只有这一个break可以正常退出循环
                    break
                }
            }
            return
        }
    }

    private suspend fun wait(event: InterestOp) {
        try {
            DefaultIOEventEmitter.waitEvent(socketChannel, event)
        } catch (ex: UnregisterException) {
            throw ClosedChannelException()
        }
    }

    suspend fun close() = socketChannel.also {
        DefaultIOEventEmitter.unregister(it)
        it.close()
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
class AServerSocketChannel private constructor(private val socketChannel: ServerSocketChannel) {
    companion object {
        suspend fun open() = AServerSocketChannel(ServerSocketChannel.open()).also {
            DefaultIOEventEmitter.register(it.socketChannel)
        }
    }

    init {
        socketChannel.configureBlocking(false)
    }

    fun bind(addr: SocketAddress) {
        socketChannel.bind(addr)
    }

    suspend fun accept(): ASocketChannel {
        DefaultIOEventEmitter.waitEvent(
            socketChannel,
            InterestOp.Accept
        )
        return ASocketChannel.wrap(socketChannel.accept())
    }
}

@Suppress("BlockingMethodInNonBlockingContext")
class ADatagramChannel private constructor(private val datagramChannel: DatagramChannel) {
    companion object {
        suspend fun open() = ADatagramChannel(DatagramChannel.open()).also {
            DefaultIOEventEmitter.register(it.datagramChannel)
        }

        suspend fun wrap(datagramChannel: DatagramChannel) =
            ADatagramChannel(datagramChannel).also {
                DefaultIOEventEmitter.register(it.datagramChannel)
            }
    }

    init {
        datagramChannel.configureBlocking(false)
    }

    private val readLock = Mutex()
    private val writeLock = Mutex()

    val isConnected = datagramChannel.isConnected
    val remoteAddress = datagramChannel.remoteAddress

    suspend fun receive(buffer: ByteBuffer): SocketAddress? {
        if (buffer.remaining() <= 0)
            return null
        readLock.withLock {
            DefaultIOEventEmitter.waitEvent(datagramChannel, InterestOp.Read)
            return datagramChannel.receive(buffer)
        }
    }

    suspend fun send(buffer: ByteBuffer, target: SocketAddress) {
        if (buffer.remaining() <= 0)
            return
        writeLock.withLock {
            DefaultIOEventEmitter.waitEvent(datagramChannel, InterestOp.Write)
            datagramChannel.send(buffer, target)
        }
    }
}
