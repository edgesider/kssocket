package com.ykai.kssocket.channels

import com.ykai.kssocket.emitter.IOEventEmitter
import com.ykai.kssocket.emitter.InterestOp
import com.ykai.kssocket.emitter.UnregisterException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.SocketChannel

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

    @Suppress("BlockingMethodInNonBlockingContext")
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