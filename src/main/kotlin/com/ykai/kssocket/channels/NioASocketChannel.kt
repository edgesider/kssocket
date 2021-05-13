package com.ykai.kssocket.channels

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousSocketChannel
import java.nio.channels.CompletionHandler
import kotlin.coroutines.resume

class NioASocketChannel : IAStreamChannel {
    val nioChannel = AsynchronousSocketChannel.open()

    companion object {
        private val intHandler = object : CompletionHandler<Int, CancellableContinuation<Int>> {
            override fun completed(result: Int, cont: CancellableContinuation<Int>) {
                cont.resume(result)
            }

            override fun failed(exc: Throwable, cont: CancellableContinuation<Int>) {
                cont.cancel(exc)
            }
        }

        private val voidHandler = object : CompletionHandler<Void, CancellableContinuation<Unit>> {
            override fun completed(result: Void?, cont: CancellableContinuation<Unit>) {
                cont.resume(Unit)
            }

            override fun failed(exc: Throwable, cont: CancellableContinuation<Unit>) {
                cont.cancel(exc)
            }
        }
    }

    suspend fun connect(addr: InetSocketAddress) = suspendCancellableCoroutine<Unit> { cont ->
        nioChannel.connect(addr, cont, voidHandler)
    }

    override suspend fun read(buffer: ByteBuffer): Int = suspendCancellableCoroutine { cont ->
        nioChannel.read(buffer, cont, intHandler)
    }

    override suspend fun write(buffer: ByteBuffer): Int = suspendCancellableCoroutine { cont ->
        nioChannel.write(buffer, cont, intHandler)
    }

    override suspend fun readAll(buffer: ByteBuffer): Boolean {
        if (buffer.remaining() <= 0)
            return true
        while (true) {
            if (read(buffer) == -1) {
                // EOF
                break
            }
            if (buffer.remaining() == 0) {
                // enough
                break
            }
        }
        return buffer.remaining() == 0
    }

    override suspend fun writeAll(buffer: ByteBuffer) {
        if (buffer.remaining() <= 0)
            return
        while (true) {
            write(buffer)
            if (buffer.remaining() == 0) {
                // 写入完毕，只有这一个break可以正常退出循环
                break
            }
        }
        return
    }

    fun close() {
        nioChannel.close()
    }
}