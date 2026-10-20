package com.adbside.assistant

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 验证安装事务、拆分包集合和失败清理，不实际安装应用。 */
class LocalAppInstallerTest {
    @get:Rule val temporary = TemporaryFolder()

    /** 创建有内容的测试 APK 占位文件。 */
    private fun apk(name: String, size: Int): File = temporary.newFile(name).apply { writeBytes(ByteArray(size) { 7 }) }

    /** 普通包通过会话提交，安装命令只允许同签名更新。 */
    @Test fun singleApkCommitsAndCleans() {
        val commands = mutableListOf<String>()
        val installer = LocalAppInstaller({ command, _ ->
            commands += command
            if (command.startsWith("pm install-create")) "Success: created install session [42]" else "Success"
        }, { file, _, progress -> progress(file.length()) })
        installer.install(listOf(apk("base.apk", 17)), { _, _, _ -> }, { fail(it) })
        assertTrue(commands.any { it == "pm install-create -r -S 17" })
        assertEquals(1, commands.count { it.startsWith("pm install-write") })
        assertTrue(commands.indexOf("pm install-commit 42") > commands.indexOfFirst { it.startsWith("pm install-write") })
        assertTrue(commands.last().contains("rmdir"))
        assertFalse(commands.any { it.contains("uninstall") || it.contains("install-abandon") || it.contains(" -d ") })
    }

    /** 全部拆分包写入之后才能提交同一个会话。 */
    @Test fun splitApksUseOneCompleteSession() {
        val commands = mutableListOf<String>()
        val uploaded = mutableListOf<String>()
        var finalBytes = 0L
        val files = listOf(apk("base.apk", 10), apk("config.apk", 20), apk("feature.apk", 30))
        LocalAppInstaller({ command, _ ->
            commands += command
            if (command.startsWith("pm install-create")) "Success [7]" else "Success"
        }, { file, path, progress -> uploaded += path; progress(file.length()) })
            .install(files, { _, bytes, _ -> finalBytes = bytes }, { fail(it) })
        assertEquals(3, uploaded.size)
        assertEquals(3, commands.count { it.startsWith("pm install-write") })
        assertEquals(1, commands.count { it == "pm install-commit 7" })
        assertTrue(commands.any { it == "pm install-create -r -S 60" })
        assertEquals(60L, finalBytes)
        assertTrue(commands.indexOf("pm install-commit 7") > commands.indexOfLast { it.startsWith("pm install-write") })
    }

    /** 写入失败时不提交会话，保留错误并放弃会话。 */
    @Test fun writeFailureAbandonsSession() {
        val commands = mutableListOf<String>()
        val result = runCatching {
            LocalAppInstaller({ command, _ ->
                commands += command
                when {
                    command.startsWith("pm install-create") -> "Success [8]"
                    command.startsWith("pm install-write") -> "Failure [INVALID_APK]"
                    else -> "Success"
                }
            }, { _, _, _ -> }).install(listOf(apk("base.apk", 10)), { _, _, _ -> }, { fail(it) })
        }
        assertTrue(result.exceptionOrNull()?.message?.contains("INVALID_APK") == true)
        assertTrue(commands.contains("pm install-abandon 8"))
        assertFalse(commands.any { it.startsWith("pm install-commit") })
        assertTrue(commands.last().contains("rmdir"))
    }

    /** 签名冲突不能通过卸载原应用来绕过，清理失败也不掩盖原错误。 */
    @Test fun commitFailurePreservesOriginalAppAndError() {
        val commands = mutableListOf<String>()
        var warning = ""
        val result = runCatching {
            LocalAppInstaller({ command, _ ->
                commands += command
                when {
                    command.startsWith("pm install-create") -> "Success [9]"
                    command.startsWith("pm install-commit") -> "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]"
                    command.startsWith("rm -f") -> error("offline")
                    else -> "Success"
                }
            }, { _, _, _ -> }).install(listOf(apk("base.apk", 10)), { _, _, _ -> }, { warning = it })
        }
        assertTrue(result.exceptionOrNull()?.message?.contains("UPDATE_INCOMPATIBLE") == true)
        assertTrue(commands.contains("pm install-abandon 9"))
        assertFalse(commands.any { it.contains("uninstall") })
        assertTrue(warning.contains("offline"))
    }

    /** 上传中断时清理临时文件，尚未创建安装会话。 */
    @Test fun uploadFailureDoesNotStartInstallation() {
        val commands = mutableListOf<String>()
        val result = runCatching {
            LocalAppInstaller({ command, _ -> commands += command; "Success" }, { _, _, _ -> error("upload interrupted") })
                .install(listOf(apk("base.apk", 10)), { _, _, _ -> }, { fail(it) })
        }
        assertEquals("upload interrupted", result.exceptionOrNull()?.message)
        assertFalse(commands.any { it.startsWith("pm install-") })
        assertTrue(commands.last().contains("rm -f"))
    }

    /** 安装包不可读或为空时，禁止任何设备操作。 */
    @Test fun missingApkRejectedBeforeRemoteCommands() {
        var executed = false
        val result = runCatching {
            LocalAppInstaller({ _, _ -> executed = true; "Success" }, { _, _, _ -> fail() })
                .install(listOf(temporary.newFile("empty.apk")), { _, _, _ -> }, { fail(it) })
        }
        assertTrue(result.isFailure)
        assertFalse(executed)
    }
}
