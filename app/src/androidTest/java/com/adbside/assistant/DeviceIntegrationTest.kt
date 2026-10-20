package com.adbside.assistant

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.UUID
import android.app.Application
import android.content.ContentValues
import android.provider.MediaStore
import android.net.Uri
import android.provider.DocumentsContract

/** 仅供测试：通过 USB 中继验证真机上的 ADB 文件协议。 */
@RunWith(AndroidJUnit4::class)
class DeviceIntegrationTest {
    /** 等待异步界面状态，超时后输出具体断言位置。 */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "等待应用状态超时" }
            Thread.sleep(30)
        }
    }

    /** 验证界面使用的连接、命令、应用列表和文件传输业务。 */
    @Test fun testModelOperations() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = AssistantModel(app)
        val resolver = app.contentResolver
        val name = "ADB助手测试-${UUID.randomUUID()}.bin"
        val bytes = ByteArray(180123) { (it % 251).toByte() }
        val source = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "source-$name")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        })!!
        val destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "download-$name")
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
        })!!
        try {
            resolver.openOutputStream(source)!!.use { it.write(bytes) }
            model.connect("127.0.0.1", "18777")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.message, model.state.value.connected)
            model.command("echo ADB助手中文测试")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.terminal.contains("ADB助手中文测试"))
            assertFalse(model.state.value.terminalRunning)
            model.loadApps(false)
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.message, model.state.value.apps.any { it.packageName == "com.adbside.assistant" })
            assertEquals("0.1.0", model.state.value.apps.first { it.packageName == "com.adbside.assistant" }.versionName)
            model.upload(source, "/sdcard/Download", name)
            waitFor { model.state.value.transfers.first().status in listOf("已完成", "失败") }
            assertEquals(model.state.value.transfers.first().error, "已完成", model.state.value.transfers.first().status)
            model.download(RemoteFile(name, bytes.size.toLong(), 0, false), "/sdcard/Download/$name", destination)
            waitFor { model.state.value.transfers.first().name == name && !model.state.value.transfers.first().upload && model.state.value.transfers.first().status in listOf("已完成", "失败") }
            assertEquals(model.state.value.transfers.first().error, "已完成", model.state.value.transfers.first().status)
            val actual = resolver.openInputStream(destination)!!.use { it.readBytes() }
            assertTrue(bytes.contentEquals(actual))
            model.screenshot()
            waitFor { !model.state.value.busy }
            assertNotNull(model.state.value.message, model.state.value.screenshotPreview)
            model.dismissScreenshot()
            assertNull(model.state.value.screenshotPreview)
            model.screenshot()
            waitFor { !model.state.value.busy }
            model.saveScreenshot(destination)
            waitFor { !model.state.value.busy }
            assertNull(model.state.value.screenshotPreview)
            val png = resolver.openInputStream(destination)!!.use { it.readBytes() }
            assertTrue(png.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
            model.deviceAction("返回键")
            waitFor { !model.state.value.busy }
            assertEquals("", model.state.value.message)
            model.deviceAction("主页键")
            waitFor { !model.state.value.busy }
            assertEquals("", model.state.value.message)
        } finally {
            if (model.state.value.connected) {
                model.command("rm -f ${shellQuote("/sdcard/Download/$name")}")
                waitFor { !model.state.value.busy }
                model.disconnect()
                waitFor { !model.state.value.busy }
            }
            resolver.delete(source, null, null)
            resolver.delete(destination, null, null)
        }
    }

    /** 验证命令输出追加、停止后恢复，以及清空历史显示。 */
    @Test fun testTerminalTranscript() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = AssistantModel(app)
        try {
            model.connect("127.0.0.1", "18777")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.message, model.state.value.connected)
            model.command("echo terminal_first")
            waitFor { !model.state.value.busy }
            model.command("echo terminal_second")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.terminal.contains("$ echo terminal_first\nterminal_first"))
            assertTrue(model.state.value.terminal.contains("$ echo terminal_second\nterminal_second"))
            model.command("echo terminal_started; sleep 20")
            waitFor { model.state.value.terminal.contains("\nterminal_started") }
            assertTrue(model.state.value.terminalRunning)
            model.stopCommand()
            waitFor { !model.state.value.busy }
            assertFalse(model.state.value.terminalRunning)
            assertTrue(model.state.value.terminal.contains("命令已停止"))
            model.command("echo terminal_after_stop")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.terminal.contains("terminal_first"))
            assertTrue(model.state.value.terminal.contains("\nterminal_after_stop"))
            assertEquals("echo terminal_after_stop", model.state.value.history.first())
            model.clearTerminal()
            assertEquals("", model.state.value.terminal)
        } finally {
            model.disconnect()
            waitFor { !model.state.value.busy }
        }
    }

    /** 验证目录保留、相对路径、中文空格目录、失败回退和重新连接重置。 */
    @Test fun testTerminalDirectory() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = AssistantModel(app)
        val folder = "adbside-dir-${UUID.randomUUID()}"
        val remote = "/sdcard/Download/$folder"
        try {
            model.connect("127.0.0.1", "18777")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.message, model.state.value.connected)
            val initial = model.state.value.terminalPath
            model.command("cd /sdcard")
            waitFor { !model.state.value.busy }
            assertEquals(model.state.value.terminal, "/sdcard", model.state.value.terminalPath)
            model.command("pwd -L; ls -d Download")
            waitFor { !model.state.value.busy }
            assertTrue(model.state.value.terminal.contains("/sdcard $ pwd -L; ls -d Download\n/sdcard\nDownload"))
            model.command("mkdir -p ${shellQuote("$remote/中文 目录")}; cd ${shellQuote("Download/$folder/中文 目录")}")
            waitFor { !model.state.value.busy }
            assertEquals("$remote/中文 目录", model.state.value.terminalPath)
            model.command("cd ..")
            waitFor { !model.state.value.busy }
            assertEquals(remote, model.state.value.terminalPath)
            model.command("cd ./does-not-exist-${UUID.randomUUID()}")
            waitFor { !model.state.value.busy }
            assertEquals(remote, model.state.value.terminalPath)
            model.command("cd /sdcard && pwd -L")
            waitFor { !model.state.value.busy }
            assertEquals("/sdcard", model.state.value.terminalPath)
            model.clearTerminal()
            assertEquals("/sdcard", model.state.value.terminalPath)
            model.disconnect()
            waitFor { !model.state.value.busy }
            assertEquals("/", model.state.value.terminalPath)
            model.connect("127.0.0.1", "18777")
            waitFor { !model.state.value.busy }
            assertEquals(initial, model.state.value.terminalPath)
        } finally {
            if (model.state.value.connected) {
                model.command("rm -rf -- ${shellQuote(remote)}")
                waitFor { !model.state.value.busy }
            }
            model.disconnect()
            waitFor { !model.state.value.busy }
        }
    }

    /** 读取测试目录子项，使用真实系统文档提供器验证目录结构。 */
    private fun children(parent: Uri): Map<String, Uri> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val result = mutableMapOf<String, Uri>()
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        resolver.query(query, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use {
            while (it.moveToNext()) result[it.getString(0)] = DocumentsContract.buildDocumentUriUsingTree(parent, it.getString(1))
        }
        return result
    }

    /** 无权限目录和循环符号链接应记录失败，其他文件仍可下载。 */
    @Test fun testFolderPartialFailure() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = AssistantModel(app)
        val tree = Uri.parse(model.state.value.downloadTree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "部分失败测试-${UUID.randomUUID()}"
        val remote = "/data/local/tmp/$name"
        try {
            model.connect("127.0.0.1", "18777")
            waitFor { !model.state.value.busy }
            model.command("mkdir -p ${shellQuote("$remote/拒绝目录")}; chmod 000 ${shellQuote("$remote/拒绝目录")}; printf 'ok' > ${shellQuote("$remote/可读.txt")}; ln -s . ${shellQuote("$remote/循环链接")}")
            waitFor { !model.state.value.busy }
            model.downloadToTree(listOf(RemoteFile(name, 0, 0, true)), "/data/local/tmp")
            waitFor { model.state.value.transfers.isNotEmpty() && model.state.value.transfers.first().status in listOf("已完成", "部分失败", "失败") }
            val task = model.state.value.transfers.first()
            assertEquals(task.error + task.failures.joinToString(), "部分失败", task.status)
            assertTrue(task.failures.joinToString(), task.failures.any { it.contains("循环链接") })
            assertTrue(task.failures.joinToString(), task.failures.any { it.contains("拒绝目录") })
            val root = children(parent)[name]!!
            assertTrue(children(root).containsKey("可读.txt"))
            assertFalse(children(root).containsKey("循环链接"))
        } finally {
            if (model.state.value.connected) {
                model.command("chmod 700 ${shellQuote("$remote/拒绝目录")}; rm -rf -- ${shellQuote(remote)}")
                waitFor { !model.state.value.busy }
                model.disconnect()
                waitFor { !model.state.value.busy }
            }
            children(parent)[name]?.let { DocumentsContract.deleteDocument(app.contentResolver, it) }
        }
    }

    /** 验证嵌套、空目录、同名副本及取消下载不会破坏原目录。 */
    @Test fun testFolderDownload() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val model = AssistantModel(app)
        assertTrue("请先在文件页授权专用测试下载目录", model.state.value.downloadTree.isNotBlank())
        val tree = Uri.parse(model.state.value.downloadTree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val name = "文件夹测试-${UUID.randomUUID()}"
        val remote = "/sdcard/Download/$name"
        try {
            model.connect("127.0.0.1", "18777")
            waitFor { !model.state.value.busy }
            model.command("mkdir -p ${shellQuote("$remote/嵌套/空目录")}; printf 'hello-folder' > ${shellQuote("$remote/嵌套/中文 文件.txt")}; : > ${shellQuote("$remote/零字节.bin")}")
            waitFor { !model.state.value.busy }
            val file = RemoteFile(name, 0, 0, true)
            model.downloadToTree(listOf(file), "/sdcard/Download")
            waitFor { model.state.value.transfers.isNotEmpty() && model.state.value.transfers.first().status in listOf("已完成", "部分失败", "失败") }
            val task = model.state.value.transfers.first()
            assertEquals(task.error + task.failures.joinToString(), "已完成", task.status)
            assertEquals(2, task.completedFiles)
            assertEquals(2, task.totalFiles)
            val root = children(parent)[name]!!
            val nested = children(root)["嵌套"]!!
            assertTrue(children(nested).containsKey("空目录"))
            val content = app.contentResolver.openInputStream(children(nested)["中文 文件.txt"]!!)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            assertEquals("hello-folder", content)
            assertEquals(0, app.contentResolver.openInputStream(children(root)["零字节.bin"]!!)!!.use { it.readBytes().size })
            model.downloadToTree(listOf(file), "/sdcard/Download")
            waitFor { model.state.value.transfers.size == 2 && model.state.value.transfers.first().status in listOf("已完成", "部分失败", "失败") }
            assertEquals("已完成", model.state.value.transfers.first().status)
            assertTrue(children(parent).containsKey("$name (1)"))
            model.downloadToTree(listOf(file), "/sdcard/Download")
            waitFor { model.state.value.transfers.size == 3 }
            val id = model.state.value.transfers.first().id
            model.cancelTransfer(id)
            Thread.sleep(300)
            assertEquals("已取消", model.state.value.transfers.first().status)
            assertTrue(children(parent).containsKey(name))
        } finally {
            if (model.state.value.connected) {
                model.command("rm -rf -- ${shellQuote(remote)}")
                waitFor { !model.state.value.busy }
                model.disconnect()
                waitFor { !model.state.value.busy }
            }
            for ((childName, uri) in children(parent)) {
                if (childName == name || childName.startsWith("$name (")) DocumentsContract.deleteDocument(app.contentResolver, uri)
            }
        }
    }

    /** 上传和下载含中文名称的大文件并校验每个字节。 */
    @Test fun testSyncRoundTrip() {
        val manager = AdbIdentity(InstrumentationRegistry.getInstrumentation().targetContext)
        val path = "/sdcard/Download/ADB助手测试-${UUID.randomUUID()}.bin"
        try {
            assertTrue(manager.connect("127.0.0.1", 18777))
            val bytes = ByteArray(2 * 1024 * 1024 + 123) { ((it * 31 + 7) % 256).toByte() }
            var sent = 0L
            manager.openStream("sync:").use { stream ->
                AdbFiles(stream.openInputStream(), stream.openOutputStream()).send(path, bytes.inputStream()) { sent = it }
            }
            assertEquals(bytes.size.toLong(), sent)
            val list = manager.openStream("sync:").use { stream -> AdbFiles(stream.openInputStream(), stream.openOutputStream()).list("/sdcard/Download") }
            assertTrue(list.any { it.name == path.substringAfterLast('/') && it.size == bytes.size.toLong() })
            val downloaded = ByteArrayOutputStream()
            var received = 0L
            manager.openStream("sync:").use { stream ->
                AdbFiles(stream.openInputStream(), stream.openOutputStream()).receive(path, downloaded) { received = it }
            }
            assertEquals(bytes.size.toLong(), received)
            assertTrue(bytes.contentEquals(downloaded.toByteArray()))
        } finally {
            if (manager.isConnected) runCatching {
                manager.openStream("shell:rm -f ${shellQuote(path)}; echo done").use { stream ->
                    val input = stream.openInputStream()
                    val buffer = ByteArray(1024)
                    runCatching { while (input.read(buffer) > 0) { /* 等待清理命令完成。 */ } }
                }
            }
            manager.close()
        }
    }
}
