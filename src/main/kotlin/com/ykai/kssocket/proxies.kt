package com.ykai.kssocket

import java.net.InetSocketAddress

abstract class Proxy

object NoProxy : Proxy()

class Socks4Proxy(val addr: InetSocketAddress, val remoteDns: Boolean) : Proxy() {
    constructor(hostname: String, port: Int, remoteDns: Boolean)
            : this(InetSocketAddress(hostname, port), remoteDns)
}

class Socks5Proxy(
    val addr: InetSocketAddress,
    val username: String? = null,
    val password: String? = null,
    val remoteDns: Boolean
) : Proxy() {
    constructor(hostname: String, port: Int, username: String? = null, password: String? = null, remoteDns: Boolean)
            : this(InetSocketAddress(hostname, port), username, password, remoteDns)

    init {
        if (username != null && username.length > 256) {
            throw Socks5ProxyException("username is too long")
        }
        if (password != null && password.length > 256) {
            throw Socks5ProxyException("password is too long")
        }
    }
}

class Socks5ProxyException(msg: String) : Exception(msg)
