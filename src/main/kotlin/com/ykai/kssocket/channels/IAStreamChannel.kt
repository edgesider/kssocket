package com.ykai.kssocket.channels

import java.nio.ByteBuffer

interface IAStreamChannel {
    suspend fun read(buffer: ByteBuffer): Int
    suspend fun write(buffer: ByteBuffer): Int
    suspend fun readAll(buffer: ByteBuffer): Boolean
    suspend fun writeAll(buffer: ByteBuffer)
}