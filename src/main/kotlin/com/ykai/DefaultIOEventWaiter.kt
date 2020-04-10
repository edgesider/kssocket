package com.ykai

import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.channels.SelectableChannel

object DefaultIOEventWaiter : IOEventWaiter {
    private val dispatcher: IOEventEmitter = IOEventEmitterImpl()

    init {
        dispatcher.runInThread()
    }

    override suspend fun waitEvent(chan: SelectableChannel, event: InterestOp) {
        suspendCancellableCoroutine<Unit> { con ->
            dispatcher.commitContinuation(con, chan, event)
        }
    }
}

