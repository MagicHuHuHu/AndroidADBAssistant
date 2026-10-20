package com.adbside.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 验证 sync 协议边界、二进制完整性和危险文件名。 */
class AdbFilesTest {
    /** 构造小端协议整数。 */
    private fun int(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    /** 构造带长度的协议响应。 */
    private fun packet(id: String, bytes: ByteArray) = id.toByteArray() + int(bytes.size) + bytes

    /** 验证中文与空格文件名不依赖 shell 解析。 */
    @Test fun listPreservesUnicodeNames() {
        val name = "截图 01.png".toByteArray()
        val response = "DENT".toByteArray() + int(0x81A4) + int(42) + int(100) + int(name.size) + name + "DONE".toByteArray() + ByteArray(16)
        val output = ByteArrayOutputStream()
        val files = AdbFiles(ByteArrayInputStream(response), output).list("/sdcard")
        assertEquals("截图 01.png", files.single().name)
        assertEquals(42L, files.single().size)
        assertFalse(files.single().directory)
        assertEquals("LIST", output.toByteArray().take(4).toByteArray().toString(Charsets.US_ASCII))
    }

    /** 验证下载不会按文本转换二进制内容。 */
    @Test fun downloadPreservesBinary() {
        val data = ByteArray(65536) { (it % 256).toByte() }
        val response = packet("DATA", data) + "DONE".toByteArray() + int(0)
        val destination = ByteArrayOutputStream()
        var progress = 0L
        AdbFiles(ByteArrayInputStream(response), ByteArrayOutputStream()).receive("/sdcard/a", destination) { progress = it }
        assertArrayEquals(data, destination.toByteArray())
        assertEquals(data.size.toLong(), progress)
    }

    /** 验证上传分块大小，覆盖超过单块的大文件。 */
    @Test fun uploadUsesBoundedChunks() {
        val data = ByteArray(150000) { it.toByte() }
        val output = ByteArrayOutputStream()
        var progress = 0L
        AdbFiles(ByteArrayInputStream("OKAY".toByteArray() + int(0)), output).send("/sdcard/a", data.inputStream()) { progress = it }
        val wire = ByteBuffer.wrap(output.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val received = ByteArrayOutputStream()
        var chunks = 0
        while (wire.hasRemaining()) {
            val id = ByteArray(4).also { wire.get(it) }.toString(Charsets.US_ASCII)
            val size = wire.int
            if (id == "DONE") break
            val payload = ByteArray(size).also { wire.get(it) }
            if (id == "DATA") { assertTrue(size <= 65536); received.write(payload); chunks++ }
        }
        assertEquals(3, chunks)
        assertEquals(150000L, progress)
        assertArrayEquals(data, received.toByteArray())
    }

    /** 设备拒绝文件读取时必须报告失败。 */
    @Test(expected = IllegalStateException::class) fun reportsRemoteFailure() {
        AdbFiles(ByteArrayInputStream(packet("FAIL", "Permission denied".toByteArray())), ByteArrayOutputStream())
            .receive("/data/private", ByteArrayOutputStream()) {}
    }

    /** 截断的数据不可被当作完整下载。 */
    @Test(expected = EOFException::class) fun rejectsTruncatedData() {
        AdbFiles(ByteArrayInputStream("DATA".toByteArray() + int(100) + byteArrayOf(1)), ByteArrayOutputStream())
            .receive("/sdcard/a", ByteArrayOutputStream()) {}
    }

    /** 过大的协议长度不可触发无界内存分配。 */
    @Test(expected = IllegalArgumentException::class) fun rejectsOversizedPacket() {
        AdbFiles(ByteArrayInputStream("DATA".toByteArray() + int(Int.MAX_VALUE)), ByteArrayOutputStream())
            .receive("/sdcard/a", ByteArrayOutputStream()) {}
    }

    /** 禁止路径穿越并保留合法特殊字符。 */
    @Test fun validatesNamesAndQuotesShell() {
        listOf("..", "a/b", "a\\b", "a\n", "").forEach { value -> assertTrue(runCatching { safeFileName(value) }.isFailure) }
        assertEquals("截图 1.png", safeFileName("截图 1.png"))
        assertEquals("'a'\\''b'", shellQuote("a'b"))
    }
}
