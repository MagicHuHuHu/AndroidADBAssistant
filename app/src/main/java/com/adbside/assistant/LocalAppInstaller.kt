package com.adbside.assistant

import java.io.File
import java.util.UUID

data class LocalInstallProgress(
    val name: String, val target: String, val totalBytes: Long, val packageCount: Int,
    val stage: String = "正在提取安装包", val uploadedBytes: Long = 0,
    val completedPackages: Int = 0, val finished: Boolean = false, val success: Boolean = false,
    val error: String = "", val cleanupWarning: String = ""
)

/** 上传完整安装包集合，通过设备 B 的安装会话一次提交。 */
class LocalAppInstaller(
    private val execute: (String, Long) -> String,
    private val upload: (File, String, (Long) -> Unit) -> Unit
) {
    /** 安装成功前不提交会话，失败时放弃会话并清理本次临时文件。 */
    fun install(files: List<File>, progress: (String, Long, Int) -> Unit, cleanup: (String) -> Unit) {
        require(files.isNotEmpty() && files.all { it.isFile && it.canRead() && it.length() > 0 }) { "安装包缺失或不可读取" }
        val directory = "/data/local/tmp/adbside-local-${UUID.randomUUID()}"
        val remoteFiles = files.indices.map { "$directory/${if (it == 0) "base" else "split_$it"}.apk" }
        val total = files.sumOf { it.length() }
        var created = false
        var session: String? = null
        var committed = false
        try {
            execute("mkdir ${shellQuote(directory)}", 30)
            created = true
            var uploaded = 0L
            for ((index, file) in files.withIndex()) {
                val previous = uploaded
                progress("正在上传 ${index + 1}/${files.size}", uploaded, index)
                upload(file, remoteFiles[index]) { bytes -> progress("正在上传 ${index + 1}/${files.size}", previous + bytes, index) }
                uploaded += file.length()
                progress("正在上传 ${index + 1}/${files.size}", uploaded, index + 1)
            }
            progress("正在创建安装会话", uploaded, files.size)
            val response = execute("pm install-create -r -S $total", 30)
            check(response.trimStart().startsWith("Success")) { response.ifBlank { "无法创建安装会话" } }
            session = Regex("\\[(\\d+)\\]").find(response)?.groupValues?.get(1) ?: error("无法识别安装会话：$response")
            for ((index, file) in files.withIndex()) {
                progress("正在写入安装包 ${index + 1}/${files.size}", uploaded, files.size)
                val result = execute("pm install-write -S ${file.length()} $session ${shellQuote(if (index == 0) "base.apk" else "split_$index.apk")} ${shellQuote(remoteFiles[index])}", 120)
                check(result.trimStart().startsWith("Success")) { result.ifBlank { "安装包写入失败" } }
            }
            progress("正在安装，请稍候", uploaded, files.size)
            val result = execute("pm install-commit $session", 120)
            check(result.trimStart().startsWith("Success")) { result.ifBlank { "应用安装失败" } }
            committed = true
        } finally {
            // 仅处理本次创建的会话和 UUID 目录，不卸载目标设备上的已有应用。
            val warnings = mutableListOf<String>()
            if (session != null && !committed) runCatching { execute("pm install-abandon $session", 30) }
                .onFailure { warnings += "安装会话未能清理：${it.message}" }
            if (created) runCatching {
                execute("rm -f ${remoteFiles.joinToString(" ") { shellQuote(it) }}; rmdir ${shellQuote(directory)}", 30)
            }.onFailure { warnings += "设备临时文件未能清理：${it.message}" }
            if (warnings.isNotEmpty()) cleanup(warnings.joinToString("\n"))
        }
    }
}
