package com.ykai.kssocket.emitter

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.resume

class IOEventEmitterImpl : IOEventEmitter {
    private val selector = Selector.open()
    private val modifyPipe = ModifyPipe(selector)

    override fun run() {
        while (true) {
            if (selector.selectNow() == 0) {  // 清理wakeup
                if (selector.select() == 0 && Thread.interrupted()) {
                    break
                }
            }
            modifyPipe.recvAll { msg ->
                try {
                    when (msg.type) {
                        ModifyPipe.ModifyType.Register -> {
                            msg.chan.register(selector, 0, TypedContinuationQueues())
                        }
                        ModifyPipe.ModifyType.UnregisterNoWait,
                        ModifyPipe.ModifyType.Unregister -> {
                            msg.chan.keyFor(selector)?.let { key ->
                                // key.cancel()之后waitEvent就无法加入新的续体了
                                key.cancel()
                                key.abortCont()
                            }
                        }
                    }
                } catch (ex: Exception) {
                    msg.cont?.cancel(ex)
                    return@recvAll
                }
                if (msg.cont != null)
                    try {
                        msg.cont.resume(Unit)
                    } catch (e: CancellationException) {
                    }
            }
            val iter = selector.selectedKeys().iterator()
            while (iter.hasNext()) {
                val key = iter.next()
                if (key == modifyPipe.pipeSourceKey)
                    continue
                if (!key.isValid) {
                    // 可能被另一个线程关闭
                    iter.remove()
                    continue
                }
                if (key.isAcceptable) {
                    key.consumeCont(InterestOp.Accept)
                }
                if (key.isReadable) {
                    key.consumeCont(InterestOp.Read)
                }
                if (key.isWritable) {
                    key.consumeCont(InterestOp.Write)
                }
                if (key.isConnectable) {
                    key.consumeCont(InterestOp.Connect)
                }
                iter.remove()
            }
        }
    }

    override suspend fun register(chan: SelectableChannel) {
        if (chan.keyFor(selector) != null) {
            throw RegisteredException()
        }
        try {
            suspendCancellableCoroutine<Unit> {
                modifyPipe.sendRegister(chan, it)
            }
        } catch (ex: CancellationException) {
            modifyPipe.sendUnregisterNoWait(chan)
            smartThrow(ex)
        }
    }

    override suspend fun unregister(chan: SelectableChannel) {
        chan.keyFor(selector)?.let {
            try {
                suspendCancellableCoroutine<Unit> {
                    modifyPipe.sendUnregister(chan, it)
                }
            } catch (ex: CancellationException) {
                smartThrow(ex)
            }
        } ?: throw NotRegisterException()
    }

    override suspend fun waitEvent(chan: SelectableChannel, event: InterestOp) {
        chan.keyFor(selector)?.let { key ->
            try {
                suspendCancellableCoroutine<Unit> {
                    key.addCont(it, event)
                }
            } catch (ex: CancellationException) {
                // TODO 是否需要取消op
                smartThrow(ex)
            }
        } ?: throw NotRegisterException()
    }

    /**
     * 超时之后并没有主动处理[InterestOp]和[TypedContinuationQueues]，
     * [TypedContinuationQueues.consume]方法会忽略已经取消的续体。
     *
     * 如果该事件在超时异常已经抛出后触发，会导致[selector]的意外唤醒，但这是无法避免的，因为即使在这里处理了
     * [InterestOp]，也需要调用[Selector.wakeup]才能使其生效。
     */
    override suspend fun waitEvent(chan: SelectableChannel, event: InterestOp, timeMillis: Long) {
        withTimeout(timeMillis) {
            waitEvent(chan, event)
        }
    }

    override fun close() {
        TODO()
    }

    /**
     * 向[SelectionKey]中添加一个[InterestOp]
     * [SelectionKey.interestOps]并不会与[SelectableChannel.register]和[Selector.select]竞争
     * 同一把锁，所以并不需要通过队列进行操作，只需要在添加之后调用一下[Selector.wakeup]即可。
     */
    private fun SelectionKey.addOp(op: InterestOp) {
        if (interestOps() and op.toInt() == 0)
            this.interestOps(this.interestOps() or op.toInt())
    }

    private fun SelectionKey.removeOp(op: InterestOp) {
        if (interestOps() and op.toInt() != 0)
            this.interestOps(this.interestOps() and (-1 xor op.toInt()))
    }

    private fun SelectionKey.consumeCont(event: InterestOp) =
        (this.attachment() as TypedContinuationQueues).consume(this, event)

    private fun SelectionKey.addCont(cont: CancellableContinuation<Unit>, event: InterestOp) =
        (this.attachment() as TypedContinuationQueues).add(this, cont, event)

    private fun SelectionKey.abortCont(event: InterestOp? = null) =
        (this.attachment() as TypedContinuationQueues).abort(event)

    private inner class TypedContinuationQueues {
        private inner class ContinuationQueue : ConcurrentLinkedQueue<CancellableContinuation<Unit>>()

        private val connectable = ContinuationQueue()
        private val readable = ContinuationQueue()
        private val writable = ContinuationQueue()
        private val acceptable = ContinuationQueue()

        fun getByEvent(op: InterestOp): ContinuationQueue = when (op) {
            InterestOp.Read -> readable
            InterestOp.Write -> writable
            InterestOp.Accept -> acceptable
            InterestOp.Connect -> connectable
        }

        fun add(key: SelectionKey, cont: CancellableContinuation<Unit>, event: InterestOp) {
            // 保证addOp和queue.add同时操作
            synchronized(key) {
                getByEvent(event).add(cont)
                key.addOp(event)
                selector.wakeup()
            }
        }

        fun consume(key: SelectionKey, event: InterestOp) {
            // 保证addOp和queue.add同时操作
            synchronized(key) {
                getByEvent(event).let { q ->
                    q.poll()?.let {
                        try {
                            // waitEvent所在的协程可能已经被取消
                            it.resume(Unit)
                        } catch (e: CancellationException) {
                        }
                        if (q.isEmpty()) {
                            key.removeOp(event)
                        }
                    }
                }
            }
        }

        fun abort(event: InterestOp? = null) {
            if (event == null) {
                InterestOp.values().forEach { op ->
                    getByEvent(op).forEach { cont ->
                        cont.cancel(UnregisterException())
                    }
                }
            } else {
                getByEvent(event).forEach { cont ->
                    cont.cancel(UnregisterException())
                }
            }
        }
    }

    private class ModifyPipe(val selector: Selector) {
        enum class ModifyType { Register, Unregister, UnregisterNoWait }
        class ModifyMessage(val type: ModifyType, val chan: SelectableChannel, val cont: CancellableContinuation<Unit>?)

        private val pipe = Pipe.open()
        private val msgQueue = ConcurrentLinkedQueue<ModifyMessage>()
        val pipeSourceKey: SelectionKey

        init {
            pipe.source().let {
                it.configureBlocking(false)
                pipeSourceKey = it.register(selector, SelectionKey.OP_READ)
            }
        }

        fun sendRegister(chan: SelectableChannel, cont: CancellableContinuation<Unit>) {
            send(ModifyMessage(ModifyType.Register, chan, cont))
        }

        fun sendUnregister(chan: SelectableChannel, cont: CancellableContinuation<Unit>) {
            send(ModifyMessage(ModifyType.Unregister, chan, cont))
        }

        fun sendUnregisterNoWait(chan: SelectableChannel) {
            send(ModifyMessage(ModifyType.UnregisterNoWait, chan, null))
        }

        fun send(message: ModifyMessage) {
            synchronized(msgQueue) {
                msgQueue.add(message)
                pipe.sink().write(ByteBuffer.wrap("@".toByteArray()))
            }
        }

        inline fun recvAll(action: (message: ModifyMessage) -> Unit) {
            synchronized(msgQueue) {
                val buf = ByteBuffer.allocate(100)
                while (true) {
                    val n = pipe.source().read(buf)
                    if (n == 0)
                        break
                    for (i in 1..n) {
                        action(msgQueue.poll())
                    }
                }
            }
        }
    }
}

/**
 * [CancellationException]有可能是由Emitter内部触发的，也有可能是该函数所在的协程被取消，
 * 这个函数会进行判断抛出合适的异常。
 */
private fun smartThrow(ex: CancellationException): Nothing =
    if (ex.cause is IOEventEmitterException)
        throw ex.cause as IOEventEmitterException
    else
        throw ex

open class IOEventEmitterException : Exception()
class UnregisterException : IOEventEmitterException()
class NotRegisterException : IOEventEmitterException()
class RegisteredException : IOEventEmitterException()
class ClosedEmitterException : IOEventEmitterException()
