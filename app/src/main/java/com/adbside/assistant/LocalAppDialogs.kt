package com.adbside.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** 选择本机应用，搜索名称或包名后查看安装包详情。 */
@Composable
fun LocalAppsDialog(state: AssistantState, dismiss: () -> Unit, select: (LocalAppEntry) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var system by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("从本机安装", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    TextButton(dismiss) { Text("关闭") }
                }
                Text("选择设备 A 上的应用，确认后推送到设备 B。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索应用名称或包名") }, singleLine = true)
                TabRow(selectedTabIndex = if (system) 1 else 0, containerColor = Color.Transparent) {
                    Tab(!system, { system = false }, text = { Text("用户应用") })
                    Tab(system, { system = true }, text = { Text("系统应用") })
                }
                if (state.localAppsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                val apps = state.localApps.filter { it.system == system && (it.name.contains(query, true) || it.packageName.contains(query, true)) }
                LazyColumn(Modifier.weight(1f)) {
                    if (apps.isEmpty() && !state.localAppsLoading) item { Text("暂无匹配的本机应用", Modifier.padding(vertical = 24.dp)) }
                    items(apps, key = { it.packageName }) { app ->
                        ListItem(headlineContent = { Text(app.name) }, supportingContent = {
                            Text("${app.packageName}\n${app.versionName} · ${app.apkPaths.size} 个 APK · ${formatSize(app.totalBytes)}")
                        }, leadingContent = {
                            if (app.icon != null) Image(app.icon.asImageBitmap(), null, Modifier.size(40.dp))
                            else Icon(Icons.Outlined.Android, null, Modifier.size(40.dp))
                        }, modifier = Modifier.clickable { select(app) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** 显示提取、上传、安装进度，结束后保留具体结果供用户查看。 */
@Composable
fun LocalInstallDialog(progress: LocalInstallProgress, busy: Boolean, dismiss: () -> Unit) {
    AlertDialog(onDismissRequest = { if (progress.finished && !busy) dismiss() },
        title = { Text(if (progress.finished) "应用推送结果" else "正在推送本机应用") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(progress.name, style = MaterialTheme.typography.titleMedium)
                Text("目标：${progress.target}", style = MaterialTheme.typography.bodySmall)
                Text(progress.stage)
                if (progress.stage.startsWith("正在上传") && progress.totalBytes > 0) {
                    LinearProgressIndicator(progress = { (progress.uploadedBytes.toFloat() / progress.totalBytes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else if (!progress.finished) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("已上传 ${formatSize(progress.uploadedBytes)} / ${formatSize(progress.totalBytes)}\n安装包 ${progress.completedPackages} / ${progress.packageCount}", style = MaterialTheme.typography.bodySmall)
                if (progress.error.isNotBlank()) Text(progress.error, color = MaterialTheme.colorScheme.error)
                if (progress.cleanupWarning.isNotBlank()) Text(progress.cleanupWarning, color = MaterialTheme.colorScheme.error)
                if (progress.success) Text("安装完成，可刷新设备 B 的应用列表。")
                else if (!progress.finished) Text("安装期间请保持应用在前台。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { if (progress.finished) TextButton(dismiss, enabled = !busy) { Text("完成") } })
}
