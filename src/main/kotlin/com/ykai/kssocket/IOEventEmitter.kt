package com.ykai.kssocket

import java.nio.channels.SelectableChannel
import kotlin.coroutines.Continuation

/**
 * 用于监听事件并将事件与续体进行关联：当某个感兴趣的事件发生时，唤醒续体。
 */
interface IOEventEmitter : Runnable {
    /**
     * 在一个新线程中运行该[IOEventEmitter]
     */
    fun runInThread()

    /**
     * 提交一个续体[continuation]，并与[chan]上的[op]事件关联
     */
    fun commitContinuation(continuation: Continuation<Unit>, chan: SelectableChannel, op: InterestOp)
}
