package com.ykai

import java.nio.channels.SelectionKey

enum class InterestOp {
    Read, Write, Accept, Connect;

    fun toInt() = when (this) {
        Read -> SelectionKey.OP_READ
        Write -> SelectionKey.OP_WRITE
        Accept -> SelectionKey.OP_ACCEPT
        Connect -> SelectionKey.OP_CONNECT
    }
}
