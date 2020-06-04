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

- Socks代理

```kotlin
fun main() = runBlocking {
    val sock = ASocketChannel.open(
        InetSocketAddress("google.com", 80),
        proxy = Socks4Proxy("127.0.0.1", 1080, remoteDns = true)
    )
    sock.writeAll(
        ByteBuffer.wrap(
            ("GET / HTTP/1.1\r\nHost: google.com\r\n" +
                    "User-Agent: curl/7.70.0\r\nAccept: */*\r\n\r\n")
                .toByteArray()
        )
    )
    val buf = ByteBuffer.allocate(1000)
    sock.read(buf)
    buf.flip()
    println(String(ByteArray(buf.limit()).also { buf.get(it) }))
    Unit
}
```

## TODO

- [x] Socks/Socks5支持
- [ ] DNS异步查询
- [ ] IPv6支持
