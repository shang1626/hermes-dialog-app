package com.hermesapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
fun ChatScreen(
    vm: ChatViewModel,
    prefs: Prefs,
    input: TextFieldValue,
    onInput: (TextFieldValue) -> Unit,
) {
    val c = LocalAppColors.current
    val msgs by vm.messages.collectAsState()
    val busy by vm.busy.collectAsState()
    val note by vm.retryNote.collectAsState()
    var fullscreen by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current

    // 末尾放一个 1dp 占位项，永远滚到它 = 永远贴底（正文增长也能跟上）
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.text, msgs.lastOrNull()?.pending) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size)
    }

    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { focus.clearFocus() })
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
            items(msgs) { m -> Bubble(m) }
            item { Spacer(Modifier.height(1.dp)) }
        }
        Row(
            Modifier.fillMaxWidth().background(c.panel).padding(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(Modifier.weight(1f)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInput,
                    placeholder = { Text("发消息…", color = c.dim) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Default
                    ),
                    colors = fieldColors(c)
                )
                // 输入框内右下角的小全屏按钮，无边框
                Text(
                    "⛶",
                    fontSize = 14.sp,
                    color = c.dim,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 8.dp)
                        .clickable { fullscreen = true }
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
                        val t = input.text.trim()
                        if (t.isNotEmpty()) { vm.send(t); onInput(TextFieldValue("")) }
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("发送", color = c.accent, fontSize = 13.sp) }
            }
        }
    }

    if (fullscreen) {
        FullScreenInput(
            initial = input,
            onCancel = { fullscreen = false },
            onDone = { v -> onInput(v); fullscreen = false },
        )
    }
}

@Composable
fun FullScreenInput(
    initial: TextFieldValue,
    onCancel: () -> Unit,
    onDone: (TextFieldValue) -> Unit,
) {
    val c = LocalAppColors.current
    var v by remember { mutableStateOf(initial) }
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
            OutlinedTextField(
                value = v,
                onValueChange = { v = it },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                placeholder = { Text("在此输入…", color = c.dim) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Default
                ),
                colors = fieldColors(c)
            )
        }
    }
}

@Composable
fun Bubble(m: Msg) {
    val c = LocalAppColors.current
    val isUser = m.role == "user"
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
                SelectionContainer {
                    Text(
                        text = if (m.pending && m.text.isEmpty()) "…" else m.text,
                        color = if (m.pending) c.dim else if (isUser) c.userText else c.text,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
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
    var profile by remember { mutableStateOf(prefs.profile) }
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
        Spacer(Modifier.height(14.dp))
        Text("对话身份", color = c.dim, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProfileBtn("friend", Modifier.weight(1f)) { profile = "friend" }
            ProfileBtn("default", Modifier.weight(1f)) { profile = "default" }
        }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = {
                prefs.serverUrl = url
                prefs.profile = profile
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
