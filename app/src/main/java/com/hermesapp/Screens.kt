package com.hermesapp

import android.app.Activity
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/**
 * 对话页。输入状态用 MutableState<String> 持有：
 * 打字只让 ChatInputBar 重组，消息列表（MessageList）完全不动 —— 消除输入卡顿。
 */
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    prefs: Prefs,
    inputState: MutableState<String>,
    onInput: (String) -> Unit,
) {
    var fullscreen by remember { mutableStateOf(false) }
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    val pend by vm.pendingImages.collectAsState()
    val note by vm.imageNote.collectAsState()
    var askVision by remember { mutableStateOf(false) }

    // 相册/图片选择器（系统 Photo Picker，无需存储权限）
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(10)
    ) { uris ->
        for (u in uris) vm.addImage(ctx, u)
    }

    // 任意文件选择器（系统 Documents UI，可多选，无需存储权限）
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        for (u in uris) vm.addFile(ctx, u)
    }

    Column(Modifier.fillMaxSize()) {
        MessageList(vm, Modifier.weight(1f))
        // 待发送附件：图片显缩略图、其他显文件卡片，右上角 × 可单删
        if (pend.isNotEmpty() || note.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (note.isNotEmpty()) {
                    Text(note, color = c.warn, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in pend) {
                        Box(Modifier.size(56.dp)) {
                            if (p.isImage) {
                                AsyncImage(
                                    model = p.uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                // 非图片：显示文件名 + 类型角标的卡片
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                                        .background(c.panel)
                                ) {
                                    Text(
                                        p.file.name.takeLast(14),
                                        color = c.dim, fontSize = 10.sp,
                                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 3.dp)
                                    )
                                }
                            }
                            Text(
                                "×", color = Color.White, fontSize = 12.sp,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                    .clickable { vm.removeImage(p.id) }
                                    .padding(horizontal = 5.dp)
                            )
                        }
                    }
                }
            }
        }
        ChatInputBar(
            vm = vm,
            inputState = inputState,
            onInput = onInput,
            onFullscreen = { fullscreen = true },
            onPickImages = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onPickFiles = { filePicker.launch(arrayOf("*/*")) },
        )
    }

    if (askVision) {
        AlertDialog(
            onDismissRequest = { askVision = false },
            title = { Text("当前模型不支持图片") },
            text = { Text("要把这几张图自动转成文字描述发过去吗？转文字后模型能读懂图里内容，但原始图片不会保留。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    askVision = false
                    prefs.visionAutoText = true
                    vm.send(inputState.value.trim())
                    onInput("")
                }) { Text("转成文字发送") }
            },
            dismissButton = {
                TextButton(onClick = { askVision = false }) { Text("取消") }
            }
        )
    }

    if (fullscreen) {
        FullScreenInput(
            initial = inputState.value,
            onCancel = { fullscreen = false },
            onDone = { v -> onInput(v); fullscreen = false },
        )
    }
}

/** 消息列表独立成 composable：打字时它不参与重组，长对话滑动也顺。 */
@Composable
fun MessageList(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    val msgs by vm.messages.collectAsState()
    val note by vm.retryNote.collectAsState()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    val view = LocalView.current

    // 末尾放一个 1dp 占位项，永远滚到它 = 永远贴底（正文增长也能跟上）
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.id, msgs.lastOrNull()?.text, msgs.lastOrNull()?.pending) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size)
    }

    Column(
        modifier.fillMaxWidth().pointerInput(Unit) {
            detectTapGestures(onTap = {
                // 点消息区/空白处：清焦点并立即收起软键盘
                focus.clearFocus()
                (ctx as? Activity)?.currentFocus?.clearFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            })
        }
    ) {
        if (note.isNotEmpty()) {
            Text(note, color = c.warn, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().background(c.panel).padding(8.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 10.dp)
        ) {
            items(msgs, key = { it.id }) { m -> Bubble(m, vm::respondApproval) }
            item { Spacer(Modifier.height(1.dp)) }
        }
    }
}

@Composable
fun ChatInputBar(
    vm: ChatViewModel,
    inputState: MutableState<String>,
    onInput: (String) -> Unit,
    onFullscreen: () -> Unit,
    onPickImages: () -> Unit,
    onPickFiles: () -> Unit,
) {
    val c = LocalAppColors.current
    val busy by vm.busy.collectAsState()
    val input = inputState.value
    Row(
        Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(Modifier.weight(1f)) {
            NativeChatInput(
                value = input,
                onValueChange = onInput,
                hintText = "发消息…",
                modifier = Modifier.fillMaxWidth()
            )
            // 全屏 + 图片：并排的小无边框图标，压在输入框右下角
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 无彩色 emoji，用单色描边图标（跟随主题前景色）
                Icon(
                    Icons.Outlined.Photo,
                    contentDescription = "发送图片",
                    tint = c.dim,
                    modifier = Modifier.size(20.dp).clickable { onPickImages() }.padding(horizontal = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "发送文件",
                    tint = c.dim,
                    modifier = Modifier.size(20.dp).clickable { onPickFiles() }.padding(horizontal = 2.dp)
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Icons.Outlined.Fullscreen,
                    contentDescription = "全屏编辑",
                    tint = c.dim,
                    modifier = Modifier.size(20.dp).clickable { onFullscreen() }.padding(horizontal = 2.dp)
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        if (busy) {
            OutlinedButton(
                onClick = { vm.stop() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("停止", color = c.bad, fontSize = 13.sp) }
        } else {
            OutlinedButton(
                onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty() || vm.pendingImages.value.isNotEmpty()) {
                        vm.send(t); onInput("")
                    }
                },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("发送", color = c.accent, fontSize = 13.sp) }
        }
    }
}

@Composable
fun FullScreenInput(
    initial: String,
    onCancel: () -> Unit,
    onDone: (String) -> Unit,
) {
    val c = LocalAppColors.current
    var v by remember { mutableStateOf(initial) }
    // 侧滑/返回键：关全屏输入回到对话界面（不再直接退出软件）
    BackHandler { onCancel() }
    Surface(color = c.bg, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("编辑长文本", color = c.text, fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { onCancel() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("取消", fontSize = 12.sp, color = c.dim) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { onDone(v) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("完成", fontSize = 12.sp, color = c.accent) }
            }
            Spacer(Modifier.height(10.dp))
            NativeChatInput(
                value = v,
                onValueChange = { v = it },
                hintText = "在此输入…",
                modifier = Modifier.weight(1f).fillMaxWidth(),
                minLines = 8,
                maxLines = 500,
                fill = true,
            )
        }
    }
}

@Composable
fun Bubble(m: Msg, onApproval: (Long, String) -> Unit = { _, _ -> }) {
    val c = LocalAppColors.current
    val isUser = m.role == "user"
    var traceOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) c.userBubble else c.panel,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(Modifier.padding(10.dp)) {
                // 用户发的图：气泡内缩略图回显
                if (m.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (u in m.images) {
                            AsyncImage(
                                model = u,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }
                    if (m.text.isNotBlank() || m.files.isNotEmpty()) Spacer(Modifier.height(6.dp))
                }
                // 用户发的非图片附件：气泡内文件卡片回显
                if (m.files.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (fn in m.files) {
                            Row(
                                Modifier.clip(RoundedCornerShape(6.dp)).background(c.panel)
                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📎", fontSize = 13.sp)
                                Spacer(Modifier.width(5.dp))
                                Text(fn, color = c.dim, fontSize = 13.sp)
                            }
                        }
                    }
                    if (m.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                // 审批卡片：服务端在等一个选择，点了就回执
                val ap = m.approval
                if (ap != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(c.card).padding(10.dp)
                    ) {
                        Text("需要你确认", color = c.warn, fontSize = 13.sp)
                        if (ap.description.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(ap.description, color = c.text, fontSize = 12.sp)
                        }
                        if (ap.command.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                ap.command, color = c.dim, fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        if (ap.resolved.isNotEmpty()) {
                            Text("已选择：" + choiceLabel(ap.resolved), color = c.dim, fontSize = 12.sp)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (ch in ap.choices) {
                                    OutlinedButton(
                                        onClick = { onApproval(m.id, ch) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ) { Text(choiceLabel(ch), color = c.accent, fontSize = 12.sp) }
                                }
                            }
                        }
                    }
                    if (m.text.isNotBlank() || m.subagents.isNotEmpty() || m.usage != null) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                // 子任务进度：delegate_task 派出的子代理，一行一条
                if (m.subagents.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("子任务（" + m.subagents.size + "）", color = c.dim, fontSize = 11.sp)
                        for (s in m.subagents) {
                            val mark = if (s.status == "running") "▶" else "✓"
                            val col = if (s.status == "running") c.accent else c.dim
                            Text(
                                mark + " " + (if (s.goal.isNotEmpty()) s.goal else s.id),
                                color = col, fontSize = 12.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (m.text.isNotBlank() || m.usage != null) Spacer(Modifier.height(6.dp))
                }
                if (m.text.isNotEmpty() || (m.pending && m.trace.isEmpty())) {
                    // 正文走 Markdown 渲染：管道表格画成网格，URL 可点开浏览器；其余按等宽原文
                    RichText(
                        text = if (m.pending && m.text.isEmpty()) "…" else m.text,
                        color = if (m.pending) c.dim else if (isUser) c.userText else c.text,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // 过程轨迹（工具调用等）：默认折叠一行，点开才展开，不占屏幕
                if (m.trace.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (traceOpen) "▾ 过程" else "▸ 过程（" + m.trace.count { it == '\n' } + " 步）",
                        color = c.dim, fontSize = 11.sp,
                        modifier = Modifier.clickable { traceOpen = !traceOpen }
                    )
                    if (traceOpen) {
                        SelectionContainer {
                            Text(
                                m.trace.trim(),
                                color = c.dim, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
                // token 用量与速度：服务端轮末下发，App 自己按耗时算每秒出多少字
                m.usage?.let { u ->
                    Spacer(Modifier.height(4.dp))
                    val speed = if (u.durationMs > 0) {
                        String.format("%.1f", u.output * 1000.0 / u.durationMs)
                    } else ""
                    val parts = mutableListOf<String>()
                    parts.add("入 " + u.input)
                    if (u.cacheRead > 0) parts.add("缓存 " + u.cacheRead)
                    parts.add("出 " + u.output)
                    parts.add("共 " + u.total)
                    if (speed.isNotEmpty()) parts.add(speed + " tok/s")
                    Text(parts.joinToString(" · "), color = c.dim, fontSize = 10.sp)
                }
                if (m.ts > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(TimeFmt.hm(m.ts), color = c.dim, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
fun StatusScreen(vm: ChatViewModel, prefs: Prefs) {
    val c = LocalAppColors.current
    val sections by vm.statusSections.collectAsState()
    val err by vm.statusErr.collectAsState()
    val online by vm.online.collectAsState()
    LaunchedEffect(Unit) { vm.refreshStatus() }
    // 每 5 秒自动刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            vm.refreshStatus()
        }
    }

    Column(Modifier.fillMaxSize()) {
        // 顶部状态条：在线点 + 身份/地址 + 刷新
        Row(
            Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (online) "● 在线" else "● 离线",
                color = if (online) c.ok else c.bad, fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(prefs.profile + " · " + prefs.serverUrl, color = c.dim, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.refreshStatus() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("刷新", fontSize = 12.sp, color = c.accent) }
        }

        if (err.isNotEmpty()) {
            Text(err, color = c.bad, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(14.dp))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp)) {
            for (s in sections) {
                StatusCard(s)
                Spacer(Modifier.height(10.dp))
            }
            Text("每 5 秒自动刷新", color = c.dim, fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp))
        }
    }
}

/** 单个状态分组卡片：标题栏 + 标签值行（标签固定宽，值左对齐成列）。 */
@Composable
fun StatusCard(s: StatusSection) {
    val c = LocalAppColors.current
    Surface(color = c.panel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(s.title, color = c.accent, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            for (item in s.items) {
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(item.label, color = c.dim, fontSize = 12.sp, modifier = Modifier.width(78.dp))
                    Text(item.value, color = c.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(
    vm: ChatViewModel,
    prefs: Prefs,
    mode: String,
    onMode: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    val updateNote by vm.updateNote.collectAsState()
    val pending by vm.pendingUpdate.collectAsState()
    val pct by vm.downloadPct.collectAsState()
    val dtext by vm.downloadText.collectAsState()
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var keepAlive by remember { mutableStateOf(prefs.keepAlive) }
    var showClear by remember { mutableStateOf(false) }
    val cacheText by vm.cacheText.collectAsState()
    LaunchedEffect(Unit) { vm.refreshCache() }
    val vc = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(1)
    }

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState())) {
        Text("服务器地址", color = c.dim, fontSize = 12.sp)
        OutlinedTextField(value = url, onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(c),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = {
                prefs.serverUrl = url
                vm.onProfileChanged(prefs)
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("保存", color = c.accent) }
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { vm.checkUpdate(vc, ctx) }, modifier = Modifier.fillMaxWidth()
        ) { Text("检查更新", color = c.accent) }
        if (updateNote.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(updateNote, color = c.dim, fontSize = 12.sp)
        }
        if (pct in 0..99) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(dtext, color = c.dim, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text("当前版本 " + vc, color = c.dim, fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = c.card)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("后台运行", color = c.text, fontSize = 13.sp)
                Text(
                    if (keepAlive) "任务期间保持连接，通知栏会有一条最小化常驻条目（Android 强制）"
                    else "不起前台服务，无任何常驻通知；任务仍在服务端跑，重开自动拉回结果",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = keepAlive, onCheckedChange = { keepAlive = it; vm.setKeepAlive(it) })
        }
        Spacer(Modifier.height(24.dp))
        HorizontalDivider(color = c.card)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("清理缓存", color = c.text, fontSize = 13.sp)
                Text(
                    "当前占用 " + cacheText + "（待发图片 / 安装包 / 图片缓存）",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = { showClear = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("清理", color = c.accent, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Text("只清临时文件，不动聊天记录与设置", color = c.dim, fontSize = 11.sp)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { prefs.loggedIn = false; onLogout() }, modifier = Modifier.fillMaxWidth()
        ) { Text("退出登录", color = c.bad) }
        Spacer(Modifier.height(10.dp))
        Text("退出后可重新选择身份", color = c.dim, fontSize = 11.sp)
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清理缓存") },
            text = { Text("将清空待发图片、已下载安装包、图片缓存，共 " + cacheText + "。\n聊天记录与设置不受影响。", fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { vm.clearCache(); showClear = false }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) { Text("取消") }
            }
        )
    }

    val info = pending
    if (info != null) {
        AlertDialog(
            onDismissRequest = { vm.dismissUpdate() },
            title = { Text("发现新版本 " + info.versionName) },
            text = {
                Column {
                    Text("安装包大小：" + sizeText(info.size), fontSize = 13.sp)
                    if (info.notes.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.notes, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmUpdate(ctx) }) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUpdate() }) { Text("取消") }
            }
        )
    }
}

/** 审批选项的中文标签（服务端下发的是英文 choice 键）。 */
fun choiceLabel(ch: String): String = when (ch) {
    "once" -> "允许一次"
    "session" -> "本次会话允许"
    "always" -> "始终允许"
    "deny" -> "拒绝"
    else -> ch
}

fun sizeText(b: Long): String = when {
    b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
    b >= 1024L -> String.format("%.0f KB", b / 1024.0)
    b > 0 -> b.toString() + " B"
    else -> "未知"
}

/**
 * 原生 EditText 输入框。
 * Compose TextField 在部分输入法下会丢失「快捷输入符号」（全角标点/符号候选）的提交
 * （issuetracker 373743376 一类问题，1.6.x 仍复现）。改用 Android 原生 EditText 通过
 * AndroidView 承载，输入法走系统原生 InputConnection，符号提交可靠。
 */
@Composable
fun NativeChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    hintText: String,
    modifier: Modifier = Modifier,
    minLines: Int = 2,
    maxLines: Int = 8,
    fill: Boolean = false,
) {
    val c = LocalAppColors.current
    // TextWatcher 在 factory 里只挂一次，必须经 rememberUpdatedState 拿到最新回调，
    // 否则后续重组的新回调永远不生效（输入内容回传的是旧闭包）。
    val onChange by rememberUpdatedState(onValueChange)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                hint = hintText
                applyNativeInputColors(this, c)
                textSize = 15f
                isSingleLine = false
                this.minLines = minLines
                this.maxLines = maxLines
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setPadding(30, 16, 30, 16)
                if (fill) {
                    // 全屏编辑：占满可用高度，内容超出时框内滚动（不再被截断）
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    isVerticalScrollBarEnabled = true
                    setTextIsSelectable(true)
                    scrollBarStyle = android.view.View.SCROLLBARS_INSIDE_INSET
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        onChange(s?.toString() ?: "")
                    }
                })
            }
        },
        update = { et ->
            // 主题可能已切换：仅在配色真的变了时重刷，否则每次按键重建 drawable 会拖慢长文本输入
            applyNativeInputColors(et, c)
            // 长度先比，避免长文本时每次重组都整串 toString 分配
            val ed = et.text
            if (ed == null || ed.length != value.length || ed.toString() != value) {
                et.setText(value)
                et.setSelection(value.length)
            }
        },
    )
}

/** 原生 EditText 的描边/文字/提示颜色统一按当前配色刷新（配色未变则直接跳过）。 */
private fun applyNativeInputColors(et: EditText, c: AppColors) {
    val key = c.dim.toArgb() * 31 + c.text.toArgb()
    if (et.tag == key) return
    et.tag = key
    et.background = GradientDrawable().apply {
        setColor(android.graphics.Color.TRANSPARENT)
        setStroke(2, c.dim.toArgb())
        cornerRadius = 24f
    }
    et.setHintTextColor(c.dim.toArgb())
    et.setTextColor(c.text.toArgb())
}