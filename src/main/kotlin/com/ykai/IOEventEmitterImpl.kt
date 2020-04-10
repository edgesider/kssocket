package com.ykai

import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class IOEventEmitterImpl : IOEventEmitter {
    private class ContinuationQueue : ArrayList<Continuation<Unit>>()
    private class TypedContinuationList {
        val connectable = ContinuationQueue()
        val readable = ContinuationQueue()
        val writable = ContinuationQueue()
        val acceptable = ContinuationQueue()

        fun getByOp(op: InterestOp): ContinuationQueue = when (op) {
            InterestOp.Read -> readable
            InterestOp.Write -> writable
            InterestOp.Accept -> acceptable
            InterestOp.Connect -> connectable
        }
    }

    /**
     * 维护每个channel对应的续体集合
     * 一旦某个channel有了新的等待操作：
     * --如果对应的事件还没有被监听，则开始监听；
     * ----如果该channel还没有注册到[selector]，则先注册
     * --将等待操作的续体保存到[channelToContinuations]对应的队列中
     * 当等待的条件达成时：
     * --从队列中弹出续体并使其继续；
     * --如果队列为空，则停止监听对应事件。
     * 当[channelToContinuations]中某个channel对应的续体队列都为空时，说明没有任何续体与之关联，此时其interestOps也为空：
     * --则取消对[selector]的注册，并从[channelToContinuations]中删除该channel
     */
    private val channelToContinuations = mutableMapOf<SelectableChannel, TypedContinuationList>()
    private val selector = Selector.open()
    private val modifyPipe = SelectorModifyPipe(selector)

    override fun run() {
        while (true) {
            if (selector.select() == 0)
                continue
            modifyPipe.processModify { msg ->
                when (msg.type) {
                    SelectorModifyPipe.SelectorModifyType.AddOp -> {
                        if (msg.channel.isRegistered) {
                            msg.channel.keyFor(selector).addOp(msg.op)
                        } else {
                            msg.channel.register(selector, msg.op.toInt())
                        }
                    }
                    SelectorModifyPipe.SelectorModifyType.RemoveOp -> {
                        msg.channel.keyFor(selector).let { key ->
                            key.removeOp(msg.op)
                            key.cancelIfUseless()
                        }
                    }
                }
            }
            selector.selectedKeys().forEach { key ->
                selector.selectedKeys().remove(key)
                if (key.isConnectable) {
                    consumeContinuation(key, InterestOp.Connect)
                }
                if (key.isReadable) {
                    consumeContinuation(key, InterestOp.Read)
                }
                if (key.isWritable) {
                    consumeContinuation(key, InterestOp.Write)
                }
                if (key.isAcceptable) {
                    consumeContinuation(key, InterestOp.Accept)
                }
                key.cancelIfUseless()
            }
        }
    }

    override fun runInThread() {
        Thread(this, "EventEmitter").let {
            it.isDaemon = false
            it.start()
        }
    }

    override fun commitContinuation(continuation: Continuation<Unit>, chan: SelectableChannel, op: InterestOp) {
        (channelToContinuations[chan] ?: TypedContinuationList()
            .also { channelToContinuations[chan] = it })
                .getByOp(op)
                .add(continuation)
        modifyPipe.addOp(chan, op)
    }

    /**
     * 消耗一个[key].channel所关联的[op]操作的续体
     */
    private fun consumeContinuation(key: SelectionKey, op: InterestOp) {
        channelToContinuations[key.channel()]!!
                .getByOp(op)
                .let { queue ->
                    queue.removeAt(0).resume(Unit)
                    queue.ifEmpty { key.removeOp(op) }
                }
    }

    private fun SelectionKey.addOp(op: InterestOp) {
        this.interestOps(this.interestOps() or op.toInt())
    }

    private fun SelectionKey.removeOp(op: InterestOp) {
        this.interestOps(this.interestOps() and (-1 xor op.toInt()))
    }

    private fun SelectionKey.cancelIfUseless() {
        if (this.interestOps() == 0) {
            this.cancel()
            channelToContinuations.remove(this.channel())
        }
    }

    /**
     * [Selector.wakeup]不能保证唤醒所对应的操作一定会被处理。
     * 将所有的注册修改操作发送至[wakeupPipe]和[modifyQueue]，就可以保证每次操作都能被很快处理。
     * */
    private class SelectorModifyPipe(val selector: Selector) {
        enum class SelectorModifyType { AddOp, RemoveOp }
        class SelectorModifyMessage(val type: SelectorModifyType,
                                    val channel: SelectableChannel,
                                    val op: InterestOp
        )

        /**
         * 要想提交一个修改操作时，就会向[wakeupPipe]中写入一个字符，并向[modifyQueue]中添加一项，
         * 之后[selector]就会被及时唤醒，并调用[processModify]来处理修改操作
         *
         * [writeLock]用来保证[wakeupPipe]和[modifyQueue]的同步写入。
         */
        private val writeLock = ReentrantLock()
        private val wakeupPipe = Pipe.open()
        private val modifyQueue = mutableListOf<SelectorModifyMessage>()

        /**
         * 在[selector]注册[wakeupPipe]获得的[SelectionKey]
         */
        private val modifyReadKey: SelectionKey

        /**
         * 写入[wakeupPipe]的字符
         */
        private val wakeupByte = ByteBuffer.wrap(byteArrayOf('@'.toByte()))

        init {
            wakeupPipe.source().also {
                it.configureBlocking(false)
                modifyReadKey = it.register(selector, SelectionKey.OP_READ)
            }
        }

        /**
         * 提交一个添加[InterestOp]的修改请求
         */
        fun addOp(chan: SelectableChannel, op: InterestOp) {
            writeLock.withLock {
                modifyQueue.add(
                    SelectorModifyMessage(
                        SelectorModifyType.AddOp,
                        chan,
                        op
                    )
                )
                wakeupPipe.sink().write(wakeupByte.also { it.clear() })
            }
        }

        /**
         * 提交一个删除[InterestOp]的修改请求
         */
        fun removeOp(chan: SelectableChannel, op: InterestOp) {
            writeLock.withLock {
                modifyQueue.add(
                    SelectorModifyMessage(
                        SelectorModifyType.RemoveOp,
                        chan,
                        op
                    )
                )
                wakeupPipe.sink().write(wakeupByte.also { it.clear() })
            }
        }

        /**
         * 处理[selector]的修改请求
         */
        fun processModify(action: (SelectorModifyMessage) -> Unit) {
            if (selector.selectedKeys().remove(modifyReadKey)) {
                val buffer = ByteBuffer.allocate(100)
                writeLock.withLock {
                    while (true) {
                        val n = wakeupPipe.source().read(buffer)
                        for (i in 1..n) {
                            action(modifyQueue.removeAt(0))
                        }
                        if (n != buffer.limit()) {
                            // 没有更多可读的
                            break
                        }
                        buffer.clear()
                    }
                }
            }
        }
    }

}

