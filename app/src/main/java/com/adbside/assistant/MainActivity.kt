package com.adbside.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    /** 创建原生 Compose 页面。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF00878B),
                secondaryContainer = Color(0xFFE3F3F3), background = Color(0xFFF7F9FC),
                surface = Color.White, onSurface = Color(0xFF172331))) { AssistantApp() }
        }
    }
}

/** 显示四个主要功能入口和全局操作状态。 */
@Composable
fun AssistantApp(model: AssistantModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var transfers by rememberSaveable { mutableStateOf(false) }
    val labels = listOf("设备", "应用", "文件", "终端")
    val icons = listOf(Icons.Outlined.PhoneAndroid, Icons.Outlined.Apps, Icons.Outlined.Folder, Icons.Outlined.Terminal)
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
            labels.forEachIndexed { i, label ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; transfers = false },
                    icon = { Icon(icons[i], label) }, label = { Text(label) })
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> DevicePage(model, state)
                1 -> AppsPage(model, state)
                2 -> if (transfers) TransfersPage(model, state) { transfers = false } else FilesPage(model, state) { transfers = true }
                3 -> TerminalPage(model, state)
            }
        }
    }
    if (state.message.isNotBlank()) AlertDialog(onDismissRequest = { model.message("") },
        title = { Text("操作提示") }, text = { Text(state.message, Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { model.message("") }) { Text("知道了") } })
}

/** 显示页面标题与当前连接设备。 */
@Composable
private fun PageHeader(title: String, state: AssistantState) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large) {
            Text(if (state.connected) "● ${state.device}" else "未连接", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** 显示带图标的操作按钮。 */
@Composable
private fun ActionButton(label: String, icon: ImageVector, enabled: Boolean = true, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled, contentPadding = PaddingValues(horizontal = 12.dp)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}

/** 编辑地址与配对参数，明确区分配对和连接端口。 */
@Composable
private fun DevicePage(model: AssistantModel, state: AssistantState) {
    var host by rememberSaveable { mutableStateOf(model.savedHost) }
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var connectionPort by rememberSaveable { mutableStateOf(model.savedPort) }
    var code by rememberSaveable { mutableStateOf("") }
    var pairing by rememberSaveable { mutableStateOf(true) }
    var reboot by remember { mutableStateOf(false) }
    val screenshot = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { it?.let(model::saveScreenshot) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("ADB 助手", state)
        Text("连接并管理另一台 Android 设备", color = MaterialTheme.colorScheme.onSurfaceVariant)
        ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors(containerColor = Color.White)) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(if (state.connected) state.device else "尚未连接设备", fontWeight = FontWeight.Bold)
                    Text(if (state.connected) state.address else "请在设备 B 开启无线调试", style = MaterialTheme.typography.bodySmall)
                }
                if (state.connected) TextButton(onClick = model::disconnect, enabled = !state.busy) { Text("断开") }
            }
        }
        Text("快捷操作", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton("截图", Icons.Outlined.Screenshot, state.connected && !state.busy) { model.screenshot() }
            ActionButton("设备信息", Icons.Outlined.Info, state.connected && !state.busy) { model.deviceAction("设备信息") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton("重启", Icons.Outlined.RestartAlt, state.connected && !state.busy) { reboot = true }
            ActionButton("打开设置", Icons.Outlined.Settings, state.connected && !state.busy) { model.deviceAction("打开设置") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ActionButton("返回键", Icons.AutoMirrored.Outlined.ArrowBack, state.connected && !state.busy) { model.deviceAction("返回键") }
            ActionButton("主页键", Icons.Outlined.Home, state.connected && !state.busy) { model.deviceAction("主页键") }
        }
        Text("添加设备", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(pairing, { pairing = true }, { Text("无线配对") })
            FilterChip(!pairing, { pairing = false }, { Text("IP 直连") })
        }
        OutlinedTextField(host, { host = it.trim().replace('。', '.') }, Modifier.fillMaxWidth(), label = { Text("设备 B 的 IP 地址") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true)
        if (pairing) {
            Text("在设备 B：开发者选项 → 无线调试 → 使用配对码配对设备", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(pairingPort, { pairingPort = it }, Modifier.weight(1f), label = { Text("配对端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                OutlinedTextField(code, { code = it.take(6) }, Modifier.weight(1f), label = { Text("六位配对码") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
            }
            OutlinedButton(onClick = { model.pair(host, pairingPort, code); code = "" }, Modifier.fillMaxWidth(), enabled = !state.busy && !state.connected) { Text("配对设备") }
        }
        OutlinedTextField(connectionPort, { connectionPort = it }, Modifier.fillMaxWidth(), label = { Text("连接端口") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        Text(if (pairing) "连接端口位于无线调试主页，与配对端口不同。配对成功后再连接。" else "设备 B 需要已开启 TCP/IP ADB；传统模式首次连接请在 B 上允许调试授权。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { model.connect(host, connectionPort) }, Modifier.fillMaxWidth(), enabled = !state.busy && !state.connected) { Text("连接设备") }
        Spacer(Modifier.height(20.dp))
    }
    if (reboot) ConfirmDialog("重启设备 B？", "设备将重启，当前连接与传输会中断。", { reboot = false }) { reboot = false; model.deviceAction("重启") }
    state.screenshotPreview?.let { preview ->
        Dialog(onDismissRequest = { if (!state.busy) model.dismissScreenshot() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(16.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("截图预览", style = MaterialTheme.typography.titleLarge)
                    Image(preview.asImageBitmap(), "设备 B 截图预览", Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(model::dismissScreenshot, enabled = !state.busy) { Text("不保存") }
                        Button({ screenshot.launch("Screenshot_${System.currentTimeMillis()}.png") }, enabled = !state.busy) { Text("保存截图") }
                    }
                }
            }
        }
    }
}

/** 展示应用包列表与应用操作面板。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppsPage(model: AssistantModel, state: AssistantState) {
    var system by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var confirmation by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportPackage by rememberSaveable { mutableStateOf("") }
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { confirmation = "安装 APK" to it.toString() } }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")) { uri -> uri?.let { model.exportApk(exportPackage, it) } }
    LaunchedEffect(state.connected, system) { if (state.connected) model.loadApps(system) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageHeader("应用管理", state)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("搜索包名") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!system, { system = false }, { Text("用户应用") })
            FilterChip(system, { system = true }, { Text("系统应用") })
            Spacer(Modifier.weight(1f))
            IconButton({ model.loadApps(system) }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        Button({ installer.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream")) }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.Add, null); Text("安装 APK") }
        if (!state.connected) EmptyHint("先到设备页连接设备 B")
        else LazyColumn(Modifier.weight(1f)) {
            items(state.apps.filter { it.packageName.contains(query, true) }, key = { it.packageName }) { app ->
                ListItem(headlineContent = { Text(app.packageName, maxLines = 2) }, supportingContent = { Text("versionName：${app.versionName}\n${app.version}") },
                    leadingContent = { Icon(Icons.Outlined.Android, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.Outlined.MoreVert, null) }, modifier = Modifier.clickable(enabled = !state.busy) { selected = app })
                HorizontalDivider()
            }
        }
    }
    selected?.let { app ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.packageName, style = MaterialTheme.typography.titleMedium)
                Text(app.version, style = MaterialTheme.typography.bodySmall)
                Text("versionName：${app.versionName}", style = MaterialTheme.typography.bodySmall)
                listOf("启动应用", "导出 APK", "强制停止", "清除数据", "卸载应用").forEach { action ->
                    val destructive = action in listOf("强制停止", "清除数据", "卸载应用")
                    TextButton(onClick = {
                        selected = null
                        when {
                            action == "导出 APK" -> { exportPackage = app.packageName; exporter.launch("${app.packageName}.apk") }
                            destructive -> confirmation = action to app.packageName
                            else -> model.appAction(app.packageName, action)
                        }
                    }, Modifier.fillMaxWidth()) { Text(action, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                }
            }
        }
    }
    confirmation?.let { (action, target) ->
        ConfirmDialog("确认$action？", if (action == "安装 APK") "将在设备 B 上安装所选 APK；同包名应用可能被更新。" else "$target\n${if (action == "清除数据") "应用本地数据将被删除，无法撤销。" else if (action == "卸载应用") "应用及其本地数据将被删除。" else "应用当前运行会被中断。"}", { confirmation = null }) {
            confirmation = null
            if (action == "安装 APK") model.install(android.net.Uri.parse(target)) else model.appAction(target, action)
        }
    }
}

/** 浏览远程文件，并通过系统文件选择器完成双向传输。 */
@Composable
private fun FilesPage(model: AssistantModel, state: AssistantState, openTransfers: () -> Unit) {
    var selected by remember { mutableStateOf(setOf<String>()) }
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var conflict by remember { mutableStateOf<android.net.Uri?>(null) }
    var rename by remember { mutableStateOf("") }
    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> pending = uris }
    val tree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { runCatching { model.setDownloadTree(it) }.onFailure { e -> model.message(e.message ?: "目录授权失败") } } }
    LaunchedEffect(state.connected) { if (state.connected) model.browse() }
    LaunchedEffect(state.path) { selected = emptySet() }
    LaunchedEffect(pending, conflict) {
        if (conflict == null && pending.isNotEmpty()) {
            val uri = pending.first()
            pending = pending.drop(1)
            runCatching { model.localInfo(uri) }.onSuccess { info ->
                if (state.files.any { it.name == info.first }) { conflict = uri; rename = info.first }
                else model.upload(uri, state.path, info.first)
            }.onFailure { model.message(it.message ?: "无法读取文件") }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        PageHeader("文件管理", state)
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
            Text("正在浏览设备 B · ${state.path}", Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { model.browse(state.path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }) }, enabled = state.connected && !state.busy && state.path != "/") { Icon(Icons.Outlined.ArrowUpward, "上级目录") }
            Button({ uploader.launch(arrayOf("*/*")) }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.Upload, null); Text("上传") }
            IconButton({ newFolder = true }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.CreateNewFolder, "新建文件夹") }
            IconButton({ model.browse() }, enabled = state.connected && !state.busy) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        Text("上传：本机 A → 设备 B　下载：设备 B → 本机 A", style = MaterialTheme.typography.labelSmall)
        if (!state.connected) Box(Modifier.weight(1f)) { EmptyHint("先到设备页连接设备 B") }
        else LazyColumn(Modifier.weight(1f)) {
            if (state.files.isEmpty()) item { EmptyHint("目录为空，或该目录没有可读取的内容") }
            items(state.files, key = { it.name }) { file ->
                ListItem(headlineContent = { Text(file.name) }, supportingContent = {
                    Text((if (file.directory) "文件夹" else formatSize(file.size)) + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(file.modified * 1000)))
                }, leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(file.name in selected, { checked -> selected = if (checked) selected + file.name else selected - file.name }, enabled = !state.busy && !file.symbolicLink)
                        if (file.directory) Icon(Icons.Outlined.Folder, null, tint = Color(0xFFE9AC2F))
                    }
                }, modifier = Modifier.clickable(enabled = !state.busy) {
                    if (file.directory) model.browse(state.path.trimEnd('/') + "/" + file.name)
                    else selected = if (file.name in selected) selected - file.name else selected + file.name
                })
                HorizontalDivider()
            }
        }
        if (selected.isNotEmpty()) Button({ model.downloadToTree(state.files.filter { it.name in selected }, state.path); if (state.downloadTree.isNotBlank()) openTransfers() }, Modifier.fillMaxWidth(), enabled = state.connected) {
            Icon(Icons.Outlined.Download, null); Text("下载到本机（${selected.size} 项）")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ tree.launch(state.downloadTree.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)) }) { Text(if (state.downloadTree.isBlank()) "选择下载保存位置" else "更改下载保存位置") }
            TextButton(openTransfers) { Text("传输任务 ${state.transfers.count { it.status in listOf("扫描中", "传输中", "等待中") }}") }
        }
    }
    if (newFolder) AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("新建文件夹") },
        text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("文件夹名称") }, singleLine = true) },
        confirmButton = { TextButton({ newFolder = false; model.mkdir(folderName); folderName = "" }) { Text("创建") } },
        dismissButton = { TextButton({ newFolder = false }) { Text("取消") } })
    conflict?.let { uri ->
        AlertDialog(onDismissRequest = { conflict = null }, title = { Text("存在同名文件") },
            text = { Column { Text("可修改名称后上传、覆盖原文件或跳过。覆盖无法撤销。"); OutlinedTextField(rename, { rename = it }, label = { Text("目标文件名") }) } },
            confirmButton = { TextButton({ runCatching { safeFileName(rename) }.onSuccess { model.upload(uri, state.path, it); conflict = null }.onFailure { model.message(it.message ?: "文件名错误") } }) { Text("按此名称上传") } },
            dismissButton = { Row {
                TextButton({ val name = model.localInfo(uri).first; model.upload(uri, state.path, name, overwrite = true); conflict = null }) { Text("覆盖", color = MaterialTheme.colorScheme.error) }
                TextButton({ conflict = null }) { Text("跳过") }
            } })
    }
}

/** 展示上传和下载进度，以及取消与重试入口。 */
@Composable
private fun TransfersPage(model: AssistantModel, state: AssistantState, back: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text("传输任务", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("下载同名文件由系统保留为独立副本。传输期间请保持应用在前台。", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall)
        if (state.transfers.isEmpty()) EmptyHint("暂无传输任务")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.transfers, key = { it.id }) { task ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (task.upload) Icons.Outlined.Upload else Icons.Outlined.Download, null, tint = MaterialTheme.colorScheme.primary)
                            Text(task.name, Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold)
                            Text(task.status, style = MaterialTheme.typography.labelMedium)
                        }
                        Text(if (task.upload) "本机 A → 设备 B" else "设备 B → 本机 A", style = MaterialTheme.typography.bodySmall)
                        if (task.total > 0) LinearProgressIndicator(progress = { (task.done.toFloat() / task.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else if (task.status == "传输中") LinearProgressIndicator(Modifier.fillMaxWidth())
                        val seconds = ((System.currentTimeMillis() - task.started) / 1000.0).coerceAtLeast(1.0)
                        Text("${formatSize(task.done)} / ${if (task.total > 0) formatSize(task.total) else "未知大小"}" + if (task.status == "传输中") " · ${formatSize((task.done / seconds).toLong())}/s" else "", style = MaterialTheme.typography.bodySmall)
                        if (task.currentFile.isNotEmpty()) Text("当前：${task.currentFile}", style = MaterialTheme.typography.bodySmall)
                        if (task.totalFiles > 0) Text("文件完成 ${task.completedFiles} / ${task.totalFiles}", style = MaterialTheme.typography.bodySmall)
                        if (task.error.isNotEmpty()) Text(task.error, color = MaterialTheme.colorScheme.error)
                        if (task.failures.isNotEmpty()) {
                            Text("失败或跳过 ${task.failures.size} 项", color = MaterialTheme.colorScheme.error)
                            Text(task.failures.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                        }
                        if (task.status in listOf("等待中", "扫描中", "传输中")) TextButton({ model.cancelTransfer(task.id) }) { Text("取消传输") }
                        else if (task.status in listOf("失败", "部分失败", "已取消")) TextButton({ model.retryTransfer(task.id) }) { Text("重试") }
                    }
                }
            }
        }
    }
}

/** 显示常用命令、输出和输入框。 */
@Composable
private fun TerminalPage(model: AssistantModel, state: AssistantState) {
    var input by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scroll = rememberScrollState()
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val terminalStyle = TextStyle(color = Color(0xFFE7EDF4), fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 21.sp)

    /** 在提示符处提交命令，执行期间阻止重复提交。 */
    fun submitCommand() {
        if (state.connected && !state.busy && input.isNotBlank()) {
            model.command(input)
            input = ""
        }
    }

    LaunchedEffect(state.terminal, state.terminalRunning, scroll.viewportSize, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("命令终端", state)
        if (!keyboardVisible) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("设备型号" to "getprop ro.product.model", "查看进程" to "ps -A", "存储空间" to "df -h /sdcard").forEach { (name, command) ->
                    SuggestionChip({ input = command }, { Text(name) })
                }
            }
            Text("点击终端输入，按 Enter 执行 · 单次执行上限 30 秒", style = MaterialTheme.typography.bodySmall)
        }
        Surface(Modifier.fillMaxWidth().weight(1f).clickable {
            if (state.connected && !state.busy) { focusRequester.requestFocus(); keyboard?.show() }
        }, shape = MaterialTheme.shapes.medium, color = Color(0xFF1D2229)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(14.dp)) {
                SelectionContainer {
                    Text(state.terminal.trimEnd('\r', '\n'), style = terminalStyle)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    Text(if (state.terminalRunning) "… " else "${state.terminalPath} $ ",
                        modifier = Modifier.widthIn(max = 180.dp), style = terminalStyle.copy(color = Color(0xFF59D8C3)))
                    // 输入与输出共用终端背景，不显示独立边框和发送按钮。
                    BasicTextField(value = input, onValueChange = { input = it.replace('\n', ' ').replace('\r', ' ') },
                        modifier = Modifier.weight(1f).heightIn(min = 28.dp).focusRequester(focusRequester).onPreviewKeyEvent {
                            if (it.key == Key.Enter || it.key == Key.NumPadEnter) {
                                if (it.type == KeyEventType.KeyDown) submitCommand()
                                true
                            } else false
                        },
                        textStyle = terminalStyle, cursorBrush = SolidColor(Color(0xFF59D8C3)),
                        readOnly = !state.connected || state.busy, singleLine = true,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submitCommand() }))
                }
            }
        }
        Row {
            TextButton({ history = true }) { Text("历史记录") }
            TextButton(model::clearTerminal, enabled = !state.terminalRunning) { Text("清空输出") }
            if (state.terminalRunning) TextButton(model::stopCommand) { Text("停止命令") }
        }
    }
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("历史记录") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (state.history.isEmpty()) Text("暂无历史命令")
            state.history.forEach { command -> TextButton({ input = command; history = false }) { Text(command) } }
        } }, confirmButton = { TextButton({ history = false }) { Text("关闭") } })
}

/** 展示需要用户明确确认的设备操作。 */
@Composable
private fun ConfirmDialog(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(confirm) { Text("确认") } }, dismissButton = { TextButton(dismiss) { Text("取消") } })
}

/** 展示空列表与未连接状态提示。 */
@Composable
private fun EmptyHint(text: String) { Text(text, Modifier.padding(vertical = 32.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }

/** 将文件字节大小格式化为易读单位。 */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
