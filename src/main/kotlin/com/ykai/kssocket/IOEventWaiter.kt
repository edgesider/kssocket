package com.ykai.kssocket

import java.nio.channels.SelectableChannel

/**
 * 在协程中等待某个IO事件的发生。
 */
interface IOEventWaiter {
    /**
     * 在[chan]上挂起等待[event]事件。
     */
    suspend fun waitEvent(chan: SelectableChannel, event: InterestOp)
}

