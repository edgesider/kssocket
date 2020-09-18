package com.ykai.kssocket.dns

import com.ykai.kssocket.bitToInt
import com.ykai.kssocket.keepPosition
import java.nio.ByteBuffer

class DNSPacket(
    var sessionId: Int,
    val flags: Int,
    var queries: Array<QueryItem>,
    var answers: Array<AnswerItem>,
    var authNS: Array<AnswerItem>,
    var additional: Array<AnswerItem>
) {
    companion object {
        const val DefaultRequestFlags = 0x100
        const val DefaultResponseFlags = 0x8180

        fun fromByteBuffer(buf: ByteBuffer): DNSPacket {
            val startPos = buf.position()
            val sessionId = buf.short.bitToInt()
            val flags = buf.short.bitToInt()
            val nQuery = buf.short.bitToInt()
            val nAnswer = buf.short.bitToInt()
            val nAuth = buf.short.bitToInt()
            val nAdditional = buf.short.bitToInt()
            val queries = Array(nQuery) {
                QueryItem.fromByteBuffer(buf, startPos)
            }
            val answers = Array(nAnswer) {
                AnswerItem.fromByteBuffer(buf, startPos)
            }
            val authNS = Array(nAuth) {
                AnswerItem.fromByteBuffer(buf, startPos)
            }
            val additional = Array(nAdditional) {
                AnswerItem.fromByteBuffer(buf, startPos)
            }
            return DNSPacket(sessionId, flags, queries, answers, authNS, additional)
        }

        /**
         * 获取[buf]当前位置处的域名。[dnsStartPosition]为DNS报文起始位置。
         */
        private fun getName(buf: ByteBuffer, dnsStartPosition: Int): String {
            val nameBuilder = StringBuilder()
            while (true) {
                val len = buf.get().bitToInt()
                if (len == 0)
                    break
                if (len ushr 6 == 0b11) {
                    // 遇到域名指针
                    val pointer = ((len and 0b00111111) shl 8) + buf.get().bitToInt()
                    val name = buf.keepPosition {
                        buf.position(pointer + dnsStartPosition)
                        getName(buf, dnsStartPosition)
                    }
                    nameBuilder.append(name)
                    // 域名指针为最后一项
                    break
                }

                val arr = ByteArray(len).also { buf.get(it) }
                val label = String(arr, Charsets.US_ASCII)
                nameBuilder.append(label)
                nameBuilder.append('.')
            }
            return nameBuilder.toString().trimEnd('.')
        }

        private fun putName(buf: ByteBuffer, name: String) {
            val labels = name.split('.')
            for (label in labels) {
                val bytes = Charsets.US_ASCII.encode(label)
                buf.put(bytes.limit().toByte())
                buf.put(bytes)
            }
            buf.put(0)
        }
    }

    fun toByteBuffer(buf: ByteBuffer): ByteBuffer {
        buf.putShort(sessionId.toShort())
        buf.putShort(flags.toShort())
        buf.putShort(queries.size.toShort())
        buf.putShort(answers.size.toShort())
        buf.putShort(authNS.size.toShort())
        buf.putShort(additional.size.toShort())
        for (query in queries)
            query.toByteBuffer(buf)
        for (ans in answers)
            ans.toByteBuffer(buf)
        for (auth in authNS)
            auth.toByteBuffer(buf)
        for (add in additional)
            add.toByteBuffer(buf)
        return buf
    }

    class QueryItem(var name: String, var type: Int, var klass: Int) {
        companion object {
            fun fromByteBuffer(buf: ByteBuffer, dnsStartPosition: Int): QueryItem {
                val name = getName(buf, dnsStartPosition)
                val type = buf.short.bitToInt()
                val klass = buf.short.bitToInt()
                return QueryItem(name, type, klass)
            }
        }

        fun toByteBuffer(buf: ByteBuffer) {
            putName(buf, name)
            buf.putShort(type.toShort())
            buf.putShort(klass.toShort())
        }
    }

    class AnswerItem(
        var name: String, var type: Int, var klass: Int,
        var ttl: Int, var data: ByteArray
    ) {
        companion object {
            /**
             * @param dnsStartPosition DNS报文中域名有可能是个指向之前域名的指针，所以需要知道DNS报文的起始位置
             */
            fun fromByteBuffer(buf: ByteBuffer, dnsStartPosition: Int): AnswerItem {
                val name = getName(buf, dnsStartPosition)
                val type = buf.short.bitToInt()
                val klass = buf.short.bitToInt()
                val ttl = buf.int
                val dataLength = buf.short.bitToInt()
                val data = ByteArray(dataLength).also { buf.get(it) }
                return AnswerItem(name, type, klass, ttl, data)
            }
        }

        val dataLength get() = data.size

        fun toByteBuffer(buf: ByteBuffer) {
            putName(buf, name)
            buf.putShort(type.toShort())
            buf.putShort(klass.toShort())
            buf.putInt(ttl)
            buf.putShort(dataLength.toShort())
            buf.put(data)
        }
    }
}