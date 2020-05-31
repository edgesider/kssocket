import com.ykai.kssocket.DefaultIOEventEmitter
import com.ykai.kssocket.IOEventEmitter
import com.ykai.kssocket.InterestOp
import kotlinx.coroutines.*
import org.junit.AfterClass
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.AsynchronousServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread

@Suppress("BlockingMethodInNonBlockingContext")
class IOEventEmitterImplTest {
    companion object {
        const val serverPort = 30000

        @JvmStatic
        lateinit var server: AsynchronousServerSocketChannel

        @JvmStatic
        var emitter: IOEventEmitter = DefaultIOEventEmitter

        @BeforeClass
        @JvmStatic
        fun runServer() {
            val sync = CompletableFuture<Unit>()
            server = AsynchronousServerSocketChannel.open()
            server.bind(InetSocketAddress(serverPort))
            sync.complete(Unit)

            thread(isDaemon = true) {
                while (true) {
                    val peer = server.accept().get()
                    peer.write(ByteBuffer.wrap(byteArrayOf('@'.toByte())))
                    println("send byte to $peer")
                }
            }
            println("server running")
            sync.get()
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            server.close()
            println("server closed")
        }

        @BeforeClass
        @JvmStatic
        fun runEmitter() {
//        emitter = IOEventEmitterImpl().also {
//            it.runInNewThread()
//        }
        }

        @AfterClass
        @JvmStatic
        fun stopEmitter() {
//        emitter.close()
        }
    }

    @Test
    fun waitEvent() = runBlocking {
        val sock = SocketChannel.open(InetSocketAddress(serverPort))
        sock.configureBlocking(false)
        emitter.register(sock)

        // 测试水平触发
        var returned = false
        launch {
            for (i in 0 until 5) {
                // 应该返回
                emitter.waitEvent(sock, InterestOp.Read)
                delay(50)
            }
            returned = true
        }.let {
            delay(1000)
            it.cancelAndJoin()
        }
        Assert.assertTrue(returned)

        returned = false
        sock.read(ByteBuffer.allocate(1))
        launch {
            // 应该阻塞
            emitter.waitEvent(sock, InterestOp.Read)
            returned = true
        }.let {
            delay(1000)
            it.cancelAndJoin()
        }
        Assert.assertFalse(returned)

        Unit
    }

    @Test
    fun waitEventTimeout() = runBlocking {
        val sock = SocketChannel.open(InetSocketAddress(serverPort))
        sock.configureBlocking(false)
        emitter.register(sock)
        Assert.assertEquals(1, sock.read(ByteBuffer.allocate(10)))

        var ex: Exception = Exception()
        launch {
            try {
                emitter.waitEvent(sock, InterestOp.Read, 1000)
            } catch (e: TimeoutCancellationException) {
                ex = e
            }
        }.let {
            delay(2000)
            it.cancelAndJoin()
        }
        Assert.assertTrue(ex is TimeoutCancellationException)
    }

    @Test
    fun unregister(): Unit = runBlocking {
        val sock = SocketChannel.open(InetSocketAddress(serverPort))
        sock.configureBlocking(false)
        emitter.register(sock)
        launch {
            try {
                emitter.waitEvent(sock, InterestOp.Read)
            } catch (e: Exception) {
                emitter.unregister(sock)
            }
        }.also {
            delay(200)
            it.cancel()
        }
        Unit
    }
}
