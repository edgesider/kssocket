package com.ykai.kssocket.channels

import com.ykai.kssocket.emitter.IOEventEmitter
import kotlinx.coroutines.runBlocking
import java.nio.channels.SelectableChannel

abstract class AsyncChannel {
    abstract val nioChannel: SelectableChannel
    abstract val emitter: IOEventEmitter

    @Suppress("BlockingMethodInNonBlockingContext")
    open suspend fun close() = nioChannel.let {
        emitter.unregister(it)
        it.close()
    }

    fun closeBlocking() = runBlocking {
        close()
    }
}