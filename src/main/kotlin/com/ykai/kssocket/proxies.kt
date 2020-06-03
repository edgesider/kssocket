package com.ykai.kssocket

import java.net.InetSocketAddress

abstract class Proxy

object NoProxy : Proxy()

class Socks4Proxy(val addr: InetSocketAddress) : Proxy()

class Socks5Proxy(val addr: InetSocketAddress, val remoteDns: Boolean) : Proxy()
