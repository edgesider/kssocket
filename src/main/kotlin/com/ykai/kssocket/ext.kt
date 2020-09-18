package com.ykai.kssocket

import com.ykai.kssocket.channels.ProxyServerException
import com.ykai.kssocket.emitter.IOEventEmitter
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

fun IOEventEmitter.runInNewThread() =
    thread(start = true, isDaemon = true) {
        run()
    }

inline fun <T> ByteBuffer.keepPosition(act: (buf: ByteBuffer) -> T): T {
    val pos = this.position()
    return act(this).also {
        this.position(pos)
    }
}

/* 按位操作函数 */
fun Short.bitToInt() = this.toInt() and 0xFFFF
fun Byte.bitToInt() = this.toInt() and 0xFF

/**
 * 将ByteBuffer的内容按[od -Ax -tx1]命令的格式输出到文件中，
 * 以便直接由wireshark打开。
 */
fun ByteBuffer.hexDump(
    file: FileOutputStream,
    bytesPerLine: Int = 16
) {
    this.keepPosition { buf ->
        val writer = file.bufferedWriter(Charsets.US_ASCII)
        for (i in buf.position() until buf.limit()) {
            if (i % bytesPerLine == 0) {
                if (i != 0) {
                    // 第一行开始不用换行
                    writer.append('\n')
                }
                // 加入行头
                writer.append(intToHex(i), " ")
            }
            writer.append(byteToHex(buf.get()), " ")
        }
        writer.append('\n')
        writer.flush()
    }
}

const val HEX_STRING = "0123456789ABCDEF"
fun byteToHex(byte: Byte): String =
    "${HEX_STRING[(byte.toInt() ushr 4) and 0xF]}${HEX_STRING[byte.toInt() and 0xF]}"

fun intToHex(num: Int): String {
    if (num > 0xFFFFFF) {
        // 只显示六位十六进制数
        throw Exception("number out of range")
    }
    var s = ""
    for (i in 0 until 4) {
        s += byteToHex((num ushr (8 * (3 - i))).toByte())
    }
    return s
}

fun ByteBuffer.clearAndLimit(limit: Int) {
    clear()
    limit(limit)
}

suspend fun processProxyException(action: suspend () -> Any) {
    try {
        action()
    } catch (e: ProxyServerException) {
        throw e
    } catch (e: Exception) {
        throw ProxyServerException("an error occurred while request socks5 server", e)
    }
}
