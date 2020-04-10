package com.ykai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

@Suppress("BlockingMethodInNonBlockingContext")
class ASocketChannel private constructor(private val socketChannel: SocketChannel) {
    companion object {
        suspend fun open(addr: SocketAddress? = null) =
            ASocketChannel(SocketChannel.open()).also {
                if (addr != null)
                    it.connect(addr)
            }

        fun wrap(socketChannel: SocketChannel) = ASocketChannel(socketChannel)
    }

    init {
        socketChannel.configureBlocking(false)
    }

    private val readLock = Mutex()
    private val writeLock = Mutex()

    suspend fun connect(addr: SocketAddress) {
        socketChannel.connect(addr)
        DefaultIOEventWaiter.waitEvent(socketChannel, InterestOp.Connect)
        socketChannel.finishConnect()
    }

    // 返回值表明是否读取到了足够的字节（== buffer.remaining()）
    suspend fun read(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 0)
            return true
        readLock.withLock {
            while (true) {
                DefaultIOEventWaiter.waitEvent(socketChannel, InterestOp.Read)
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

    suspend fun write(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 0)
            return true
        writeLock.withLock {
            while (true) {
                DefaultIOEventWaiter.waitEvent(socketChannel, InterestOp.Write)
                socketChannel.write(buffer)
                if (buffer.remaining() == 0) {
                    // over
                    break
                }
            }
            return buffer.remaining() == 0
        }
    }

    fun close() = socketChannel.close()
}

@Suppress("BlockingMethodInNonBlockingContext")
class AServerSocketChannel private constructor(private val socketChannel: ServerSocketChannel) {
    companion object {
        fun open() = AServerSocketChannel(ServerSocketChannel.open()).also {
            it.socketChannel.configureBlocking(false)
        }
    }

    fun bind(addr: SocketAddress) {
        socketChannel.bind(addr)
    }

    suspend fun accept(): ASocketChannel {
        DefaultIOEventWaiter.waitEvent(socketChannel, InterestOp.Accept)
        return ASocketChannel.wrap(socketChannel.accept())
    }
}
