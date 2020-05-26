# KSSocket

可直接用于Kotlin协程的socket实现。

## 例子

- TCP客户端
```kotlin
fun main() = runBlocking {
    ASocketChannel.open().let { sock ->
        sock.connect(InetSocketAddress("localhost", 8000))
        sock.write(ByteBuffer.wrap("kssocket".toByteArray()))
        sock.close()
    }
}
```

## TDOO

- [ ] Socks/Socks5支持