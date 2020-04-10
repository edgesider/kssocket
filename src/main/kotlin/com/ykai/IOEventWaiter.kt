package com.ykai

import com.ykai.InterestOp
import java.nio.channels.SelectableChannel

/**
 * 等待一个IO事件的发生
 */
interface IOEventWaiter {
    suspend fun waitEvent(chan: SelectableChannel, event: InterestOp)
}

