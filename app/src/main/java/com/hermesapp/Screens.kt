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
        ActivityResultContracts.PickMultipleVisualMedia(5)
    ) { uris ->
        for (u in uris) vm.addImage(ctx, u)
    }

    Column(Modifier.fillMaxSize()) {
        MessageList(vm, Modifier.weight(1f))
        // 待发送图片：缩略图横排，每张右上角 × 可单删
        if (pend.isNotEmpty() || note.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (note.isNotEmpty()) {
                    Text(note, color = c.warn, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in pend) {
                        Box(Modifier.size(56.dp)) {
                            AsyncImage(
                                model = p.uri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                            )
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
            items(msgs, key = { it.id }) { m -> Bubble(m) }
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
) {
    val c = LocalAppColors.current
    val busy by vm.busy.collectAsState()
    val input = inputState.value
    Row(
        Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        // 图片入口：小描边圆钮
        OutlinedButton(
            onClick = onPickImages,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            shape = RoundedCornerShape(8.dp),
        ) { Text("＋", color = c.accent, fontSize = 14.sp) }
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            NativeChatInput(
                value = input,
                onValueChange = onInput,
                hintText = "发消息…",
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "⛶",
                fontSize = 14.sp,
                color = c.dim,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 10.dp, bottom = 6.dp)
                    .clickable { onFullscreen() }
            )
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
fun Bubble(m: Msg) {
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
                    if (m.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                if (m.text.isNotEmpty() || (m.pending && m.trace.isEmpty())) {
                    SelectionContainer {
                        Text(
                            text = if (m.pending && m.text.isEmpty()) "…" else m.text,
                            color = if (m.pending) c.dim else if (isUser) c.userText else c.text,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
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
    val txt by vm.statusText.collectAsState()
    val online by vm.online.collectAsState()
    LaunchedEffect(Unit) { vm.refreshStatus() }
    // 每 5 秒自动刷新
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000)
            vm.refreshStatus()
        }
    }

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (online) "● 网关在线" else "● 网关离线",
                color = if (online) c.ok else c.bad, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("5 秒自动刷新", color = c.dim, fontSize = 11.sp)
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = { vm.refreshStatus() },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(8.dp),
            ) { Text("刷新", fontSize = 12.sp, color = c.accent) }
        }
        Spacer(Modifier.height(12.dp))
        Text(prefs.profile + " · " + prefs.serverUrl, color = c.dim, fontSize = 11.sp)
        Spacer(Modifier.height(14.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(txt, color = c.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
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
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { prefs.loggedIn = false; onLogout() }, modifier = Modifier.fillMaxWidth()
        ) { Text("退出登录", color = c.bad) }
        Spacer(Modifier.height(10.dp))
        Text("退出后可重新选择身份", color = c.dim, fontSize = 11.sp)
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
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                background = GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    setStroke(2, c.dim.toArgb())
                    cornerRadius = 24f
                }
                hint = hintText
                setHintTextColor(c.dim.toArgb())
                setTextColor(c.text.toArgb())
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
                        onValueChange(s?.toString() ?: "")
                    }
                })
            }
        },
        update = { et ->
            val cur = et.text?.toString() ?: ""
            if (cur != value) {
                et.setText(value)
                et.setSelection(value.length)
            }
        },
    )
}