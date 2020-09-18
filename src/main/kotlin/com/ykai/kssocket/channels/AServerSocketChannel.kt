package com.ykai.kssocket.channels

import com.ykai.kssocket.emitter.DefaultIOEventEmitter
import com.ykai.kssocket.emitter.IOEventEmitter
import com.ykai.kssocket.emitter.InterestOp
import java.net.SocketAddress
import java.nio.channels.ServerSocketChannel

class AServerSocketChannel private constructor(
    override val nioChannel: ServerSocketChannel,
    override val emitter: IOEventEmitter
) : AsyncChannel() {
    companion object {
        @Suppress("BlockingMethodInNonBlockingContext")
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

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun accept(): ASocketChannel {
        emitter.waitEvent(
            nioChannel,
            InterestOp.Accept
        )
        return ASocketChannel.wrap(nioChannel.accept(), emitter)
    }
}