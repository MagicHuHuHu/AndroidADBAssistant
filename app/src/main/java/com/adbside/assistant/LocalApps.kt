package com.adbside.assistant

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File

data class LocalAppEntry(
    val packageName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val updatedAt: Long,
    val apkPaths: List<String>,
    val apkSizes: List<Long>,
    val system: Boolean,
    val icon: Bitmap?
) {
    val totalBytes: Long get() = apkSizes.sum()
}

/** 读取本机应用和完整 APK 集合，不读取应用私有数据。 */
class LocalApps(private val context: Context) {
    /** 读取一个应用的基础包、拆分包和展示信息。 */
    fun read(packageName: String, withIcon: Boolean = false): LocalAppEntry {
        val pm = context.packageManager
        val info = pm.getPackageInfo(packageName, 0)
        val app = info.applicationInfo ?: error("无法读取本机应用信息")
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty().toList()
        val icon = if (withIcon) runCatching {
            val drawable = app.loadIcon(pm)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 64, 64)
            drawable.draw(Canvas(bitmap))
            bitmap
        }.getOrNull() else null
        return LocalAppEntry(packageName, app.loadLabel(pm).toString(), info.versionName ?: "未提供",
            info.longVersionCode, info.lastUpdateTime, paths, paths.map { File(it).length() },
            app.flags and ApplicationInfo.FLAG_SYSTEM != 0, icon)
    }

    /** 在后台加载可见的本机应用列表，并缩小图标以控制内存。 */
    fun list(): List<LocalAppEntry> {
        val result = mutableListOf<LocalAppEntry>()
        for (info in context.packageManager.getInstalledPackages(0)) {
            runCatching { read(info.packageName, withIcon = true) }.onSuccess { result += it }
        }
        return result.sortedWith(compareBy<LocalAppEntry> { it.name.lowercase() }.thenBy { it.packageName })
    }
}
