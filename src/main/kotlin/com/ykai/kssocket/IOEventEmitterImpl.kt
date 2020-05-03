package com.ykai.kssocket

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import kotlin.coroutines.resume

class IOEventEmitterImpl : IOEventEmitter {
    private val selector = Selector.open()
    private val modifyPipe = ModifyPipe(selector)

    override fun run() {
        while (true) {
            if (selector.select() == 0)
                continue
            selector.selectedKeys().remove(modifyPipe.pipeSourceKey)
            modifyPipe.recvAll { msg ->
                try {
                    when (msg.type) {
                        ModifyPipe.ModifyType.Register -> {
                            msg.chan.register(selector, 0, TypedContinuationQueues(msg.chan))
                        }
                        ModifyPipe.ModifyType.Unregister -> {
                            msg.chan.keyFor(selector).cancel()
                        }
                    }
                    msg.cont.resume(Unit)
                } catch (ex: java.lang.Exception) {
                    msg.cont.cancel(ex)
                }
            }
            for (key in selector.selectedKeys()) {
                if (!key.isValid) {
                    // 可能被另一个线程关闭
                    key.cancel()
                    continue
                }
                if (key.isAcceptable) {
                    consume(key, InterestOp.Accept)
                }
                if (key.isReadable) {
                    consume(key, InterestOp.Read)
                }
                if (key.isWritable) {
                    consume(key, InterestOp.Write)
                }
                if (key.isConnectable) {
                    consume(key, InterestOp.Connect)
                }
                selector.selectedKeys().remove(key)
            }
        }
    }

    override suspend fun register(chan: SelectableChannel) {
        try {
            suspendCancellableCoroutine<Unit> {
                modifyPipe.sendRegister(chan, it)
            }
        } catch (ex: CancellationException) {
            throw ex.cause ?: ex
        }
    }

    /**
     * @throws UnregisterException
     */
    override suspend fun unregister(chan: SelectableChannel) {
        try {
            (chan.keyFor(selector).attachment() as TypedContinuationQueues).abort()
            suspendCancellableCoroutine<Unit> {
                modifyPipe.sendUnregister(chan, it)
            }
        } catch (ex: CancellationException) {
            throw ex.cause ?: ex
        }
    }

    override suspend fun waitEvent(chan: SelectableChannel, event: InterestOp) {
        chan.keyFor(selector)?.let { key ->
            try {
                suspendCancellableCoroutine<Unit> {
                    (key.attachment() as TypedContinuationQueues).add(it, event)
                }
            } catch (ex: CancellationException) {
                throw UnregisterException()
            }
        } ?: throw Exception("not register")
    }

    override fun close() {
        TODO()
    }

    private fun consume(key: SelectionKey, event: InterestOp) {
        (key.attachment() as TypedContinuationQueues).consume(event)
    }

    private class ModifyPipe(val selector: Selector) {
        enum class ModifyType { Register, Unregister }
        class ModifyMessage(val type: ModifyType, val chan: SelectableChannel, val cont: CancellableContinuation<Unit>)

        private val pipe = Pipe.open()
        private val msgQueue = mutableListOf<ModifyMessage>()
        internal val pipeSourceKey: SelectionKey

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

        fun send(message: ModifyMessage) {
            synchronized(pipe) {
                pipe.sink().write(ByteBuffer.wrap("@".toByteArray()))
                msgQueue.add(message)
            }
        }

        fun recvAll(action: (message: ModifyMessage) -> Unit) {
            val buf = ByteBuffer.allocate(100)
            while (true) {
                val n = pipe.source().read(buf)
                if (n == 0)
                    break
                for (i in 1..n) {
                    action(msgQueue.removeAt(0))
                }
            }
        }
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

    private class ContinuationQueue : ArrayList<CancellableContinuation<Unit>>()
    private inner class TypedContinuationQueues(val chan: SelectableChannel) {
        val connectable = ContinuationQueue()
        val readable = ContinuationQueue()
        val writable = ContinuationQueue()
        val acceptable = ContinuationQueue()

        fun getByEvent(op: InterestOp): ContinuationQueue = when (op) {
            InterestOp.Read -> readable
            InterestOp.Write -> writable
            InterestOp.Accept -> acceptable
            InterestOp.Connect -> connectable
        }

        fun add(cont: CancellableContinuation<Unit>, event: InterestOp) {
            // 保证addOp和queue.add同时操作
            synchronized(chan) {
                getByEvent(event).add(cont)
                chan.keyFor(selector).addOp(event)
                selector.wakeup()
            }
        }

        fun consume(event: InterestOp) {
            // 保证addOp和queue.add同时操作
            synchronized(chan) {
                getByEvent(event).let { q ->
                    q.removeAt(0).resume(Unit)
                    if (q.isEmpty()) {
                        chan.keyFor(selector).removeOp(event)
                    }
                }
            }
        }

        fun abort(event: InterestOp? = null) {
            if (event == null) {
                InterestOp.values().forEach { op ->
                    getByEvent(op).forEach { cont ->
                        cont.cancel()
                    }
                }
            } else {
                getByEvent(event).forEach { cont ->
                    cont.cancel()
                }
            }
        }
    }

}

class UnregisterException : Exception()
