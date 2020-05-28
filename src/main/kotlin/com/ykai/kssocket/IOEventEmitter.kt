package com.ykai.kssocket

import kotlinx.coroutines.CancellationException
import java.nio.channels.SelectableChannel

/**
 * 监听IO事件。
 * 在使用之前需要[IOEventEmitter.register]，停止使用时要调用[IOEventEmitter.unregister]，
 */
interface IOEventEmitter : Runnable {
    /**
     * 注册通道。
     *
     * @throws RegisteredException [chan]已经被注册到此emitter
     * @throws [SelectableChannel.register]抛出的异常
     */
    suspend fun register(chan: SelectableChannel)

    /**
     * 取消注册通道。
     * 会使得正阻塞到[waitEvent]的方法抛出[UnregisterException]
     *
     * @throws NotRegisterException
     */
    suspend fun unregister(chan: SelectableChannel)

    /**
     * 等待[chan]上[event]事件的发生。
     * 如果[chan]在等待过程中被其他协程[unregister]，这个方法会抛出[UnregisterException]。
     *
     * @throws CancellationException 所在的协程被取消
     * @throws UnregisterException 其他协程取消注册
     */
    suspend fun waitEvent(chan: SelectableChannel, event: InterestOp)

    // TODO waitEvent timeout

    fun close()
}
