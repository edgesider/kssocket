import com.ykai.AServerSocketChannel
import com.ykai.ASocketChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.ByteBuffer

fun main(): Unit = runBlocking {
    launch {
        val sock = ASocketChannel.open(InetSocketAddress(8000))
        val buffer = ByteBuffer.allocate(10)
        sock.read(buffer)
        println(buffer)
        sock.write(ByteBuffer.wrap("123123".toByteArray()))
        println("done")
    }

    launch {
        val sock = AServerSocketChannel.open()
        sock.bind(InetSocketAddress(8001))
        while (true) {
            sock.accept().let {
                it.write(ByteBuffer.wrap("123123".toByteArray()))
                it.close()
            }
        }
    }

    Unit
}
