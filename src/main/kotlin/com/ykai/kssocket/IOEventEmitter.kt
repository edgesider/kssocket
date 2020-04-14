package com.ykai.kssocket

import java.nio.channels.SelectableChannel

/**
 * 监听IO事件。
 * 在使用之前需要[IOEventEmitter.register]，停止使用时要调用[IOEventEmitter.unregister]，
 */
interface IOEventEmitter : Runnable {
    /**
     * 注册通道。
     */
    suspend fun register(chan: SelectableChannel)

    /**
     * 取消注册通道。
     * 会使得正阻塞到[waitEvent]的方法抛出[UnregisterException]
     */
    suspend fun unregister(chan: SelectableChannel)

    /**
     * 等待[chan]上[event]事件的发生。
     * 如果[chan]在等待过程中被[unregister]，则这个方法会抛出[UnregisterException]。
     *
     * @throws UnregisterException
     */
    suspend fun waitEvent(chan: SelectableChannel, event: InterestOp)
    fun close()
}
