package com.ykai.kssocket

import java.nio.channels.SelectableChannel
import kotlin.concurrent.thread

class FixedThreadPoolIOEventEmitter(private val poolSize: Int) : IOEventEmitter {
    private val emitters = Array(poolSize) { IOEventEmitterImpl() }
    private val threads = Array<Thread?>(poolSize) { null }

    override fun run() {
        emitters.forEachIndexed { i, emitter ->
            threads[i] = thread(start = true, isDaemon = true) {
                emitter.run()
            }
        }
        threads.forEach { it!!.join() }
    }

    override suspend fun register(chan: SelectableChannel) {
        findEmitter(chan).register(chan)
    }

    override suspend fun unregister(chan: SelectableChannel) {
        findEmitter(chan).unregister(chan)
    }

    override suspend fun waitEvent(chan: SelectableChannel, event: InterestOp) {
        findEmitter(chan).waitEvent(chan, event)
    }

    override fun close() {
        emitters.forEach { it.close() }
        threads.forEach { it!!.join() }
    }

    private fun findEmitter(chan: SelectableChannel) = emitters[chan.hashCode() % poolSize]
}