package com.ykai.kssocket.channels

import com.ykai.kssocket.emitter.DefaultIOEventEmitter
import com.ykai.kssocket.emitter.IOEventEmitter
import com.ykai.kssocket.emitter.InterestOp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class ADatagramChannel private constructor(
    override val nioChannel: DatagramChannel,
    override val emitter: IOEventEmitter
) : AsyncChannel() {
    companion object {
        @Suppress("BlockingMethodInNonBlockingContext")
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