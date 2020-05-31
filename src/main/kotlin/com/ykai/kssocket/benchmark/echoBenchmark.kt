package com.ykai.kssocket.benchmark

import com.ykai.kssocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

fun main() = runBlocking {
    echoBenchmark(
        serverPort = 30000,
        connectionCount = 500,
        echoMessageSize = 100,
        echoTimes = 500,
        serverThreadNumber = 10,
        clientThreadNumber = 10,
        emitterNumber = 10
    )
}

fun echoBenchmark(
    serverPort: Int, connectionCount: Int, echoMessageSize: Int, echoTimes: Int,
    serverThreadNumber: Int, clientThreadNumber: Int, emitterNumber: Int
) = runBlocking {
    val serverThreadPool = Executors.newFixedThreadPool(serverThreadNumber).asCoroutineDispatcher()
    val clientThreadPool = Executors.newFixedThreadPool(clientThreadNumber).asCoroutineDispatcher()
    val emitter = FixedThreadPoolIOEventEmitter(emitterNumber).also {
        it.runInNewThread()
    }

    logServer("starting...")
    val server = AServerSocketChannel.open(emitter = emitter).also {
        it.bind(InetSocketAddress(serverPort), connectionCount)
        logServer("started")
    }
    val serverJob = runEchoServer(serverThreadPool, server, echoMessageSize)
    runEchoClients(clientThreadPool, emitter, serverPort, connectionCount, echoTimes, echoMessageSize)
        .join()

    try {
        serverJob.cancelAndJoin()
    } finally {
        logServer("exiting...")
        server.close()
    }
    serverThreadPool.close()
    clientThreadPool.close()
}

fun CoroutineScope.runEchoServer(
    dispatcher: ExecutorCoroutineDispatcher,
    chan: AServerSocketChannel,
    echoMessageSize: Int
) = launch(dispatcher) {
    while (true) {
        val client = chan.accept()
        logServer("accepted new client: $client")
        launch(dispatcher + CoroutineExceptionHandler { _, ex ->
            logServer("client closed with exception: $ex")
        }) clientJob@{
            val buf = ByteBuffer.allocate(echoMessageSize)
            while (true) {
                buf.clear()
                val readSize = client.read(buf)
                if (readSize == -1) {
                    logServer("client $client closed: $client")
                    return@clientJob
                }
                buf.flip()
                client.writeAll(buf)
            }
        }
    }
}


fun CoroutineScope.runEchoClients(
    threadPool: ExecutorCoroutineDispatcher,
    emitter: IOEventEmitter,
    serverPort: Int,
    clientCount: Int,
    echoTimes: Int,
    echoMessageSize: Int
) = launch {
    val durationLock = Mutex()
    var maxDuration = Double.MIN_VALUE
    var minDuration = Double.MAX_VALUE
    val clientJobs = ConcurrentLinkedQueue<Job>()
    for (j in 0 until clientCount) {
        launch(threadPool) client@{
            val client = ASocketChannel.open(InetSocketAddress(serverPort), emitter)
            logClient("connected")
            val buf = ByteBuffer.wrap(ByteArray(echoMessageSize) { it.toByte() })
            val start = System.currentTimeMillis()
            for (i in 0 until echoTimes) {
                buf.clear()
                try {
                    client.writeAll(buf)
                } catch (e: Exception) {
                    logClient("write error: $e").let { }
                    return@client
                }
                buf.clear()
                client.read(buf)
            }
            val end = System.currentTimeMillis()
            val duration = (end - start) / 1000.0
            durationLock.withLock {
                maxDuration = max(duration, maxDuration)
                minDuration = min(duration, minDuration)
            }
            logClient("$client send over in $duration(ms)")
        }.let {
            clientJobs.offer(it)
        }
    }
    clientJobs.forEach { it.join() }
    logClient("Jobs all over. Max duration: $maxDuration, min duration: $minDuration")
}

fun logServer(log: String) {
    println("[Server] $log")
}

fun logClient(log: String) {
    println("[Client] $log")
}
