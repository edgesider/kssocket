package com.ykai.kssocket

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
        DefaultIOEventWaiter.waitEvent(
            socketChannel,
            InterestOp.Connect
        )
        socketChannel.finishConnect()
    }

    /**
     * 尝试读取buffer.remaining()个字符到[buffer]
     * @return 表明是否读满[buffer]。返回false意味着到了EOF，并且[buffer]未被读满。
     */
    suspend fun read(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() < 0)
            return true
        readLock.withLock {
            while (true) {
                DefaultIOEventWaiter.waitEvent(
                    socketChannel,
                    InterestOp.Read
                )
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
        if (buffer.remaining() < 0)
            return
        writeLock.withLock {
            while (true) {
                DefaultIOEventWaiter.waitEvent(
                    socketChannel,
                    InterestOp.Write
                )
                socketChannel.write(buffer)
                if (buffer.remaining() == 0) {
                    // 写入完毕，只有这一个break可以正常退出循环
                    break
                }
            }
            return
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
        DefaultIOEventWaiter.waitEvent(
            socketChannel,
            InterestOp.Accept
        )
        return ASocketChannel.wrap(socketChannel.accept())
    }
}
