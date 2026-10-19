package com.adbside.assistant

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RemoteFile(val name: String, val size: Long, val modified: Long, val directory: Boolean, val symbolicLink: Boolean = false)

/** 封装 shell 参数，防止文件名被解释为命令。 */
fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

/** 校验单个文件名，禁止跨目录和控制字符。 */
fun safeFileName(value: String): String {
    require(value.isNotBlank() && value != "." && value != ".." && value.none { it == '/' || it == '\\' || it.code < 32 }) { "文件名不合法" }
    return value
}

/** 实现 ADB sync v1，使用分块传输避免大文件占满内存。 */
class AdbFiles(private val input: InputStream, private val output: OutputStream) {
    /** 精确读取指定字节，遇到异常断流立即报错。 */
    private fun readExact(size: Int): ByteArray {
        require(size in 0..1_048_576) { "ADB 响应长度异常" }
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(bytes, offset, size - offset)
            if (n < 0) throw EOFException("ADB 文件传输已中断")
            offset += n
        }
        return bytes
    }

    /** 读取小端协议整数。 */
    private fun number(): Int = ByteBuffer.wrap(readExact(4)).order(ByteOrder.LITTLE_ENDIAN).int

    /** 写入同步请求头和可选数据。 */
    private fun packet(id: String, bytes: ByteArray = byteArrayOf(), value: Int = bytes.size) {
        output.write(id.toByteArray(Charsets.US_ASCII))
        output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        output.write(bytes)
        output.flush()
    }

    /** 列出设备目录，保留包含空格及中文的文件名。 */
    fun list(path: String): List<RemoteFile> {
        packet("LIST", path.toByteArray())
        val files = mutableListOf<RemoteFile>()
        while (true) {
            when (val id = String(readExact(4), Charsets.US_ASCII)) {
                "DENT" -> {
                    val mode = number()
                    val size = Integer.toUnsignedLong(number())
                    val time = Integer.toUnsignedLong(number())
                    val name = String(readExact(number()), Charsets.UTF_8)
                    if (name != "." && name != "..") files += RemoteFile(name, size, time, mode and 0xF000 == 0x4000, mode and 0xF000 == 0xA000)
                }
                "DONE" -> { readExact(16); return files.sortedWith(compareByDescending<RemoteFile> { it.directory }.thenBy { it.name.lowercase() }) }
                "FAIL" -> error(String(readExact(number()), Charsets.UTF_8))
                else -> error("无法识别的目录响应：$id")
            }
        }
    }

    /** 分块上传文件并检查设备端确认结果。 */
    fun send(path: String, source: InputStream, progress: (Long) -> Unit) {
        packet("SEND", "$path,33188".toByteArray())
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            packet("DATA", buffer.copyOf(n))
            total += n
            progress(total)
        }
        packet("DONE", value = (System.currentTimeMillis() / 1000).toInt())
        val id = String(readExact(4), Charsets.US_ASCII)
        val length = number()
        if (id == "FAIL") error(String(readExact(length), Charsets.UTF_8))
        check(id == "OKAY") { "上传确认失败：$id" }
    }

    /** 分块下载文件，异常时由调用方处理未完成文件。 */
    fun receive(path: String, destination: OutputStream, progress: (Long) -> Unit) {
        packet("RECV", path.toByteArray())
        var total = 0L
        while (true) {
            val id = String(readExact(4), Charsets.US_ASCII)
            val length = number()
            when (id) {
                "DATA" -> { destination.write(readExact(length)); total += length; progress(total) }
                "DONE" -> { destination.flush(); return }
                "FAIL" -> error(String(readExact(length), Charsets.UTF_8))
                else -> error("无法识别的下载响应：$id")
            }
        }
    }
}
