package com.ykai.kssocket.channels

import com.ykai.kssocket.emitter.DefaultIOEventEmitter
import com.ykai.kssocket.emitter.IOEventEmitter
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

abstract class ASocketChannel : AsyncChannel(), IAStreamChannel {
    companion object Builder {
        @Suppress("BlockingMethodInNonBlockingContext")
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

    abstract override val nioChannel: SocketChannel

    abstract suspend fun connect(addr: InetSocketAddress)
}