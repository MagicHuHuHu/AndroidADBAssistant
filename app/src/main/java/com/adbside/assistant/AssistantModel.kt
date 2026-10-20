package com.adbside.assistant

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AppEntry(val packageName: String, val version: String = "", val versionName: String = "读取中")
data class TransferEntry(val id: String, val name: String, val upload: Boolean, val total: Long,
    val done: Long = 0, val status: String = "等待中", val error: String = "", val started: Long = System.currentTimeMillis(),
    val currentFile: String = "", val totalFiles: Int = 0, val completedFiles: Int = 0, val failures: List<String> = emptyList())
private data class FolderDownloadItem(val relative: String, val remote: String, val file: RemoteFile)
data class AssistantState(
    val connected: Boolean = false, val device: String = "", val address: String = "",
    val busy: Boolean = false, val message: String = "", val apps: List<AppEntry> = emptyList(),
    val path: String = "/sdcard", val files: List<RemoteFile> = emptyList(),
    val terminal: String = "连接设备后即可执行 shell 命令。\n", val history: List<String> = emptyList(), val terminalRunning: Boolean = false,
    val terminalPath: String = "/",
    val transfers: List<TransferEntry> = emptyList(), val downloadTree: String = "", val screenshotPreview: Bitmap? = null
)

/** 统一管理连接与界面状态，所有网络和文件操作均在后台执行。 */
class AssistantModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("assistant", 0)
    private val mutable = MutableStateFlow(AssistantState(downloadTree = prefs.getString("downloadTree", "") ?: ""))
    val state = mutable.asStateFlow()
    val savedHost: String get() = prefs.getString("host", "") ?: ""
    val savedPort: String get() = prefs.getString("port", "") ?: ""
    private val manager by lazy { AdbIdentity(application) }
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val streams = java.util.concurrent.ConcurrentHashMap<String, AdbStream>()
    private val transferJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val retries = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
    private val operation = AtomicBoolean(false)
    @Volatile private var terminalStream: AdbStream? = null
    @Volatile private var terminalStopRequested = false
    private var screenshotBytes: ByteArray? = null
    private val resolver get() = getApplication<Application>().contentResolver

    /** 执行短操作并统一展示错误，防止重复提交。 */
    private fun runAction(action: suspend () -> Unit) {
        if (!operation.compareAndSet(false, true)) return
        mutable.update { it.copy(busy = true, message = "") }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() } }
            catch (e: Exception) {
                android.util.Log.e("AdbAssistant", "操作失败", e)
                val connected = runCatching { manager.isConnected }.getOrDefault(false)
                mutable.update { it.copy(message = e.message ?: "操作失败：${e.javaClass.simpleName}", connected = connected, terminalPath = if (connected) it.terminalPath else "/") }
            } finally { operation.set(false); mutable.update { it.copy(busy = false) } }
        }
    }

    /** 显示短消息并允许用户关闭。 */
    fun message(text: String) { mutable.update { it.copy(message = text) } }

    /** 校验端口，避免无效地址进入连接流程。 */
    private fun port(value: String): Int = (value.toIntOrNull()?.takeIf { it in 1..65535 }) ?: error("端口应为 1～65535")

    /** 使用六位配对码配对，配对成功后仍需连接调试端口。 */
    fun pair(host: String, pairingPort: String, code: String) = runAction {
        require(host.isNotBlank()) { "请输入设备 B 的 IP 地址" }
        require(code.matches(Regex("[0-9]{6}"))) { "请输入六位数字配对码" }
        check(manager.pair(host.trim(), port(pairingPort), code)) { "配对失败，请检查配对码和配对端口" }
        message("配对成功，请填写无线调试主页的连接端口，然后点击连接")
    }

    /** 连接设备并保存最近一次地址，不保存临时配对码。 */
    fun connect(host: String, connectionPort: String) = runAction {
        require(host.isNotBlank()) { "请输入设备 B 的 IP 地址" }
        val p = port(connectionPort)
        manager.disconnect()
        mutable.update { it.copy(connected = false, terminalPath = "/") }
        check(manager.connect(host.trim(), p)) { "连接失败，请检查连接端口及设备 B 的授权提示" }
        val name = shell("getprop ro.product.model").trim().ifEmpty { "Android 设备" }
        val terminalPath = shell("pwd -L").trim().ifEmpty { "/" }
        prefs.edit().putString("host", host.trim()).putString("port", connectionPort).apply()
        mutable.update { it.copy(connected = true, device = name, address = "${host.trim()}:$p", files = emptyList(), apps = emptyList(), terminalPath = terminalPath, message = "已连接 $name") }
    }

    /** 关闭连接并终止所有传输。 */
    fun disconnect() {
        streams.values.forEach { runCatching { it.close() } }
        terminalStream?.let { runCatching { it.close() } }
        runAction {
            manager.disconnect()
            mutable.update { it.copy(connected = false, device = "", files = emptyList(), apps = emptyList(), terminalPath = "/", message = "已断开连接") }
        }
    }

    /** 执行有超时和输出上限的 shell，避免无限命令占满资源。 */
    private fun shell(command: String, terminal: Boolean = false, terminalPrefix: String = ""): String {
        check(manager.isConnected) { "请先连接设备 B" }
        val completion = "ADB_DONE_${UUID.randomUUID().toString().replace("-", "")}:"
        val directoryMarker = "ADB_CWD_${UUID.randomUUID().toString().replace("-", "")}:"
        // 上游 OPEN 请求按字符数分配缓冲，先转为 ASCII，避免中文命令或路径导致溢出。
        // 在同一命令环境中读取执行后的目录，避免仅解析 cd 文本漏掉相对路径或组合命令。
        val setup = if (terminal) """
            cd ${shellQuote(mutable.value.terminalPath)} || { echo '当前目录不可用，已返回根目录'; cd / || exit; }
            trap 'terminal_status=${'$'}?; printf "\n$directoryMarker"; pwd -L | base64 | tr -d "\n"; printf "\n"; exit "${'$'}terminal_status"' EXIT
        """.trimIndent() else ""
        val script = "(\n$setup\n$command\n); printf '\\n$completion%s' \"\$?\""
        val encoded = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        manager.openStream("shell:echo $encoded | base64 -d | sh").use { stream ->
            if (terminal) terminalStream = stream
            val timeout = timer.schedule({ runCatching { stream.close() } }, 30, TimeUnit.SECONDS)
            try {
                if (terminal && terminalStopRequested) error("命令已停止")
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                stream.openInputStream().use { input ->
                    while (true) {
                        // 上游库在远端正常关闭时也可能抛 IOException，完成标记用于判断结果完整性。
                        val n = try { input.read(buffer) } catch (e: IOException) {
                            if (stream.isClosed) -1 else throw e
                        }
                        if (n < 0) break
                        check(bytes.size() + n <= 2 * 1024 * 1024) { "输出超过 2 MB，请缩小命令范围" }
                        bytes.write(buffer, 0, n)
                        if (terminal) mutable.update { it.copy(terminal = (terminalPrefix + bytes.toString("UTF-8").substringBefore(directoryMarker).substringBefore(completion)).takeLast(100_000)) }
                    }
                }
                if (timeout.isDone) error("命令超过 30 秒，已停止")
                val result = bytes.toString("UTF-8")
                val index = result.lastIndexOf(completion)
                check(index >= 0 && result.substring(index + completion.length).trim().toIntOrNull() != null) { "命令已停止或连接中断，输出可能不完整" }
                var output = result.substring(0, index)
                if (terminal) {
                    val directoryIndex = output.lastIndexOf(directoryMarker)
                    check(directoryIndex >= 0) { "未收到当前目录，输出可能不完整" }
                    val encodedPath = output.substring(directoryIndex + directoryMarker.length).trim()
                    val path = String(android.util.Base64.decode(encodedPath, android.util.Base64.DEFAULT), Charsets.UTF_8).removeSuffix("\n")
                    check(path.startsWith('/') && '\u0000' !in path) { "设备返回的目录不合法" }
                    mutable.update { it.copy(terminalPath = path) }
                    output = output.substring(0, directoryIndex)
                }
                return output.trimEnd('\r', '\n')
            } finally { timeout.cancel(false); if (terminal) terminalStream = null }
        }
    }

    /** 检查远端命令退出码，使安装与应用管理正确报告失败。 */
    private fun checked(command: String): String {
        val marker = "ADB_SIDE_${UUID.randomUUID().toString().replace("-", "")}:"
        val output = shell("( $command ); r=\$?; printf '\\n$marker%s' \"\$r\"")
        val index = output.lastIndexOf(marker)
        check(index >= 0) { "未收到命令完成状态" }
        val result = output.substring(0, index).trim()
        check(output.substring(index + marker.length).trim() == "0") { result.ifEmpty { "设备拒绝了该操作" } }
        return result
    }

    /** 执行终端命令并保存最近二十条命令。 */
    fun command(command: String) = runAction {
        require(command.isNotBlank()) { "请输入 shell 命令" }
        check(manager.isConnected) { "请先连接设备 B" }
        terminalStopRequested = false
        val previous = mutable.value.terminal.trimEnd()
        val prefix = (if (previous.isEmpty()) "" else "$previous\n\n") + "${mutable.value.terminalPath} $ $command\n"
        mutable.update { it.copy(history = (listOf(command) + it.history).distinct().take(20), terminal = prefix.takeLast(100_000), terminalRunning = true) }
        try {
            val output = shell(command, terminal = true, terminalPrefix = prefix)
            mutable.update { it.copy(terminal = (prefix + output + "\n").takeLast(100_000)) }
        } catch (e: Exception) {
            val reason = if (terminalStopRequested) "命令已停止" else e.message ?: "命令执行失败"
            val connected = manager.isConnected
            mutable.update { it.copy(terminal = (it.terminal.trimEnd() + "\n[$reason]\n").takeLast(100_000), connected = connected, terminalPath = if (connected) it.terminalPath else "/") }
        } finally { mutable.update { it.copy(terminalRunning = false) } }
    }

    /** 停止当前终端流，释放长期运行的命令。 */
    fun stopCommand() {
        terminalStopRequested = true
        terminalStream?.let { runCatching { it.close() } }
    }

    /** 清空终端显示。 */
    fun clearTerminal() { mutable.update { it.copy(terminal = "") } }

    /** 获取用户或系统应用列表及版本。 */
    fun loadApps(system: Boolean) = runAction {
        val output = checked("pm list packages ${if (system) "-s" else "-3"} --show-versioncode")
        val apps = output.lineSequence().filter { it.startsWith("package:") }.map {
            val parts = it.removePrefix("package:").trim().split(" ")
            AppEntry(parts.first(), parts.drop(1).joinToString(" ").replace("versionCode:", "版本号 "))
        }.sortedBy { it.packageName }.toList()
        mutable.update { it.copy(apps = apps) }
        // 一次获取所有版本名称，避免为每个应用单独建立命令流。
        val details = checked("dumpsys package packages | grep -E '^[[:space:]]*Package \\[|^[[:space:]]*versionName='")
        val versions = mutableMapOf<String, String>()
        var currentPackage = ""
        for (line in details.lineSequence()) {
            val text = line.trim()
            if (text.startsWith("Package [")) currentPackage = text.substringAfter('[').substringBefore(']')
            if (text.startsWith("versionName=") && currentPackage.isNotEmpty()) versions[currentPackage] = text.substringAfter("versionName=")
        }
        mutable.update { it.copy(apps = apps.map { app -> app.copy(versionName = versions[app.packageName]?.takeIf { name -> name != "null" && name.isNotBlank() } ?: "未提供") }) }
    }

    /** 执行经界面确认的应用操作。 */
    fun appAction(pkg: String, action: String) = runAction {
        require(pkg.matches(Regex("[a-zA-Z0-9_.]+"))) { "包名不合法" }
        val q = shellQuote(pkg)
        val result = checked(when (action) {
            "启动应用" -> "monkey -p $q -c android.intent.category.LAUNCHER 1"
            "强制停止" -> "am force-stop $q"
            "清除数据" -> "pm clear $q"
            "卸载应用" -> "pm uninstall $q"
            else -> "dumpsys package $q"
        })
        if (action == "卸载应用" || action == "清除数据") check(result.contains("Success")) { result }
        message(result.ifEmpty { "$action 完成" }.take(3000))
        if (action == "卸载应用") mutable.update { it.copy(apps = it.apps.filterNot { app -> app.packageName == pkg }) }
    }

    /** 在独立 sync 流中完成文件操作。 */
    private fun <T> sync(block: (AdbFiles) -> T): T {
        check(manager.isConnected) { "请先连接设备 B" }
        return manager.openStream("sync:").use { stream -> block(AdbFiles(stream.openInputStream(), stream.openOutputStream())) }
    }

    /** 浏览共享存储目录。 */
    fun browse(path: String = mutable.value.path) = runAction {
        val files = sync { it.list(path) }
        mutable.update { it.copy(path = path, files = files) }
    }

    /** 在当前设备目录创建文件夹。 */
    fun mkdir(name: String) = runAction {
        checked("mkdir ${shellQuote(mutable.value.path.trimEnd('/') + "/" + safeFileName(name))}")
        val files = sync { it.list(mutable.value.path) }
        mutable.update { it.copy(files = files, message = "文件夹已创建") }
    }

    /** 保存用户授权的下载目录。 */
    fun setDownloadTree(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("downloadTree", uri.toString()).apply()
        mutable.update { it.copy(downloadTree = uri.toString(), message = "下载保存位置已更新") }
    }

    /** 读取系统文件选择器返回的文件名和大小。 */
    fun localInfo(uri: Uri): Pair<String, Long> = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) safeFileName(it.getString(0)) to (if (it.isNull(1)) 0L else it.getLong(1)) else error("无法读取文件信息")
    } ?: error("无法读取文件信息")

    /** 更新单个传输任务。 */
    private fun updateTransfer(id: String, change: (TransferEntry) -> TransferEntry) {
        mutable.update { it.copy(transfers = it.transfers.map { row -> if (row.id == id) change(row) else row }) }
    }

    /** 执行可取消的传输任务，保留失败原因和重试入口。 */
    private fun transfer(name: String, upload: Boolean, size: Long, retry: () -> Unit, block: (String, (Long) -> Unit) -> Unit) {
        val id = UUID.randomUUID().toString()
        retries[id] = retry
        mutable.update { it.copy(transfers = listOf(TransferEntry(id, name, upload, size)) + it.transfers) }
        transferJobs[id] = viewModelScope.launch(Dispatchers.IO) {
            try {
                checkDownloadActive(id)
                updateTransfer(id) { if (it.status == "已取消") it else it.copy(status = "传输中") }
                block(id) { done -> updateTransfer(id) { it.copy(done = done) } }
                updateTransfer(id) {
                    if (it.status == "已取消") it
                    else it.copy(status = if (it.failures.isEmpty()) "已完成" else "部分失败")
                }
                if (mutable.value.transfers.first { it.id == id }.status == "已完成") retries.remove(id)
            } catch (e: Exception) {
                android.util.Log.e("AdbAssistant", "文件传输失败", e)
                updateTransfer(id) { if (it.status == "已取消") it else it.copy(status = "失败", error = e.message ?: "传输失败：${e.javaClass.simpleName}") }
            } finally { streams.remove(id)?.let { runCatching { it.close() } }; transferJobs.remove(id) }
        }
    }

    /** 取消任务，通过关闭流中断阻塞读写。 */
    fun cancelTransfer(id: String) {
        updateTransfer(id) { it.copy(status = "已取消") }
        streams[id]?.let { runCatching { it.close() } }
        transferJobs[id]?.cancel()
    }

    /** 重新执行失败或取消的传输。 */
    fun retryTransfer(id: String) { retries[id]?.invoke() }

    /** 上传到临时文件，完整成功后移动，取消时清理临时文件。 */
    fun upload(uri: Uri, path: String, name: String, overwrite: Boolean = false) {
        val info = runCatching { localInfo(uri) }.getOrElse { message(it.message ?: "文件不可读"); return }
        val target = path.trimEnd('/') + "/" + safeFileName(name)
        transfer(name, true, info.second, { upload(uri, path, name, overwrite) }) { id, progress ->
            val temporary = "$target.adbside-$id.part"
            try {
                check(manager.isConnected) { "请先连接设备 B" }
                manager.openStream("sync:").use { stream ->
                    streams[id] = stream
                    resolver.openInputStream(uri)?.use { source ->
                        AdbFiles(stream.openInputStream(), stream.openOutputStream()).send(temporary, source, progress)
                    } ?: error("无法读取本机文件")
                }
                check(mutable.value.transfers.first { it.id == id }.status != "已取消") { "已取消" }
                // 在移动前重新检查，避免选择文件后目标目录内容变化造成误覆盖。
                val q = shellQuote(target)
                checked(if (overwrite) "mv -f ${shellQuote(temporary)} $q" else "if [ -e $q ]; then echo '目标文件已存在'; exit 1; else mv ${shellQuote(temporary)} $q; fi")
                val files = sync { it.list(path) }
                mutable.update { if (it.path == path) it.copy(files = files) else it }
            } finally { runCatching { checked("rm -f ${shellQuote(temporary)}") } }
        }
    }

    /** 将远端文件保存到系统提供的目标 URI，失败后删除不完整文件。 */
    fun download(file: RemoteFile, remote: String, destination: Uri, retry: (() -> Unit)? = null) {
        transfer(file.name, false, file.size, retry ?: { message("请重新选择下载保存位置后重试") }) { id, progress ->
            try {
                manager.openStream("sync:").use { stream ->
                    streams[id] = stream
                    resolver.openOutputStream(destination, "wt")?.use {
                        AdbFiles(stream.openInputStream(), stream.openOutputStream()).receive(remote, it, progress)
                    } ?: error("无法写入下载位置")
                }
                check(mutable.value.transfers.first { it.id == id }.status != "已取消") { "已取消" }
            } catch (e: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                throw e
            }
        }
    }

    /** 检查取消状态，防止递归任务在关闭流后继续创建文件。 */
    private fun checkDownloadActive(id: String) {
        if (mutable.value.transfers.first { it.id == id }.status == "已取消") throw CancellationException("下载已取消")
    }

    /** 为目录扫描和文件接收注册可取消的同步流。 */
    private fun <T> downloadSync(id: String, block: (AdbFiles) -> T): T {
        checkDownloadActive(id)
        return manager.openStream("sync:").use { stream ->
            streams[id] = stream
            try { checkDownloadActive(id); block(AdbFiles(stream.openInputStream(), stream.openOutputStream())) }
            finally { streams.remove(id, stream) }
        }
    }

    /** 先扫描目录以统计总大小，保留空目录并记录不可读取的项目。 */
    private fun scanDownloadFolder(id: String, remote: String, relative: String, entries: MutableList<FolderDownloadItem>, failures: MutableList<String>) {
        checkDownloadActive(id)
        require(relative.count { it == '/' } < 128) { "目录层级过深" }
        updateTransfer(id) { it.copy(currentFile = relative) }
        try {
            checked("test ! -L ${shellQuote(remote)} && test -r ${shellQuote(remote)} && test -x ${shellQuote(remote)}")
            // 部分 ADB 服务会返回链接目标的类型，额外识别链接以防循环进入目录。
            val links = checked("find ${shellQuote(remote)} -mindepth 1 -maxdepth 1 -type l -print")
                .lineSequence().filter { it.isNotBlank() }.map { it.substringAfterLast('/') }.toSet()
            // 即使 ADB 目录列表隐藏了链接，也在结果中说明该项被跳过。
            for (link in links) failures += "${if (relative.isEmpty()) link else "$relative/$link"}：跳过符号链接"
            val children = downloadSync(id) { it.list(remote) }
            for (child in children) {
                checkDownloadActive(id)
                safeFileName(child.name)
                val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (child.name in links) continue
                if (child.symbolicLink) { failures += "$childRelative：跳过符号链接"; continue }
                val entry = FolderDownloadItem(childRelative, "$remote/${child.name}", child)
                entries += entry
                check(entries.size <= 100000) { "目录项目超过十万项，请分批下载" }
                if (child.directory) scanDownloadFolder(id, entry.remote, entry.relative, entries, failures)
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures += "${relative.ifEmpty { remote }}：${e.message ?: "目录读取失败"}" }
    }

    /** 创建不覆盖已有内容的文档，同名时使用带序号的名称。 */
    private fun createDownloadDocument(parent: Uri, name: String, directory: Boolean): Uri {
        safeFileName(name)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val existing = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) existing += cursor.getString(0)
        } ?: error("无法读取下载目录")
        var actualName = name
        var number = 1
        while (actualName in existing) { actualName = "$name ($number)"; number++ }
        return DocumentsContract.createDocument(resolver, parent,
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", actualName) ?: error("无法创建 $actualName")
    }

    /** 递归下载一个文件夹，失败项单独记录，其他文件继续下载。 */
    private fun downloadFolder(file: RemoteFile, remote: String, parent: Uri) {
        transfer(file.name, false, 0, { downloadFolder(file, remote, parent) }) { id, _ ->
            updateTransfer(id) { if (it.status == "已取消") it else it.copy(status = "扫描中") }
            val entries = mutableListOf<FolderDownloadItem>()
            val failures = mutableListOf<String>()
            scanDownloadFolder(id, remote, "", entries, failures)
            checkDownloadActive(id)
            val total = entries.filter { !it.file.directory }.sumOf { it.file.size }
            updateTransfer(id) { if (it.status == "已取消") it else it.copy(status = "传输中", total = total, totalFiles = entries.count { entry -> !entry.file.directory }) }
            checkDownloadActive(id)
            val root = createDownloadDocument(parent, file.name, true)
            val directories = mutableMapOf("" to root)
            var done = 0L
            var completed = 0
            for (entry in entries) {
                checkDownloadActive(id)
                updateTransfer(id) { it.copy(currentFile = entry.relative) }
                var destination: Uri? = null
                try {
                    val parentPath = entry.relative.substringBeforeLast('/', "")
                    val localParent = directories[parentPath] ?: error("父目录未创建")
                    destination = createDownloadDocument(localParent, entry.file.name, entry.file.directory)
                    checkDownloadActive(id)
                    if (entry.file.directory) directories[entry.relative] = destination
                    else {
                        val base = done
                        resolver.openOutputStream(destination, "wt")?.use { output ->
                            downloadSync(id) { sync -> sync.receive(entry.remote, output) { bytes ->
                                checkDownloadActive(id)
                                updateTransfer(id) { it.copy(done = base + bytes) }
                            } }
                        } ?: error("无法写入目标文件")
                        done += entry.file.size
                        completed++
                        updateTransfer(id) { it.copy(done = done, completedFiles = completed) }
                    }
                } catch (e: Exception) {
                    if (!entry.file.directory && destination != null) runCatching { DocumentsContract.deleteDocument(resolver, destination) }
                    if (e is CancellationException) throw e
                    checkDownloadActive(id)
                    failures += "${entry.relative}：${e.message ?: "下载失败"}"
                }
            }
            checkDownloadActive(id)
            updateTransfer(id) { it.copy(currentFile = "", failures = failures.toList()) }
        }
    }

    /** 使用用户授权目录批量下载文件与文件夹，保留同名项目为独立副本。 */
    fun downloadToTree(files: List<RemoteFile>, path: String) {
        val tree = mutable.value.downloadTree
        if (tree.isBlank()) { message("请先选择下载保存位置"); return }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(tree)
                val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                files.forEach { file ->
                    val remote = path.trimEnd('/') + "/" + safeFileName(file.name)
                    if (file.symbolicLink) message("${file.name} 是符号链接，请进入实际目录下载")
                    else if (file.directory) downloadFolder(file, remote, parent)
                    else {
                        val destination = createDownloadDocument(parent, file.name, false)
                        download(file, remote, destination) { downloadToTree(listOf(file), path) }
                    }
                }
            } catch (e: Exception) { message(e.message ?: "下载失败") }
        }
    }

    /** 上传 APK 后安装，始终清理设备上的临时安装包。 */
    fun install(uri: Uri) = runAction {
        val path = "/data/local/tmp/adbside-${UUID.randomUUID()}.apk"
        try {
            resolver.openInputStream(uri)?.use { source -> sync { it.send(path, source) {} } } ?: error("APK 文件不可读")
            val result = checked("pm install -r ${shellQuote(path)}")
            check(result.contains("Success")) { result }
            message("APK 安装成功，请刷新应用列表")
        } finally { runCatching { checked("rm -f ${shellQuote(path)}") } }
    }

    /** 下载应用基础 APK，拆分包提示用户仍需其他 split APK。 */
    fun exportApk(pkg: String, destination: Uri) = runAction {
        val paths = checked("pm path ${shellQuote(pkg)}").lineSequence().filter { it.startsWith("package:") }.map { it.removePrefix("package:").trim() }.toList()
        val base = paths.firstOrNull { it.endsWith("/base.apk") } ?: paths.firstOrNull() ?: error("找不到 APK")
        resolver.openOutputStream(destination, "wt")?.use { out -> sync { it.receive(base, out) {} } } ?: error("无法保存 APK")
        message(if (paths.size > 1) "已导出基础 APK；此应用含拆分包，基础 APK 不能单独完整安装" else "APK 已导出")
    }

    /** 先获取截图并显示预览，此时不要求用户选择保存位置。 */
    fun screenshot() = runAction {
        manager.openStream("exec:screencap -p").use { stream ->
            val timeout = timer.schedule({ runCatching { stream.close() } }, 30, TimeUnit.SECONDS)
            try {
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                val input = stream.openInputStream()
                while (true) {
                    val n = try { input.read(buffer) } catch (e: IOException) { if (stream.isClosed) -1 else throw e }
                    if (n < 0) break
                    check(bytes.size() + n <= 32 * 1024 * 1024) { "截图超过大小限制" }
                    bytes.write(buffer, 0, n)
                }
                val png = bytes.toByteArray()
                check(png.size >= 20 && png.takeLast(8).toByteArray().contentEquals(byteArrayOf(73, 69, 78, 68, -82, 66, 96, -126))) { "未收到完整 PNG 截图" }
                check(!timeout.isDone) { "截图超时" }
                // 预览按需缩小，保存时仍使用原始 PNG 字节。
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 2048 || bounds.outHeight / sample > 2048) sample *= 2
                val preview = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("无法解码截图")
                screenshotBytes = png
                mutable.update { it.copy(screenshotPreview = preview) }
            } finally { timeout.cancel(false) }
        }
    }

    /** 用户选择保存后，将预览对应的原始截图写入目标文件。 */
    fun saveScreenshot(destination: Uri) = runAction {
        val bytes = screenshotBytes ?: error("截图已关闭，请重新截图")
        try {
            resolver.openOutputStream(destination, "wt")?.use { it.write(bytes) } ?: error("无法保存截图")
            dismissScreenshot()
            message("截图已保存")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    /** 关闭预览并释放原始截图引用。 */
    fun dismissScreenshot() {
        screenshotBytes = null
        mutable.update { it.copy(screenshotPreview = null) }
    }

    /** 执行设备快捷操作。 */
    fun deviceAction(action: String) = runAction {
        when (action) {
            "设备信息" -> message(checked("getprop ro.product.model; getprop ro.build.version.release; wm size; df -h /sdcard"))
            "打开设置" -> { checked("am start -a android.settings.SETTINGS"); message("已打开设备 B 的设置") }
            "返回键" -> { checked("input keyevent KEYCODE_BACK") }
            "主页键" -> { checked("input keyevent KEYCODE_HOME") }
            "重启" -> { manager.openStream("reboot:").close(); manager.disconnect(); mutable.update { it.copy(connected = false, message = "已发送重启请求") } }
        }
    }

    /** 清理页面生命周期之外的网络资源。 */
    override fun onCleared() {
        streams.values.forEach { runCatching { it.close() } }
        timer.shutdownNow()
        runCatching { manager.close() }
        super.onCleared()
    }
}
