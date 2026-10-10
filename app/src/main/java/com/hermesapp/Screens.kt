package com.hermesapp

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.app.Activity
import android.content.Context
import android.content.Intent
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import java.io.File
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
    val pend by vm.pendingImages.collectAsStateWithLifecycle()
    val note by vm.imageNote.collectAsStateWithLifecycle()
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

    val quote by vm.quoteTarget.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        // 「正在播放别的会话的语音」提示条：多任务排队时轮到的可能是另一个会话，
        // 光靠听分辨不出是谁的——这里显式标出来，并给「进入」「跳过」两个出口。
        val curSid by vm.currentId.collectAsStateWithLifecycle()
        val pSess by StreamVoicePlayer.playingSession.collectAsStateWithLifecycle()
        val pOwner = pSess
        if (pOwner != null && pOwner.first.isNotEmpty() && pOwner.first != curSid) {
            Row(
                Modifier.fillMaxWidth()
                    .background(c.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = c.accent,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    "正在播放：" + pOwner.second + " 的语音",
                    color = c.text, fontSize = T.sub, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text(
                    "进入", color = c.accent, fontSize = T.sub,
                    modifier = Modifier
                        .clickable { vm.switchSession(pOwner.first) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                Text(
                    "跳过", color = c.dim, fontSize = T.sub,
                    modifier = Modifier
                        .clickable { vm.skipVoice() }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        // 子任务汇总面板已挪进顶部栏本身（App.kt 的 TopBar），不在这里再放一份。
        //
        // 待处理卡片置顶提示：审批/澄清的卡片原来是嵌在助手气泡里的，长对话时埋在中间
        // 得滚半天才看得到（用户报「长文本上面看不到」）。这里在消息列表之上钉一条醒目
        // 提示，不管滚到哪都在眼前；点一下跳到那张卡片。没有待处理时不占任何高度。
        val pendingAct by vm.pendingAction.collectAsStateWithLifecycle()
        val pa = pendingAct
        if (pa != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(c.warn.copy(alpha = 0.14f))
                    .clickable { vm.requestScrollToMsg(pa.msgId) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("●", color = c.warn, fontSize = T.cap)
                Spacer(Modifier.width(7.dp))
                Column(Modifier.weight(1f)) {
                    Text(pa.title, color = c.warn, fontSize = T.sub, fontWeight = FontWeight.Bold)
                    if (pa.summary.isNotEmpty()) {
                        Text(
                            pa.summary.replace(Regex("\\s+"), " ").trim().let {
                                if (it.length > 50) it.take(50) + "…" else it
                            },
                            color = c.text, fontSize = T.cap, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
                Text("查看 ›", color = c.accent, fontSize = T.sub)
            }
        }
        MessageList(vm, Modifier.weight(1f))
        // 引用条：长按气泡选「引用」后出现，点 × 取消。发送时把片段拼在正文前。
        // 取一份本地快照再判空：委托属性（by collectAsState）不能被智能转换，
        // 老写法在 if 里用 !! 二次读同一个状态，状态一旦变空就是空指针闪退。
        val quoteBar = quote
        if (quoteBar != null) {
            Row(
                Modifier.fillMaxWidth().background(c.card).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.width(3.dp).height(28.dp)
                        .background(c.accent, RoundedCornerShape(2.dp))
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("引用" + (if (quoteBar.role == "user") "我的消息" else "助手消息"),
                        color = c.accent, fontSize = T.cap)
                    Text(
                        quoteBar.text.replace(Regex("\\s+"), " ").trim().let {
                            if (it.isEmpty()) "[图片或附件]" else if (it.length > 60) it.take(60) + "…" else it
                        },
                        color = c.dim, fontSize = T.sub, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(
                    "×", color = c.dim, fontSize = T.title,
                    modifier = Modifier.clickable { vm.clearQuote() }.padding(horizontal = 4.dp)
                )
            }
        }
        // 待发送附件：图片显缩略图、其他显文件卡片，右上角 × 可单删
        if (pend.isNotEmpty() || note.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 10.dp, vertical = 6.dp)) {
                if (note.isNotEmpty()) {
                    Text(note, color = c.warn, fontSize = T.sub)
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
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(Rad.chip))
                                )
                            } else {
                                // 非图片：显示文件名 + 类型角标的卡片
                                Box(
                                    Modifier.fillMaxSize().clip(RoundedCornerShape(Rad.chip))
                                        .background(c.panel)
                                ) {
                                    Text(
                                        p.file.name.takeLast(14),
                                        color = c.dim, fontSize = T.micro,
                                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 3.dp)
                                    )
                                }
                            }
                            Text(
                                "×", color = Color.White, fontSize = T.sub,
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
            onPasteImage = { vm.addImage(ctx, it) },
        )
    }

    if (askVision) {
        AlertDialog(
            onDismissRequest = { askVision = false },
            title = { Text("当前模型不支持图片") },
            text = { Text("要把这几张图自动转成文字描述发过去吗？转文字后模型能读懂图里内容，但原始图片不会保留。", fontSize = T.body) },
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

    // 子任务进度面板：点气泡里的子任务行打开。数据由 vm 在刷（运行中每 3 秒一次），
    // 关掉即停；拿不到内容时面板里照实写「读不到」，不假装有进度。
    val subDetail by vm.subDetail.collectAsStateWithLifecycle()
    val sd = subDetail
    if (sd != null) SubagentDetailDialog(vm, sd)
}

/** 消息列表独立成 composable：打字时它不参与重组，长对话滑动也顺。 */
@Composable
fun MessageList(vm: ChatViewModel, modifier: Modifier = Modifier) {
    val c = LocalAppColors.current
    val msgs by vm.messages.collectAsStateWithLifecycle()
    val note by vm.retryNote.collectAsStateWithLifecycle()
    val searchOn by vm.searchActive.collectAsStateWithLifecycle()
    val q by vm.searchQuery.collectAsStateWithLifecycle()
    val hits by vm.searchIds.collectAsStateWithLifecycle()
    val hitIdx by vm.searchIdx.collectAsStateWithLifecycle()
    val rcMenu by vm.receiptMenu.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val focus = LocalFocusManager.current
    val ctx = LocalContext.current
    val view = LocalView.current
    // 变化即重建各气泡的 SelectionContainer：用来取消文本选中（点空白/点正文时 +1）。
    var selReset by remember { mutableStateOf(0) }

    // 末尾放一个 1dp 占位项，永远滚到它 = 永远贴底（正文增长也能跟上）
    //
    // 关键优化：打开 App / 切会话时，消息是一次性从 0 加载到几百条的，若用
    // animateScrollToItem 会从第 0 项一路动画滚到末尾——300 条逐帧滚，明显卡顿。
    // 改为：同一会话里「末尾追加了一条」（size 恰好 +1）才用平滑动画，其余
    // （首次加载、切会话、批量合并）一律 scrollToItem 瞬间到底，无逐帧滚动。
    var lastCount by remember { mutableStateOf(-1) }
    var lastFirstId by remember { mutableStateOf(0L) }
    // 「是否自动跟随到底部」。默认跟随；只有用户**自己手动**往上翻、并把视口停在半路时
    // 才暂停跟随，一旦回到（或接近）底部立刻恢复。
    //
    // 为什么换掉旧的 `!listState.canScrollForward` 守卫：新消息作为新项插到列表末尾时，
    // 视口还没跟上，`canScrollForward` 在下一帧就已经变成 true（下方还有可滚内容），于是
    // 「新消息到达」被误判成「用户在翻历史」→ 贴底被跳过 → 用户必须手动滑（用户报障：
    // 接收的新消息不自动聚焦）。改用「滚动停止那一刻视口是否在底部」判定：追加新项本身
    // 不产生滚动事件，因此不会误判；用户手动往上翻会先进入 isScrollInProgress，停下时
    // 不在底部 → 暂停跟随，语义正确。
    val atBottomNow: () -> Boolean = {
        val li = listState.layoutInfo
        val last = li.visibleItemsInfo.lastOrNull()
        last == null || last.index >= li.totalItemsCount - 1
    }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { inProgress ->
            if (!inProgress) follow = atBottomNow()
        }
    }
    LaunchedEffect(msgs.size, msgs.lastOrNull()?.id, msgs.lastOrNull()?.text, msgs.lastOrNull()?.pending) {
        if (msgs.isEmpty() || searchOn) return@LaunchedEffect
        val firstId = msgs.firstOrNull()?.id ?: 0L
        val sameConv = firstId == lastFirstId
        // 首次加载 / 切会话：无条件瞬间到底（不用动画，避免几百条逐帧滚）。其余情况：
        // 用户没往上翻（follow）时跟随到底——末尾恰好追加一条用平滑动画，批量合并瞬间到底。
        if (!sameConv) {
            listState.scrollToItem(msgs.size)
            follow = true
        } else if (follow) {
            if (msgs.size == lastCount + 1) listState.animateScrollToItem(msgs.size)
            else listState.scrollToItem(msgs.size)
        }
        lastCount = msgs.size
        lastFirstId = firstId
    }

    // 用户主动发消息 → 无条件滚到最新一条。单独一条通道，不受上面那个
    // 「流式期间别把翻历史的用户拽回底部」守卫（!canScrollForward）影响：
    // 自发消息是明确的「看最新」意图，哪怕此前往上翻过历史也必须贴底。
    val bottomTick by vm.scrollBottomTick.collectAsStateWithLifecycle()
    LaunchedEffect(bottomTick) {
        if (bottomTick <= 0) return@LaunchedEffect
        follow = true
        listState.animateScrollToItem(msgs.size)
    }

    // 跳到命中：当前命中项一变就滚到那条消息（搜索时自动贴底让位）。
    LaunchedEffect(hitIdx, hits) {
        val id = hits.getOrNull(hitIdx) ?: return@LaunchedEffect
        val pos = msgs.indexOfFirst { it.id == id }
        if (pos >= 0) listState.animateScrollToItem(pos)
    }

    // 顶部「待处理」提示点了「查看」：滚到那张卡片所在的消息，消费掉请求。
    // 单独一条通道，不复用搜索跳转（那条会把搜索栏弹出来）。
    val scrollReq by vm.scrollToMsg.collectAsStateWithLifecycle()
    LaunchedEffect(scrollReq) {
        val id = scrollReq ?: return@LaunchedEffect
        val pos = msgs.indexOfFirst { it.id == id }
        if (pos >= 0) listState.animateScrollToItem(pos)
        vm.consumeScrollToMsg()
    }

    Column(
        modifier.fillMaxWidth().pointerInput(Unit) {
            detectTapGestures(onTap = {
                // 点消息区/空白处：取消文本选中 + 清焦点并立即收起软键盘
                selReset++
                focus.clearFocus()
                (ctx as? Activity)?.currentFocus?.clearFocus()
                val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(view.windowToken, 0)
            })
        }
    ) {
        if (note.isNotEmpty()) {
            Text(note, color = c.warn, fontSize = T.sub,
                modifier = Modifier.fillMaxWidth().background(c.panel).padding(8.dp))
        }
        // 归档入口已移到侧边栏（DrawerPanel）——本会话的超 300 条老消息入口不再占对话窗口顶部。
        // 会话内搜索栏：输入即搜（去抖），显示「第几/共几」，上下跳、× 收起
        if (searchOn) {
            val total = hits.size
            Row(
                Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = q,
                    onValueChange = { vm.setSearchQuery(it) },
                    singleLine = true,
                    placeholder = { Text("搜索本会话", fontSize = T.body) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        q.isBlank() -> ""
                        total == 0 -> "无结果"
                        else -> (hitIdx + 1).toString() + "/" + total
                    },
                    color = c.dim, fontSize = 12.sp
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    "↑", color = c.accent, fontSize = T.title,
                    modifier = Modifier.clickable { vm.searchNavigate(-1) }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Text(
                    "↓", color = c.accent, fontSize = T.title,
                    modifier = Modifier.clickable { vm.searchNavigate(1) }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Text(
                    "×", color = c.dim, fontSize = T.title,
                    modifier = Modifier.clickable { vm.clearSearch() }.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = G.page),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = G.x3)
        ) {
            items(msgs, key = { it.id }) { m ->
                val hl = hits.getOrNull(hitIdx) == m.id
                Bubble(
                    m, vm::respondApproval, vm::respondClarify,
                    receiptMenuOpen = rcMenu == m.id,
                    onReceiptTap = { vm.openReceiptMenu(it) },
                    onConfirmReceipt = { vm.confirmReceipt(it) },
                    onResendReceipt = { vm.resendReceipt(it) },
                    onAckReceipt = { vm.acknowledgeReceipt(it) },
                    onQuote = { vm.setQuote(it) },
                    onCancelQueued = { vm.cancelQueued(it) },
                    onEditQueued = { vm.editQueued(it) },
                    onCardAction = { vm.dispatchCardAction(it, ctx) },
                    highlight = hl,
                    hitQuery = if (hl) q else "",
                    selectionReset = selReset,
                    onClearSelection = { selReset++ },
                )
            }
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
    /** 输入框里粘贴了图片（剪贴板/输入法）：交给 vm.addImage 收进待发附件。 */
    onPasteImage: (android.net.Uri) -> Unit = {},
) {
    val c = LocalAppColors.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val queued by vm.queuedCount.collectAsStateWithLifecycle()
    val paused by vm.queuedPaused.collectAsStateWithLifecycle()
    val editText by vm.queuedEdit.collectAsStateWithLifecycle()
    // 编辑排队消息：正文回填输入框，取走即清。
    LaunchedEffect(editText) {
        val e = editText
        if (e != null) { onInput(e); vm.clearQueuedEdit() }
    }
    val input = inputState.value
    Row(
        Modifier.fillMaxWidth().background(c.panel).padding(horizontal = G.x3, vertical = G.x2),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(Modifier.weight(1f)) {
            NativeChatInput(
                value = input,
                onValueChange = onInput,
                hintText = "发消息…",
                modifier = Modifier.fillMaxWidth(),
                onPasteImage = onPasteImage,
            )
            // 全屏 + 图片：并排的小无边框图标，压在输入框右下角
            Row(
                Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 无彩色 emoji，用单色描边图标（跟随主题前景色）
                Icon(
                    Icons.Outlined.Photo,
                    contentDescription = "发送图片",
                    tint = c.dim,
                    modifier = Modifier.size(24.dp).clickable { onPickImages() }.padding(horizontal = 3.dp)
                )
                Spacer(Modifier.width(G.x1))
                Icon(
                    Icons.Outlined.AttachFile,
                    contentDescription = "发送文件",
                    tint = c.dim,
                    modifier = Modifier.size(24.dp).clickable { onPickFiles() }.padding(horizontal = 3.dp)
                )
                Spacer(Modifier.width(G.x1))
                Icon(
                    Icons.Outlined.Fullscreen,
                    contentDescription = "全屏编辑",
                    tint = c.dim,
                    modifier = Modifier.size(24.dp).clickable { onFullscreen() }.padding(horizontal = 3.dp)
                )
            }
        }
        Spacer(Modifier.width(G.x2))
        // 忙时也能发：本会话在跑就排队，等这轮结束自动发出去（不再把输入框锁死）。
        if (queued > 0) {
            Text(if (paused) "待发 " + queued else "排队 " + queued, color = c.warn, fontSize = T.micro)
            Spacer(Modifier.width(G.x2))
        }
        // 主操作：实心强调色胶囊，一眼看得到「发送」
        Button(
            onClick = {
                val t = input.trim()
                if (t.isNotEmpty() || vm.pendingImages.value.isNotEmpty()) {
                    vm.send(t); onInput("")
                }
            },
            shape = RoundedCornerShape(Rad.pill),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (busy) c.cardAlt else c.accent,
                contentColor = if (busy) c.warn else c.onAccent,
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(if (busy) "排队" else "发送", fontSize = T.sub, fontWeight = T.bold)
        }
        if (busy) {
            Spacer(Modifier.width(G.x2))
            // 插话：把这句注入本轮（与「排队」不同——排队是下一轮才发）。
            OutlinedButton(
                onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty()) { vm.steerCurrent(t); onInput("") }
                },
                shape = RoundedCornerShape(Rad.pill),
                border = BorderStroke(1.dp, c.border),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("插话", color = c.accent, fontSize = T.sub) }
            Spacer(Modifier.width(G.x2))
            OutlinedButton(
                onClick = { vm.stop() },
                shape = RoundedCornerShape(Rad.pill),
                border = BorderStroke(1.dp, c.border),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("停止", color = c.bad, fontSize = T.sub) }
        } else if (paused && queued > 0) {
            Spacer(Modifier.width(G.x2))
            OutlinedButton(
                onClick = { vm.resumeQueue() },
                shape = RoundedCornerShape(Rad.pill),
                border = BorderStroke(1.dp, c.border),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) { Text("继续", color = c.ok, fontSize = T.sub) }
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
                    shape = RoundedCornerShape(Rad.pill),
                ) { Text("取消", fontSize = T.sub, color = c.dim) }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { onDone(v) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(Rad.pill),
                ) { Text("完成", fontSize = T.sub, color = c.accent) }
            }
            Spacer(Modifier.height(G.x3))
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

/**
 * 耗时格式化：1 分钟以内按秒显示（保留一位小数），到 1 分钟起改按分钟显示。
 * 45.2s -> 「45.2s」；60s -> 「1.0分」；90s -> 「1.5分」；3分20秒 -> 「3.3分」。
 */
private fun fmtDuration(ms: Long): String {
    if (ms < 60_000L) return String.format("%.1f", ms / 1000.0) + "s"
    return String.format("%.1f", ms / 60_000.0) + "分"
}

/**
 * 进行中的实时耗时：每秒重算一次并跳动显示，回复到达后该组件不再渲染。
 * 只读 startedAt，不碰任何状态机；就算一直没结束也只是每秒刷一个文本，开销可忽略。
 */
/**
 * 当前界面是否处于「前台可见」状态。
 *
 * 为什么需要：Compose 的 LaunchedEffect 在 App 退到后台时**不会**被取消（组合还在），
 * 于是状态页 5 秒轮询、日志 2 秒轮询会一直跑——白耗电、白发包。
 * 这里订阅生命周期，把「是否 RESUMED」暴露成 State，循环按它开关。
 */
@Composable
private fun isResumedState(): State<Boolean> {
    val owner = LocalLifecycleOwner.current
    val st = remember { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            st.value = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return st
}

@Composable
private fun LiveElapsed(startedAt: Long, color: Color) {
    val resumed by isResumedState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt, resumed) {
        while (resumed) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val ms = (now - startedAt).coerceAtLeast(0L)
    Text("耗时 " + fmtDuration(ms), color = color, fontSize = T.micro)
}

/** 子任务进度里「多久以前」的文案。 */
private fun subAgo(ms: Long): String = when {
    ms < 5_000L -> "刚刚"
    ms < 60_000L -> (ms / 1000).toString() + " 秒前"
    ms < 3_600_000L -> (ms / 60_000).toString() + " 分钟前"
    else -> (ms / 3_600_000L).toString() + " 小时前"
}

/**
 * 子任务进度一句话。
 *
 * 步数来自子代理自己的会话（tool_call_count），运行中每 4 秒刷一次。为什么不在推送里拿：
 * 网关那条 run 事件流只转发 subagent.start / subagent.complete，中间的 subagent.tool
 * 与 subagent.progress 被当「界面噪音」丢掉了，所以运行中的进度只能这么读（详见
 * ChatViewModel.ensureSubagentSweep）。
 */
private fun subagentProgressText(s: SubagentLine, now: Long): String {
    val parts = mutableListOf<String>()
    if (s.steps > 0) parts.add("已 " + s.steps + " 步")
    when {
        s.status == "running" -> {
            if (s.startedAt > 0) parts.add("已跑 " + fmtDuration(now - s.startedAt))
            parts.add(if (s.seenAt > 0) subAgo(now - s.seenAt) + "取的进度" else "正在取进度…")
        }
        s.endedAt > 0 && s.startedAt > 0 -> {
            parts.add("耗时 " + fmtDuration(s.endedAt - s.startedAt))
            if (s.tokens > 0) parts.add("子代理 " + s.tokens + " tokens")
        }
    }
    return parts.joinToString(" · ")
}

/**
 * 顶部栏里那个「子任务」小标：挤在顶栏同一行（☰ 对话 … 搜索 ●在线）里，
 * 点一下在下面展开明细，再点收起。
 *
 * 为什么做成行内小标：用户明确要求「位置在顶部栏」并且「挤进去」，同时
 * 搜索/在线两项原位不动 —— 所以它插在标题右边，右侧靠 Spacer(weight) 顶住，
 * 那两项不会被推走。没有子任务时它整个不出现，顶栏和以前一模一样。
 */
@Composable
fun SubagentChip(vm: ChatViewModel, open: Boolean, onToggle: () -> Unit) {
    val c = LocalAppColors.current
    val subs by vm.sessionSubagents.collectAsStateWithLifecycle()
    if (subs.isEmpty()) return
    val running = subs.count { it.status == "running" }
    Text(
        "子任务 " + subs.size + (if (running > 0) " · " + running + " 跑" else "") +
            (if (open) " ▴" else " ▾"),
        color = if (running > 0) c.accent else c.dim,
        fontSize = T.sub,
        modifier = Modifier.clip(RoundedCornerShape(6.dp))
            .clickable { onToggle() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * 展开后的子任务明细：挂在顶栏那一行下面（属于 TopBar 组件自身）。
 *
 * 默认折叠、点小标才展开：有子任务的会话一进去不该被面板吃掉一截屏幕。
 * 长会话里子任务会一直累积，默认只列最近 5 条，其余靠「看全部」。
 */
@Composable
fun SubagentList(vm: ChatViewModel) {
    val c = LocalAppColors.current
    val subs by vm.sessionSubagents.collectAsStateWithLifecycle()
    if (subs.isEmpty()) return
    val running = subs.count { it.status == "running" }
    val cap = 5
    var showAll by remember { mutableStateOf(false) }
    val rows = if (showAll) subs else subs.take(cap)
    // 「N 秒前取的进度」的心跳：只在还有子任务在跑时才跳（进度本身 4 秒刷一回）。
    val tick = remember { mutableStateOf(System.currentTimeMillis()) }
    val resumedSub by isResumedState()
    LaunchedEffect(running > 0, resumedSub) {
        while (running > 0 && resumedSub) {
            tick.value = System.currentTimeMillis()
            delay(3000L)
        }
    }
    val curId by vm.currentId.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 10.dp, vertical = 4.dp)) {
        if (subs.size > cap) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                // 收起靠顶栏那个小标（再点一下），这里只放「看全部」切换。
                Text(
                    if (showAll) "只显示最近" else "看全部（" + subs.size + "）",
                    color = c.accent, fontSize = T.cap,
                    modifier = Modifier.clickable { showAll = !showAll }.padding(horizontal = 4.dp),
                )
            }
        }
        for (s in rows) {
            SubagentRow(
                s, tick,
                onStop = { vm.stopSubagent(curId, s.id) },
                onClick = { vm.openSubagentDetail(curId, s.id) },
            )
        }
        if (!showAll && subs.size > cap) {
            Text(
                "还有 " + (subs.size - cap) + " 条更早的（点「看全部」）",
                color = c.dim, fontSize = T.micro,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
    HorizontalDivider(color = c.card)
}

/** 一条子任务：目标 + 实时进度 + 入口提示，点整行打开进度面板。 */
@Composable
private fun SubagentRow(
    s: SubagentLine,
    tick: State<Long>,
    onStop: () -> Unit,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    val mark = when (s.status) {
        "running" -> "▶"
        "completed" -> "✓"
        "ended" -> "■"
        else -> "✗"
    }
    val col = when (s.status) {
        "running" -> c.accent
        "completed" -> c.ok
        "ended" -> c.warn
        else -> c.bad
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 3.dp, horizontal = 4.dp)
    ) {
        Text(
            mark + " " + (if (s.goal.isNotEmpty()) s.goal else s.id),
            color = col, fontSize = T.sub, maxLines = 2, overflow = TextOverflow.Ellipsis
        )
        val prog = subagentProgressText(s, tick.value)
        if (prog.isNotEmpty()) {
            Text(prog, color = c.dim, fontSize = T.micro, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("查看进度 ▸", color = c.accent, fontSize = T.micro)
            if (s.status == "running") {
                Spacer(Modifier.width(12.dp))
                // 停止是协作式的：子代理到下一个步骤边界才停，不是立即杀进程。
                Text(
                    "停止", color = c.bad, fontSize = T.micro,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp))
                        .clickable { onStop() }.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 子任务进度面板：这个子代理做了什么、每一步的参数与结果。 */
@Composable
fun SubagentDetailDialog(vm: ChatViewModel, d: SubagentDetail) {
    val c = LocalAppColors.current
    val st = when (d.status) {
        "running" -> "运行中"
        "completed" -> "已完成"
        "ended" -> "已结束（没等到完成事件）"
        else -> d.status
    }
    val bits = mutableListOf<String>()
    if (d.steps.isNotEmpty()) bits.add("已 " + d.steps.size + " 步")
    if (d.startedAt > 0) {
        val end = if (d.endedAt > 0) d.endedAt else System.currentTimeMillis()
        bits.add((if (d.endedAt > 0) "耗时 " else "已跑 ") + fmtDuration(end - d.startedAt))
    }
    if (d.tokens > 0) bits.add("子代理 " + d.tokens + " tokens")
    AlertDialog(
        onDismissRequest = { vm.closeSubagentDetail() },
        title = { Text("子任务进度", color = c.text, fontSize = 15.sp) },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(d.goal, color = c.text, fontSize = T.sub)
                Spacer(Modifier.height(6.dp))
                Text(
                    (listOf(st) + bits).joinToString(" · "),
                    color = if (d.status == "running") c.accent else c.dim, fontSize = T.cap,
                )
                if (d.note.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(d.note, color = c.warn, fontSize = T.cap)
                }
                Spacer(Modifier.height(G.x2))
                if (d.steps.isEmpty()) {
                    Text("还没有步骤可显示（子代理还没调用工具，或这条会话读不到）", color = c.dim, fontSize = T.sub)
                } else {
                    for (stp in d.steps) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text(
                                stp.n.toString() + ". " + stp.tool +
                                    (if (stp.arg.isNotEmpty()) "  " + stp.arg else ""),
                                color = c.accent, fontSize = T.sub,
                            )
                            if (stp.result.isNotEmpty()) {
                                Text("    ↳ " + stp.result, color = c.dim, fontSize = T.cap)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.closeSubagentDetail() }) {
                Text("关闭", color = c.accent, fontSize = T.body)
            }
        },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Bubble(
    m: Msg,
    onApproval: (Long, String) -> Unit = { _, _ -> },
    onClarify: (Long, String) -> Unit = { _, _ -> },
    /** 该条的投递处置按钮是否展开（由 vm.receiptMenu 控制）。 */
    receiptMenuOpen: Boolean = false,
    onReceiptTap: (Long) -> Unit = {},
    onConfirmReceipt: (Long) -> Unit = {},
    onResendReceipt: (Long) -> Unit = {},
    /** 「知道了，不重发」：收掉「不确定」角标与提示，不动网络。 */
    onAckReceipt: (Long) -> Unit = {},
    /** 撤回一条还没发出去的排队消息（从队列摘掉、收掉气泡）。 */
    onCancelQueued: (Long) -> Unit = {},
    /** 编辑一条排队消息：从队列摘掉，正文回填到输入框。 */
    onEditQueued: (Long) -> Unit = {},
    /** 长按气泡选「引用」：把这整条交给 ViewModel。 */
    onQuote: (Msg) -> Unit = {},
    /** 富卡片按钮点击：交给 ViewModel 分发（发消息 / 开链接）。 */
    onCardAction: (CardAction) -> Unit = {},
    /** 该条是当前搜索命中：加一圈强调边框。 */
    highlight: Boolean = false,
    /** 命中词：正文里加黄底（空表示不高亮）。 */
    hitQuery: String = "",
    selectionReset: Int = 0,
    onClearSelection: () -> Unit = {},
) {
    val c = LocalAppColors.current
    val isUser = m.role == "user"
    // 气泡形状按「发送者」分档：靠发送者那一侧圆角小、另一侧大 —— 一眼分得清谁说的。
    val shape = if (isUser) RoundedCornerShape(Rad.bubble, Rad.bubble, Rad.bubbleTight, Rad.bubble)
    else RoundedCornerShape(Rad.bubble, Rad.bubble, Rad.bubble, Rad.bubbleTight)
    var traceOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) c.userBubble else c.bubble,
            shape = shape,
            border = BorderStroke(1.dp, if (isUser) c.userBorder else c.bubbleBorder),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .combinedClickable(
                    onClick = { onClearSelection() },
                    onLongClick = { menuOpen = true },
                )
                .then(
                    if (highlight) Modifier.border(1.5.dp, c.accent, shape)
                    else Modifier
                )
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                // 引用片段：这条消息是引用发送时，先显示被引的一行（左侧竖条 + 灰字）
                if (m.quote.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.chip))
                            .background(c.cardAlt).padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Box(Modifier.width(3.dp).height(28.dp).background(c.accent, RoundedCornerShape(Rad.pill)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            m.quote, color = c.dim, fontSize = T.cap, lineHeight = 17.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(G.x2))
                }
                // 用户发的图：气泡内缩略图回显
                if (m.images.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (u in m.images) {
                            LocalImageView(
                                uri = u,
                                modifier = Modifier.size(72.dp).clip(RoundedCornerShape(Rad.chip))
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
                                Modifier.clip(RoundedCornerShape(Rad.chip)).background(c.cardAlt)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📎", fontSize = T.sub)
                                Spacer(Modifier.width(6.dp))
                                Text(fn, color = c.dim, fontSize = T.sub)
                            }
                        }
                    }
                    if (m.text.isNotBlank()) Spacer(Modifier.height(6.dp))
                }
                // 审批卡片：服务端在等一个选择，点了就回执
                val ap = m.approval
                if (ap != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.card))
                            .background(c.cardAlt).padding(G.x3)
                    ) {
                        Text("需要你确认", color = c.warn, fontSize = T.sub, fontWeight = T.bold)
                        if (ap.description.isNotEmpty()) {
                            Spacer(Modifier.height(G.x1))
                            Text(ap.description, color = c.text, fontSize = T.sub, lineHeight = T.lineSub)
                        }
                        if (ap.command.isNotEmpty()) {
                            Spacer(Modifier.height(G.x1))
                            Text(
                                ap.command, color = c.dim, fontSize = T.cap,
                                fontFamily = FontFamily.Monospace, maxLines = 6,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(G.x2))
                        if (ap.resolved.isNotEmpty()) {
                            Text("已选择：" + choiceLabel(ap.resolved), color = c.dim, fontSize = T.sub)
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (ch in ap.choices) {
                                    OutlinedButton(
                                        onClick = { onApproval(m.id, ch) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(Rad.pill),
                                    ) { Text(choiceLabel(ch), color = c.accent, fontSize = T.sub) }
                                }
                            }
                        }
                    }
                    if (m.text.isNotBlank() || m.subagents.isNotEmpty() || m.usage != null) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                // 澄清卡片：我问你「选 A 还是 B」，点选项直接回执
                val cl = m.clarify
                if (cl != null) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.chip))
                            .background(c.card).padding(10.dp)
                    ) {
                        Text("需要你选一下", color = c.warn, fontSize = T.body)
                        Spacer(Modifier.height(4.dp))
                        Text(cl.question, color = c.text, fontSize = T.sub)
                        Spacer(Modifier.height(G.x2))
                        if (cl.resolved.isNotEmpty()) {
                            Text("已选择：" + cl.resolved, color = c.dim, fontSize = T.sub)
                        } else if (cl.choices.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (ch in cl.choices) {
                                    OutlinedButton(
                                        onClick = { onClarify(m.id, ch) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(Rad.pill),
                                    ) { Text(ch, color = c.accent, fontSize = T.sub) }
                                }
                            }
                        } else {
                            Text("请在下方输入框回复", color = c.dim, fontSize = T.sub)
                        }
                    }
                    if (m.text.isNotBlank() || m.subagents.isNotEmpty() || m.usage != null) {
                        Spacer(Modifier.height(6.dp))
                    }
                }
                // 子任务进度不在这里逐条显示了：已统一汇总到对话窗口顶部的面板
                // （顶栏的 SubagentChip），消息再长也在同一处看。
                if (m.text.isNotEmpty() || (m.pending && m.trace.isEmpty())) {
                    // 正文走 Markdown 渲染：管道表格画成网格，URL 可点开浏览器；其余按等宽原文
                    RichText(
                        text = if (m.pending && m.text.isEmpty()) "…" else m.text,
                        color = if (m.pending) c.dim else if (isUser) c.userText else c.text,
                        fontSize = T.chat,
                        modifier = Modifier.fillMaxWidth(),
                        hitQuery = hitQuery,
                        selectionReset = selectionReset,
                        onClearSelection = onClearSelection,
                        onCardAction = { a -> onCardAction(a) },
                    )
                }
                // 过程轨迹（工具调用等）：默认折叠一行，点开才展开，不占屏幕
                if (m.trace.isNotEmpty()) {
                    Spacer(Modifier.height(G.x2))
                    Text(
                        if (traceOpen) "▾ 过程" else "▸ 过程（" + m.trace.count { it == '\n' } + " 步）",
                        color = c.dim, fontSize = T.cap,
                        modifier = Modifier.clickable { traceOpen = !traceOpen }
                    )
                    if (traceOpen) {
                        key(selectionReset) {
                            SelectionContainer {
                                Text(
                                    m.trace.trim(),
                                    color = c.dim, fontSize = T.cap, lineHeight = 17.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
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
                    // 本轮耗时：从发起任务到收到回复的墙钟时间。
                    if (u.durationMs > 0) {
                        parts.add("耗时 " + fmtDuration(u.durationMs))
                    }
                    if (speed.isNotEmpty()) parts.add(speed + " tok/s")
                    Text(parts.joinToString(" · "), color = c.faint, fontSize = T.micro, lineHeight = 15.sp)
                }
                // 用户消息投递状态：转圈 / 单勾 / 黄问号 / 红叹号。点黄问号或红叹号展开处置。
                // 状态标记与发送时间并排同一行（不再各自独占一行）；告警说明接在时间后面。
                val rc = m.receipt
                val actionable = rc != null &&
                    (rc.status == Receipt.UNCERTAIN || rc.status == Receipt.FAILED ||
                        rc.status == Receipt.QUEUED || rc.status == Receipt.NOT_SENT)
                val mark = when (rc?.status) {
                    Receipt.SENDING -> "◌"
                    Receipt.QUEUED -> "⋯"
                    Receipt.NOT_SENT -> "↑"
                    Receipt.STEER_FAILED -> "✗"
                    Receipt.ACCEPTED -> "✓"
                    Receipt.UNCERTAIN -> "?"
                    Receipt.FAILED -> "!"
                    Receipt.ACKED -> ""
                    else -> ""
                }
                val markCol = when (rc?.status) {
                    Receipt.UNCERTAIN, Receipt.QUEUED, Receipt.NOT_SENT -> c.warn
                    Receipt.FAILED, Receipt.STEER_FAILED -> c.bad
                    else -> c.dim
                }
                // 语音附件的迷你图标：跟时间并排同一行，不单独占一行。
                // 流式模式下正文里没有附件，按钮改看消息的 runId（服务端长期留档）。
                val voiceTarget = remember(m.text) { VoicePlayer.audioTarget(m.text) }
                val replayRunId = m.runId
                if (rc != null || m.ts > 0 || voiceTarget.isNotEmpty() || replayRunId.isNotEmpty() || m.steer) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.then(
                            if (actionable) Modifier.clickable { onReceiptTap(m.id) } else Modifier
                        )
                    ) {
                        // 插话小标：跟发送时间并排一行（原来它独占气泡顶部一行，白占高度）。
                        if (m.steer) {
                            Row(
                                Modifier.clip(RoundedCornerShape(Rad.pill))
                                    .background(c.accent.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) { Text("插话", color = c.accent, fontSize = T.micro) }
                            Spacer(Modifier.width(6.dp))
                        }
                        if (rc != null) {
                            Text(mark, color = markCol, fontSize = T.cap)
                            Spacer(Modifier.width(5.dp))
                        }
                        if (m.ts > 0) Text(TimeFmt.hm(m.ts), color = c.faint, fontSize = T.micro)
                        // 进行中：从气泡创建起实时跳动显示耗时（回复到达后由用量行显示最终值）。
                        if (m.pending && m.startedAt > 0) {
                            Spacer(Modifier.width(6.dp))
                            LiveElapsed(m.startedAt, c.accent)
                        }
                        // 同一条消息只留一个语音按钮：优先按 runId 的重播按钮（服务端长期留档，
                        // 本机有缓存则零网络），没有 runId 的老消息才回落正文里的内联附件按钮。
                        if (replayRunId.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            // inlineTarget：完成语音自动播报走 VoicePlayer（内联附件），
                            // 传给重播按钮让它也能停掉正在自动播报的这条（2026-10-10 报障）。
                            VoiceReplayMiniButton(replayRunId, inlineTarget = voiceTarget)
                            // 正在播这条时额外点一个喇叭：播放按钮只在「手动重播」时
                            // 变停止方块，自动播报（流式）期间它一直是三角，
                            // 用户看不出队列轮到哪条了。
                            VoiceSpeakerMark(replayRunId)
                        } else if (voiceTarget.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            VoiceMiniButton(voiceTarget)
                        }
                        if (rc != null) {
                            val tip = when (rc.status) {
                                Receipt.QUEUED -> "排队中，本轮结束后自动发送（点这里可撤回或编辑）"
                                Receipt.NOT_SENT -> "未发送（App 重启后没自动发出），点这里重发或忽略"
                                Receipt.STEER_FAILED -> if (rc.note.isNotEmpty()) rc.note else "插话没送达"
                                Receipt.UNCERTAIN -> "发送结果不确定，点这里处理"
                                Receipt.FAILED -> if (rc.note.isNotEmpty()) rc.note else "发送失败，点这里重发"
                                else -> ""
                            }
                            if (tip.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tip,
                                    color = if (rc.status == Receipt.FAILED ||
                                        rc.status == Receipt.STEER_FAILED) c.bad else c.warn,
                                    fontSize = T.micro,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                    if (receiptMenuOpen && actionable) {
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (rc?.status == Receipt.QUEUED) {
                                // 排队中：还没发出去，给「撤回 / 编辑」两个出口。
                                OutlinedButton(
                                    onClick = { onCancelQueued(m.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(Rad.pill),
                                ) { Text("撤回", color = c.bad, fontSize = T.sub) }
                                OutlinedButton(
                                    onClick = { onEditQueued(m.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(Rad.pill),
                                ) { Text("编辑", color = c.accent, fontSize = T.sub) }
                            } else if (rc?.status == Receipt.NOT_SENT) {
                                // 重启后归位的「未发送」：这条不会再自动发出，交给用户决定。
                                OutlinedButton(
                                    onClick = { onResendReceipt(m.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(Rad.pill),
                                ) { Text("重新发送", color = c.accent, fontSize = T.sub) }
                                OutlinedButton(
                                    onClick = { onAckReceipt(m.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(Rad.pill),
                                ) { Text("知道了", color = c.dim, fontSize = T.sub) }
                            } else {
                                OutlinedButton(
                                    onClick = { onConfirmReceipt(m.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(Rad.pill),
                                ) { Text("确认送达", color = c.accent, fontSize = T.sub) }
                                OutlinedButton(
                                    onClick = { onResendReceipt(m.id) },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    shape = RoundedCornerShape(Rad.pill),
                                ) { Text("重新发送", color = c.accent, fontSize = T.sub) }
                                // 「不确定」时给一个不重发的出口：看过就算了，不必拿这句话去赌会不会发两遍。
                                if (rc?.status == Receipt.UNCERTAIN) {
                                    OutlinedButton(
                                        onClick = { onAckReceipt(m.id) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                        shape = RoundedCornerShape(Rad.pill),
                                    ) { Text("知道了", color = c.dim, fontSize = T.sub) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // 长按菜单：引用 / 复制正文。用轻量 Dialog 承载，点外面即关。
    if (menuOpen) {
        val ctx2 = LocalContext.current
        Dialog(onDismissRequest = { menuOpen = false }) {
            Surface(color = c.panel, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        "引用回复", color = c.text, fontSize = T.body,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { menuOpen = false; onQuote(m) }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                    Text(
                        "复制正文", color = c.text, fontSize = T.body,
                        modifier = Modifier.fillMaxWidth()
                            .clickable {
                                menuOpen = false
                                val cm = ctx2.getSystemService(Context.CLIPBOARD_SERVICE)
                                    as? android.content.ClipboardManager
                                cm?.setPrimaryClip(
                                    android.content.ClipData.newPlainText("hermes", m.text)
                                )
                            }
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusScreen(vm: ChatViewModel, prefs: Prefs) {
    val c = LocalAppColors.current
    val sections by vm.statusSections.collectAsStateWithLifecycle()
    val metrics by vm.statusMetrics.collectAsStateWithLifecycle()
    val hero by vm.statusHero.collectAsStateWithLifecycle()
    val err by vm.statusErr.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    // 上次刷新时刻：给「每 5 秒自动刷新」配一个会动的秒数，一眼看出数据是新的。
    var refreshedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { vm.refreshStatus() }
    // 每 5 秒自动刷新（仅前台；退后台停，省电省包）
    val resumedStatus by isResumedState()
    LaunchedEffect(resumedStatus) {
        while (resumedStatus) {
            delay(5000)
            vm.refreshStatus()
        }
    }
    // 状态一到就记一次刷新时间（数据变了才会触发重组，用 metrics/sections 变化当信号）。
    LaunchedEffect(metrics, sections) { refreshedAt = System.currentTimeMillis() }

    Column(Modifier.fillMaxSize()) {
        // 顶部状态条：在线点 + 身份/地址 + 刷新
        Row(
            Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (online) "● 在线" else "● 离线",
                color = if (online) c.ok else c.bad, fontSize = T.body)
            Spacer(Modifier.width(10.dp))
            Text(prefs.profile + " · " + prefs.serverUrl, color = c.dim, fontSize = T.cap,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { vm.refreshStatus() },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(Rad.pill),
                border = BorderStroke(1.dp, c.border),
            ) { Text("刷新", fontSize = T.sub, color = c.accent) }
        }

        if (err.isNotEmpty()) {
            Text(err, color = c.bad, fontSize = T.sub,
                modifier = Modifier.fillMaxWidth().padding(G.page))
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(G.page)) {
            // ① 顶部概览卡：状态徽章 + 模型 + 运行时长 + 在跑任务数，全部行内标签。
            hero?.let { StatusHeroCard(it) }

            // ② 动态进度条：CPU / 内存 / Swap / 磁盘 / 负载，数值到条会平滑推进。
            if (metrics.isNotEmpty()) {
                Surface(
                    color = c.panel,
                    shape = RoundedCornerShape(Rad.card),
                    border = BorderStroke(1.dp, c.borderSoft),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(G.x4)) {
                        Text("资源使用", color = c.accent, fontSize = T.sub, fontWeight = T.bold)
                        Spacer(Modifier.height(G.x3))
                        for (m in metrics) {
                            StatusMetricBar(m)
                            Spacer(Modifier.height(G.x3))
                        }
                    }
                }
                Spacer(Modifier.height(G.x3))
            }

            // ③ 原有明细分组（网关、CPU、内存、磁盘、运行…）。
            for (s in sections) {
                StatusCard(s)
                Spacer(Modifier.height(G.x3))
            }
            // 心跳指示：点每 5 秒闪一下，说明自动刷新真的在跑。
            RefreshHeartbeat(refreshedAt)
        }
    }
}

/**
 * 「每 5 秒自动刷新」下面那颗心跳点：自己带 1 秒 ticker，
 * 把重组限制在这一小块里（整屏状态数据不跟着每秒重组）。
 */
@Composable
private fun RefreshHeartbeat(refreshedAt: Long) {
    val c = LocalAppColors.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val resumedHb by isResumedState()
    LaunchedEffect(resumedHb) {
        while (resumedHb) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val fresh = refreshedAt > 0 && (now - refreshedAt) < 2000L
    val dot by animateFloatAsState(
        targetValue = if (fresh) 1f else 0.35f,
        animationSpec = tween(400), label = "dot"
    )
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(c.ok.copy(alpha = dot)))
        Spacer(Modifier.width(G.x2))
        Text(
            "每 5 秒自动刷新" + if (refreshedAt > 0) " · 上次 " + TimeFmt.hhmmss(refreshedAt) else "",
            color = c.faint, fontSize = T.micro
        )
    }
}

/** 顶部概览卡：状态徽章 + 模型 + 运行时长 + 任务数（同类信息同行、行内标签）。 */
@Composable
fun StatusHeroCard(h: StatusHero) {
    val c = LocalAppColors.current
    Surface(
        color = c.panel,
        shape = RoundedCornerShape(Rad.card),
        border = BorderStroke(1.dp, c.borderSoft),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(G.x4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (h.ok) c.ok else c.bad))
                Spacer(Modifier.width(G.x2))
                Text(h.statusText, color = if (h.ok) c.ok else c.bad, fontSize = T.body,
                    fontWeight = T.heavy)
                Spacer(Modifier.weight(1f))
                Text("PID " + h.pid, color = c.faint, fontSize = T.micro)
            }
            Spacer(Modifier.height(G.x3))
            Text("版本：" + h.version, color = c.text, fontSize = T.sub,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.uptimeText.isNotEmpty()) {
                Spacer(Modifier.height(G.x1))
                Text("已运行：" + h.uptimeText, color = c.dim, fontSize = T.sub)
            }
            Spacer(Modifier.height(G.x1))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("活跃任务：" + h.activeRuns, color = if (h.activeRuns > 0) c.accent else c.dim, fontSize = T.sub)
                Spacer(Modifier.width(G.x4))
                Text("子任务：" + h.delegations, color = if (h.delegations > 0) c.accent else c.dim, fontSize = T.sub)
            }
        }
    }
}

/**
 * 一根进度条：标签 + 数值在上一行，下面一条会平滑推进的细条。
 * 颜色分级：<60% 用主色，60~85% 用警示色，≥85% 用危险色。
 * 负载条按「核数=100%」折算，超核就是满条。
 */
@Composable
fun StatusMetricBar(m: StatusMetric) {
    val c = LocalAppColors.current
    val frac by animateFloatAsState(
        targetValue = (m.percent / 100.0).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(700), label = m.key
    )
    val color = when {
        m.percent >= 85.0 -> c.bad
        m.percent >= 60.0 -> c.warn
        else -> c.accent
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(m.label, color = c.text, fontSize = T.sub)
            Spacer(Modifier.weight(1f))
            Text(m.valueText, color = color, fontSize = T.sub, fontWeight = T.bold)
        }
        Spacer(Modifier.height(G.x2))
        // 轨道 + 填充；填充宽度按百分比动画推进。
        Box(
            Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(Rad.pill))
                .background(c.cardAlt)
        ) {
            Box(
                Modifier.fillMaxWidth(frac).fillMaxHeight()
                    .clip(RoundedCornerShape(Rad.pill)).background(color)
            )
        }
        if (m.subText.isNotEmpty()) {
            Spacer(Modifier.height(G.x1))
            Text(m.subText, color = c.faint, fontSize = T.cap,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 单个状态分组卡片：标题栏 + 标签值行（标签固定宽，值左对齐成列）。 */
@Composable
fun StatusCard(s: StatusSection) {
    val c = LocalAppColors.current
    Surface(
        color = c.panel,
        shape = RoundedCornerShape(Rad.card),
        border = BorderStroke(1.dp, c.borderSoft),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(G.x4)) {
            Text(s.title, color = c.accent, fontSize = T.sub, fontWeight = T.bold)
            Spacer(Modifier.height(G.x3))
            for (item in s.items) {
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text(item.label, color = c.dim, fontSize = T.cap, modifier = Modifier.width(82.dp))
                    Text(item.value, color = c.text, fontSize = T.sub, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * 定时任务页：列出服务端 /api/jobs 的任务，支持暂停 / 恢复 / 立即执行。
 * 只看得到本机器人的任务（两个机器人各用各的 key，服务端按档案隔离）。
 */
@Composable
fun JobsScreen(vm: ChatViewModel, prefs: Prefs) {
    val c = LocalAppColors.current
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val err by vm.jobsErr.collectAsStateWithLifecycle()
    val note by vm.jobsNote.collectAsStateWithLifecycle()
    val reports by vm.inbox.collectAsStateWithLifecycle()
    val unread by vm.inboxUnread.collectAsStateWithLifecycle()
    val inboxErr by vm.inboxErr.collectAsStateWithLifecycle()
    // 口径（是否含已停用）与界面开关同源：ViewModel 持有，界面只读与切换。
    // 暂停某任务后 ViewModel 会把口径切到「含已停用」，开关文字随之变成「隐藏已停用」，
    // 不会再出现「开关说没显示、列表里却有」的错位。
    val showDisabled by vm.jobsIncludeDisabled.collectAsStateWithLifecycle()
    var clearConfirm by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CronReport?>(null) }
    LaunchedEffect(Unit) {
        vm.refreshJobs(showDisabled)
        // 进这一页顺手拉一次收件箱（App 走 api_server 通道收不到推送，产出只能来拉）。
        vm.refreshInbox(notifyNew = false)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(c.panel).padding(horizontal = G.page, vertical = G.x3),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("定时任务", color = c.text, fontSize = T.body, fontWeight = T.bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (showDisabled) "隐藏已停用" else "显示已停用",
                color = c.accent, fontSize = T.sub,
                modifier = Modifier.clickable { vm.refreshJobs(!showDisabled) }
            )
            Spacer(Modifier.width(G.x3))
            OutlinedButton(
                onClick = { vm.refreshJobs(showDisabled) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                shape = RoundedCornerShape(Rad.pill),
                border = BorderStroke(1.dp, c.border),
            ) { Text("刷新", fontSize = T.sub, color = c.accent) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderSoft))
        if (note.isNotEmpty()) {
            Text(note, color = c.ok, fontSize = T.sub,
                modifier = Modifier.fillMaxWidth().padding(horizontal = G.page, vertical = G.x2))
        }
        if (err.isNotEmpty()) {
            Text(err, color = c.bad, fontSize = T.sub,
                modifier = Modifier.fillMaxWidth().padding(G.page))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(G.page)) {
            // 收件箱：定时任务的产出。App 走 api_server 通道，服务端推不过来
            // （supports_async_delivery=False），产出在服务端留档、这里拉出来看。
            if (reports.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "收件箱" + (if (unread > 0) "（" + unread + " 条未读）" else "（" + reports.size + " 条）"),
                        color = if (unread > 0) c.accent else c.text,
                        fontSize = T.sub, fontWeight = T.bold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (unread > 0) {
                        Text(
                            "全部已读", color = c.accent, fontSize = T.sub,
                            modifier = Modifier.clickable { vm.ackInbox(all = true) },
                        )
                        Spacer(Modifier.width(G.x3))
                    }
                    Text(
                        "清空", color = c.bad, fontSize = T.sub,
                        modifier = Modifier.clickable { clearConfirm = true },
                    )
                }
                Spacer(Modifier.height(G.x2))
                for (r in reports) {
                    CronReportRow(
                        r,
                        onOpen = { vm.openCronReport(r) },
                        onLongPress = { pendingDelete = r },
                        onDelete = { pendingDelete = r },
                    )
                    Spacer(Modifier.height(G.x2))
                }
                Spacer(Modifier.height(G.x3))
            }
            if (inboxErr.isNotEmpty()) {
                Text(inboxErr, color = c.bad, fontSize = T.sub, modifier = Modifier.padding(vertical = G.x2))
            }
            if (jobs.isEmpty() && err.isEmpty()) {
                Text("（没有定时任务）", color = c.dim, fontSize = T.sub,
                    modifier = Modifier.padding(vertical = G.x3))
            }
            for (j in jobs) {
                JobCard(j) { action -> vm.jobAction(j.id, action, j.zhName.ifEmpty { j.name }) }
                Spacer(Modifier.height(G.x3))
            }
            Text("数据来自服务端 /api/jobs，只列出本机器人的任务",
                color = c.faint, fontSize = T.micro,
                modifier = Modifier.fillMaxWidth().padding(top = G.x1))
        }
    }

    // 产出全文：点收件箱某一条弹出。
    val viewing by vm.cronReport.collectAsStateWithLifecycle()
    val vr = viewing
    if (vr != null) CronReportDialog(vm, vr)

    // 清空全部：不可恢复，先二次确认。
    if (clearConfirm) {
        AlertDialog(
            onDismissRequest = { clearConfirm = false },
            title = { Text("清空收件箱？", color = c.text, fontSize = T.body, fontWeight = T.bold) },
            text = { Text("所有定时任务产出都会被删除，删了找不回来。", color = c.dim, fontSize = T.sub) },
            confirmButton = {
                TextButton(onClick = { clearConfirm = false; vm.deleteInbox(all = true) }) {
                    Text("清空", color = c.bad, fontSize = T.body, fontWeight = T.bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirm = false }) { Text("取消", color = c.dim, fontSize = T.body) }
            },
            containerColor = c.panel,
        )
    }
    // 单条删除：长按某条弹出，二次确认。
    val pd = pendingDelete
    if (pd != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条产出？", color = c.text, fontSize = T.body, fontWeight = T.bold) },
            text = { Text(jobDisplayName(pd.jobName, pd.jobId) + " 的这条产出会被删除，删了找不回来。", color = c.dim, fontSize = T.sub) },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; vm.deleteInbox(listOf(pd.id)) }) {
                    Text("删除", color = c.bad, fontSize = T.body, fontWeight = T.bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消", color = c.dim, fontSize = T.body) }
            },
            containerColor = c.panel,
        )
    }
}

/**
 * 收件箱一条：任务名 + 时间 + 正文首行；未读带红点。点开看全文，右侧「删除」可直接删。
 *
 * 「删除」为什么必须显式摆出来（2026-10-10 用户报「收件箱消息又没法删除了」）：
 * 原来只有两个入口——长按整行、或点开全文弹窗底部的「删除」。用户手机上长按不被识别
 * （press 被当成 tap → 只是打开全文），而弹窗底部的按钮在长正文时又不容易够到，结果
 * 服务端访问日志里「一条删除请求都没有」：不是删除接口坏，是**入口没被触发**。
 * 现在把删除做成行内可见控件，点击走同一个二次确认。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CronReportRow(
    r: CronReport,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Rad.card)).background(c.card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f)
                .combinedClickable(onClick = { onOpen() }, onLongClick = { onLongPress() })
                .padding(G.x3)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.unread) {
                    Box(Modifier.size(8.dp).background(c.bad, CircleShape))
                    Spacer(Modifier.width(G.x2))
                }
                Text(
                    (if (r.failed) "✗ " else "✓ ") + jobDisplayName(r.jobName, r.jobId),
                    color = if (r.failed) c.bad else c.text, fontSize = T.sub,
                    fontWeight = T.bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(TimeFmt.isoToBj(r.at), color = c.faint, fontSize = T.micro)
            }
            Spacer(Modifier.height(G.x1))
            Text(
                r.body.replace(Regex("\\s+"), " ").trim().take(90),
                color = c.dim, fontSize = T.cap, lineHeight = 17.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        // 行内删除：描边小字，点击弹二次确认（与长按同一条路径）。
        Text(
            "删除", color = c.bad, fontSize = T.sub,
            modifier = Modifier
                .padding(horizontal = G.x3, vertical = G.x4)
                .clickable { onDelete() },
        )
    }
}

/** 定时任务产出全文（收件箱条目）。 */
@Composable
fun CronReportDialog(vm: ChatViewModel, r: CronReport) {
    val c = LocalAppColors.current
    AlertDialog(
        onDismissRequest = { vm.closeCronReport() },
        title = {
            Text(
                (if (r.failed) "定时任务失败 · " else "定时任务产出 · ") + jobDisplayName(r.jobName, r.jobId),
                color = if (r.failed) c.bad else c.text, fontSize = T.body, fontWeight = T.bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(TimeFmt.isoToBj(r.at) + "  ·  " + r.jobId, color = c.dim, fontSize = T.cap)
                Spacer(Modifier.height(G.x2))
                if (r.body.isBlank()) {
                    Text("（这条没有正文）", color = c.dim, fontSize = T.sub)
                } else {
                    RichText(r.body, color = c.text, fontSize = T.sub)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.closeCronReport() }) {
                Text("关闭", color = c.accent, fontSize = T.body)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.deleteInbox(listOf(r.id)) }) {
                Text("删除", color = c.bad, fontSize = T.body)
            }
        },
    )
}

/** 执行记录状态翻译（latest_execution.status），与 ViewModel 内那份保持一致。 */
private fun jobZhExecStatusLocal(s: String): String = when (s) {
    "claimed" -> "已排入队列"
    "running" -> "执行中"
    "completed" -> "已完成"
    "failed" -> "失败"
    "unknown" -> "状态未知"
    else -> s
}

/** 单条定时任务卡片：名称 + 排期 + 上次/下次 + 三个动作按钮。 */
@Composable
fun JobCard(j: JobItem, onAction: (String) -> Unit) {
    val c = LocalAppColors.current
    val stateColor = when {
        !j.enabled -> c.dim
        j.lastOk -> c.ok
        j.lastStatus.isEmpty() -> c.dim
        else -> c.warn
    }
    val title = j.zhName.ifEmpty { j.name }
    // 详情默认收起：卡片默认只占三行（标题 / 一行摘要 / 按钮），要点开才展开细节。
    // 删除不可恢复，必须先过一道二次确认对话框。
    var open by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        color = c.panel,
        shape = RoundedCornerShape(Rad.card),
        border = BorderStroke(1.dp, c.borderSoft),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(G.x4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title, color = c.text, fontSize = T.body, fontWeight = T.bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(G.x2))
                // 状态做成小胶囊：一眼分清「在跑 / 已停用 / 上次失败」，不再是一枚裸字。
                Row(
                    Modifier.clip(RoundedCornerShape(Rad.pill))
                        .background(stateColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        if (!j.enabled) "已停用" else j.state,
                        color = stateColor, fontSize = T.micro, fontWeight = T.bold
                    )
                }
            }
            // ── 默认四行（2026-10-10 第二轮反馈：压成一行会被截断）──
            // 第 1 行标题+状态胶囊；第 2 行「下次 · 上次」；第 3 行「排期 · 已跑」；
            // 第 4 行按钮（小描边 chip，不再用 Material 大按钮——它带 48dp 触控热区，
            // 视觉上明显偏大）。细节仍在「详情」里，展开才占空间。
            val line2 = buildList {
                if (j.nextRun.isNotEmpty()) add("下次 " + j.nextRun)
                if (j.lastRun.isNotEmpty()) {
                    add("上次 " + j.lastRun + (if (j.lastStatus.isNotEmpty()) " " + j.lastStatus else ""))
                }
            }.joinToString("   ·   ")
            val line3 = buildList {
                if (j.schedule.isNotEmpty()) add("排期 " + j.schedule)
                if (j.repeatDone > 0) add("已跑 " + j.repeatDone + " 次")
            }.joinToString("   ·   ")
            if (line2.isNotEmpty()) {
                Spacer(Modifier.height(G.x1))
                Text(
                    line2, color = c.dim, fontSize = T.micro,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (line3.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    line3, color = c.dim, fontSize = T.micro,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(G.x2))
            Row(horizontalArrangement = Arrangement.spacedBy(G.x2)) {
                JobChip(if (j.enabled) "暂停" else "恢复", c.accent) {
                    onAction(if (j.enabled) "pause" else "resume")
                }
                JobChip("立即执行", c.accent) { onAction("run") }
                JobChip(if (open) "收起 ˄" else "详情 ˅", c.accent) { open = !open }
            }

            // ── 详情（2026-10-10 新增「任务描述详情」）──
            // 任务到底让 agent 干什么（服务端 prompt 原文）、投递方式、模型、创建时间、
            // 已跑次数、最近执行明细、投递失败原因，最后是删除入口。
            if (open) {
                Spacer(Modifier.height(G.x2))
                HorizontalDivider(color = c.card)
                Spacer(Modifier.height(G.x2))
                if (j.note.isNotEmpty()) {
                    Text(j.note, color = c.dim, fontSize = T.cap, lineHeight = 18.sp)
                    Spacer(Modifier.height(G.x1))
                }
                if (j.schedule.isNotEmpty()) JobMeta("排期", j.schedule)
                if (j.deliver.isNotEmpty()) JobMeta("投递", j.deliver)
                if (j.model.isNotEmpty()) JobMeta("模型", j.model)
                if (j.createdAt.isNotEmpty()) JobMeta("创建", j.createdAt)
                if (j.repeatDone > 0) JobMeta("已跑", j.repeatDone.toString() + " 次")
                // 最近一次执行明细：状态 + 耗时 / 失败原因。
                // 这一段回答的是「刚才点『立即执行』到底跑了哪条、跑成没成」。
                if (j.execStatus.isNotEmpty()) {
                    Spacer(Modifier.height(G.x1))
                    val execCol = when (j.execStatus) {
                        "completed" -> c.ok
                        "failed", "unknown" -> c.bad
                        "running", "claimed" -> c.accent
                        else -> c.dim
                    }
                    val line = StringBuilder("最近执行  ")
                    line.append(jobZhExecStatusLocal(j.execStatus))
                    if (j.execDuration.isNotEmpty()) line.append(" · 耗时 ").append(j.execDuration)
                    Text(line.toString(), color = execCol, fontSize = T.cap)
                    if (j.execError.isNotEmpty()) {
                        Text(
                            "原因  " + j.execError.replace(Regex("\\s+"), " ").trim().take(160),
                            color = c.bad, fontSize = T.cap, lineHeight = 17.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // 投递失败：任务跑成功了，但结果没送到（如微信会话没准备好）。
                if (j.deliveryError.isNotEmpty()) {
                    Spacer(Modifier.height(G.x1))
                    Text(
                        "投递失败  " + j.deliveryError.replace(Regex("\\s+"), " ").trim().take(160),
                        color = c.bad, fontSize = T.cap, lineHeight = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis
                    )
                }
                if (j.desc.isNotEmpty()) {
                    Spacer(Modifier.height(G.x2))
                    Text("任务描述", color = c.faint, fontSize = T.cap, fontWeight = T.bold)
                    Spacer(Modifier.height(G.x1))
                    // 描述原文可能几千字：限高 + 卡片内滚动，别把卡片撑成一面墙。
                    Text(
                        j.desc.replace(Regex("\\s+"), " ").trim(),
                        color = c.text, fontSize = T.cap, lineHeight = 18.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                Spacer(Modifier.height(G.x2))
                // 删除任务：不可恢复，描边红字 + 二次确认（用户 2026-10-10 要求新增）。
                // 同样用小 chip —— Material 大按钮带 48dp 热区，视觉上太大。
                JobChip("删除任务", c.bad) { confirmDelete = true }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个定时任务？", color = c.text, fontSize = T.body, fontWeight = T.bold) },
            text = {
                Text(
                    title + " 会被彻底删除，删了找不回来；想留着不跑就先「暂停」。",
                    color = c.dim, fontSize = T.cap, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onAction("delete") }) {
                    Text("删除", color = c.bad, fontSize = T.sub)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("取消", color = c.dim, fontSize = T.sub)
                }
            },
            containerColor = c.panel,
        )
    }
}

/** 任务卡里的一行元信息：标签固定宽（最弱色）+ 值（次弱色），多行对齐成一列。 */
@Composable
private fun JobMeta(label: String, value: String) {
    val c = LocalAppColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = c.faint, fontSize = T.cap, modifier = Modifier.width(46.dp))
        Text(value, color = c.dim, fontSize = T.cap, modifier = Modifier.weight(1f))
    }
}

/**
 * 任务卡上的小操作按钮：描边胶囊 + 最小字号（约 22dp 高）。
 *
 * 为什么不用 Material 的 OutlinedButton：它内置 48dp 最小触控热区，任务卡上三个并排时
 * 视觉上明显偏大（用户 2026-10-10 反馈「按钮太大了」）。这里手工做一个同款描边小 chip，
 * 视觉小一号、仍然有描边（符合「按钮禁实色大块、要描边小按钮」的既有偏好）。
 */
@Composable
private fun JobChip(label: String, color: Color, onClick: () -> Unit) {
    val c = LocalAppColors.current
    val shape = RoundedCornerShape(Rad.pill)
    Box(
        Modifier
            .clip(shape)
            .border(BorderStroke(1.dp, c.border), shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(label, color = color, fontSize = T.micro)
    }
}

/**
 * 把运行日志落成文件并调系统分享面板发出去（发我排查用）。
 * 走「复制全文」不占剪贴板、不截断，长日志也能整份发过来。
 * 落在 App 私有 exports/（FileProvider 已声明），只读日志，不改任何数据。
 * 失败只弹一行提示，绝不崩。
 */
private fun exportLogFile(ctx: Context) {
    try {
        val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
        val f = File(dir, "run.log")
        f.writeText(AppLog.read(ctx))
        val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Hermes 运行日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(i, "导出运行日志"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            ctx, "导出失败：" + (e.message ?: "?"), android.widget.Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
private fun SectionTitle(text: String) {
    val c = LocalAppColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = G.x1, bottom = G.x1),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(4.dp).height(16.dp).background(c.accent, RoundedCornerShape(Rad.pill)))
        Spacer(Modifier.width(G.x2))
        Text(text, color = c.text, fontSize = T.sub, fontWeight = T.bold)
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
    val updateNote by vm.updateNote.collectAsStateWithLifecycle()
    val pending by vm.pendingUpdate.collectAsStateWithLifecycle()
    val pct by vm.downloadPct.collectAsStateWithLifecycle()
    val dtext by vm.downloadText.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var keepAlive by remember { mutableStateOf(prefs.keepAlive) }
    var notifyDone by remember { mutableStateOf(prefs.notifySessionCompletions) }
    var playVoice by remember { mutableStateOf(prefs.playCompletionVoice) }
    var voiceRate by remember { mutableStateOf(prefs.voiceRate) }
    var showClear by remember { mutableStateOf(false) }
    // 排查诊断区默认收起：运行日志/闪退记录平时用不上，展开才占屏幕。
    var diagOpen by remember { mutableStateOf(false) }
    val cacheText by vm.cacheText.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshCache() }
    val vc = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        }.getOrDefault(1)
    }
    // 版本名（如 2.41）：与 versionCode 一起显示，用户能一眼对上发布的版本号。
    val vName = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
        }.getOrDefault("")
    }

    Column(Modifier.fillMaxSize().padding(G.page).verticalScroll(rememberScrollState())) {
        // ───────── 一、服务器 ─────────
        SectionTitle("服务器")
        Spacer(Modifier.height(G.x2))
        OutlinedTextField(value = url, onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(), colors = fieldColors(c),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done))
        Spacer(Modifier.height(G.x2))
        OutlinedButton(
            onClick = {
                prefs.serverUrl = url
                vm.onProfileChanged(prefs)
            },
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            shape = RoundedCornerShape(Rad.pill),
        ) { Text("保存", color = c.accent, fontSize = T.body) }

        // ── 已登录账号（R20）：凭据按身份存在手机本地，只列**已登录**的账号名（身份由账号决定）──
        // 没凭据的身份不再出现「未登录」占位（按反馈：设置页不应出现未登录项）。
        val accounts = listOf("default", "friend").mapNotNull { p ->
            val cred = prefs.credential(p)
            if (cred.isEmpty()) null
            else Triple(p, cred.substringBefore(':'), prefs.profile == p)
        }
        if (accounts.isNotEmpty()) {
            Spacer(Modifier.height(G.x3))
            Text("已登录账号", color = c.text, fontSize = T.body)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                accounts.forEach { (p, who, cur) ->
                    OutlinedButton(
                        onClick = { if (!cur) { prefs.profile = p; vm.onProfileChanged(prefs) } },
                        enabled = !cur,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(Rad.pill),
                    ) {
                        Text(
                            if (cur) who + "（当前）" else who,
                            color = if (cur) c.dim else c.accent, fontSize = T.sub
                        )
                    }
                }
            }
            if (accounts.size == 1) {
                Spacer(Modifier.height(4.dp))
                Text("要加/换账号：退出登录后用另一个账号登录一次即可", color = c.dim, fontSize = T.cap)
            }
        }

        // ───────── 二、通知与语音 ─────────
        Spacer(Modifier.height(22.dp))
        SectionTitle("通知与语音")
        Spacer(Modifier.height(G.x3))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("后台运行", color = c.text, fontSize = T.body)
                Text(
                    if (keepAlive) "常驻通知栏保持连接（一条静默条目，安卓强制）；关掉开关通知才消失"
                    else "不起前台服务，无任何常驻通知；任务仍在服务端跑，重开自动拉回结果",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = keepAlive, onCheckedChange = { keepAlive = it; vm.setKeepAlive(it) })
        }
        Spacer(Modifier.height(G.x3))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("其它会话完成也提醒", color = c.text, fontSize = T.body)
                Text(
                    if (notifyDone) "别的会话跑完时也弹通知；当前会话的内容就在屏幕上，不重复提醒"
                    else "只在 App 退到后台时提醒；开着 App 看别的会话时那边跑完不响",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = notifyDone, onCheckedChange = {
                notifyDone = it
                vm.setNotifySessionCompletions(it)
                if (it && !keepAlive) { keepAlive = true; vm.setKeepAlive(true) }
            })
        }
        Spacer(Modifier.height(G.x3))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("完成语音播报", color = c.text, fontSize = T.body)
                Text(
                    if (playVoice) "任务跑完时自动把整段回复念给你听（用系统播放器，不额外占内存）"
                    else "任务跑完只弹通知，不念内容",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Switch(checked = playVoice, onCheckedChange = {
                playVoice = it
                vm.setPlayCompletionVoice(it)
            })
        }
        // ── 保活：这台机型（realme/ColorOS）会把后台 App 直接杀掉（真机日志实测一天 8 次）。
        //    任务是跑在服务端的、不会丢，但后台进度与完成提醒会断；下面几步点一次即可显著改善。
        Spacer(Modifier.height(16.dp))
        Text("保活（防被系统回收）", color = c.text, fontSize = T.body)
        Spacer(Modifier.height(4.dp))
        Text(
            "① 点「电池优化白名单」，在弹窗里选「允许」；\n" +
            "② 点「自启动 / 省电设置」进应用详情，把「自启动」打开、省电策略选「允许后台运行」或「无限制」；\n" +
            "③ 最近任务里把本应用的卡片下拉「锁定」（各机型菜单名略有差别）。\n" +
            "做一次长期有效。没做的直接后果：后台任务跑完不提醒、切回来才看到结果。",
            color = c.dim, fontSize = 11.sp
        )
        Spacer(Modifier.height(G.x2))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { openBatteryWhitelist(ctx) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(Rad.pill),
            ) { Text("电池优化白名单", color = c.accent, fontSize = T.body) }
            OutlinedButton(
                onClick = { openAppDetails(ctx) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(Rad.pill),
            ) { Text("自启动 / 省电设置", color = c.accent, fontSize = T.body) }
        }

        Spacer(Modifier.height(G.x3))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("播报语速", color = c.text, fontSize = T.body)
                Text(
                    "播放速度和文件无关，随时可改；下次播报即生效",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (r in listOf(0.75f, 1.0f, 1.25f, 1.5f)) {
                    SpeedBtn(
                        label = if (r == 1.0f) "正常" else r.toString().trimEnd('0').trimEnd('.') + "×",
                        selected = voiceRate == r,
                        onClick = { voiceRate = r; vm.setVoiceRate(r) }
                    )
                }
            }
        }

        // ───────── 三、版本更新 ─────────
        Spacer(Modifier.height(22.dp))
        SectionTitle("版本更新")
        Spacer(Modifier.height(G.x3))
        val hasUpdate by vm.updateBadge.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { vm.checkUpdate(vc, ctx) }, modifier = Modifier.fillMaxWidth()
            ) { Text("检查更新", color = c.accent) }
            // 有新版本时：按钮右上角（边框内）一个小绿点，与抽屉「设置」角标联动
            if (hasUpdate) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 8.dp)
                        .size(7.dp)
                        .background(c.ok, CircleShape)
                )
            }
        }
        if (updateNote.isNotEmpty()) {
            Spacer(Modifier.height(G.x2))
            Text(updateNote, color = c.dim, fontSize = T.sub)
        }
        if (pct in 0..99) {
            Spacer(Modifier.height(G.x3))
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(dtext, color = c.dim, fontSize = T.sub)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (vName.isNotEmpty()) "当前版本 " + vName + "（" + vc + "）" else "当前版本 " + vc,
            color = c.dim, fontSize = 11.sp
        )

        // ───────── 四、存储 ─────────
        Spacer(Modifier.height(22.dp))
        SectionTitle("存储")
        Spacer(Modifier.height(G.x3))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("清理缓存", color = c.text, fontSize = T.body)
                Text(
                    "当前占用 " + cacheText + "（待发图片 / 安装包 / 图片缓存）",
                    color = c.dim, fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(
                onClick = { showClear = true },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                shape = RoundedCornerShape(Rad.pill),
            ) { Text("清理", color = c.accent, fontSize = T.sub) }
        }
        Spacer(Modifier.height(G.x2))
        Text("只清临时文件，不动聊天记录与设置", color = c.dim, fontSize = T.cap)

        // ───────── 五、排查诊断（默认折叠） ─────────
        Spacer(Modifier.height(22.dp))
        SectionTitle("排查诊断")
        Spacer(Modifier.height(6.dp))
        Text(
            if (diagOpen) "▾ 收起（运行日志 / 闪退记录 / 服务故障记录）"
            else "▸ 展开（出问题时才用：运行日志 / 闪退记录 / 服务故障记录）",
            color = c.accent, fontSize = T.sub,
            modifier = Modifier.clickable { diagOpen = !diagOpen }
        )
        if (diagOpen) {
            // 运行日志：连接/重连/发送/收流的关键节点留痕。排查「连不上」时复制全文发出来。
            var logText by remember { mutableStateOf(AppLog.tail(ctx, 300)) }
            val resumedLog by isResumedState()
            LaunchedEffect(resumedLog) {
                while (resumedLog) {
                    delay(2000)
                    logText = AppLog.tail(ctx, 300)
                }
            }
            Spacer(Modifier.height(G.x4))
            Text("运行日志", color = c.text, fontSize = T.body)
            Spacer(Modifier.height(4.dp))
            Text("连接/重连/发送/收流的每一步都记在这里；出问题时点「复制全文」发给我。", color = c.dim, fontSize = T.cap)
            Spacer(Modifier.height(G.x2))
            // 最新在最上面（用户 2026-10-09 反馈：原来最新在底部，想看新日志得往下滑半天）。
            val logShown = remember(logText) {
                if (logText.isEmpty()) "" else logText.split('\n').asReversed().joinToString("\n")
            }
            Text(
                if (logShown.isEmpty()) "（暂无日志）" else logShown,
                color = c.text, fontSize = T.micro,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .background(c.panel, RoundedCornerShape(Rad.chip))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            )
            Spacer(Modifier.height(G.x2))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                            as? android.content.ClipboardManager
                        cm?.setPrimaryClip(
                            android.content.ClipData.newPlainText("hermes-log", AppLog.read(ctx))
                        )
                        android.widget.Toast.makeText(
                            ctx, "已复制，粘贴发给我即可", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(Rad.pill),
                ) { Text("复制全文", color = c.accent, fontSize = T.sub) }
                OutlinedButton(
                    onClick = { exportLogFile(ctx) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(Rad.pill),
                ) { Text("导出文件", color = c.accent, fontSize = T.sub) }
                OutlinedButton(
                    onClick = { vm.uploadDiagNow() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(Rad.pill),
                ) { Text("上报诊断", color = c.accent, fontSize = T.sub) }
                OutlinedButton(
                    onClick = { AppLog.clear(ctx); logText = "" },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(Rad.pill),
                ) { Text("清除", color = c.dim, fontSize = T.sub) }
            }
            Spacer(Modifier.height(6.dp))
            val diagNote by vm.diagNote.collectAsStateWithLifecycle()
            if (diagNote.isNotEmpty()) {
                Text(diagNote, color = c.accent, fontSize = T.cap)
                Spacer(Modifier.height(4.dp))
            }
            Text("「上报诊断」把日志和会话状态直接传给我——只在你点它时才传，不会自动上传、不占流量。", color = c.dim, fontSize = T.cap)

            // 上次闪退记录：崩溃是进程被直接杀掉，只有落到这里才查得动。
            var crashText by remember { mutableStateOf(CrashLog.read(ctx)) }
            if (crashText.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("上次闪退记录", color = c.bad, fontSize = T.body)
                Spacer(Modifier.height(4.dp))
                Text("点「复制全文」发给我，就能定位到出错的代码行。", color = c.dim, fontSize = T.cap)
                Spacer(Modifier.height(G.x2))
                Text(
                    crashText, color = c.text, fontSize = T.micro,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(c.panel, RoundedCornerShape(Rad.chip))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                )
                Spacer(Modifier.height(G.x2))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("hermes-crash", crashText)
                            )
                            android.widget.Toast.makeText(
                                ctx, "已复制，粘贴发给我即可", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(Rad.pill),
                    ) { Text("复制全文", color = c.accent, fontSize = T.sub) }
                    OutlinedButton(
                        onClick = { CrashLog.clear(ctx); crashText = "" },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(Rad.pill),
                    ) { Text("清除", color = c.dim, fontSize = T.sub) }
                }
            }

            // 服务故障记录：前台服务启动失败这类错误被 catch 住了、进程不会死，
            // 所以不会走「上次闪退记录」那条路，但它是闪退的真凶，得单独看。
            var faultText by remember { mutableStateOf(CrashLog.readFault(ctx)) }
            if (faultText.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("服务故障记录", color = c.bad, fontSize = T.body)
                Spacer(Modifier.height(4.dp))
                Text("前台服务启动失败的完整原因，点「复制全文」发给我。", color = c.dim, fontSize = T.cap)
                Spacer(Modifier.height(G.x2))
                Text(
                    faultText, color = c.text, fontSize = T.micro,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .background(c.panel, RoundedCornerShape(Rad.chip))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp)
                )
                Spacer(Modifier.height(G.x2))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE)
                                as? android.content.ClipboardManager
                            cm?.setPrimaryClip(
                                android.content.ClipData.newPlainText("hermes-fault", faultText)
                            )
                            android.widget.Toast.makeText(
                                ctx, "已复制，粘贴发给我即可", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(Rad.pill),
                    ) { Text("复制全文", color = c.accent, fontSize = T.sub) }
                    OutlinedButton(
                        onClick = { CrashLog.clearFault(ctx); faultText = "" },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(Rad.pill),
                    ) { Text("清除", color = c.dim, fontSize = T.sub) }
                }
            }
        }

        // ───────── 退出登录 ─────────
        Spacer(Modifier.height(26.dp))
        HorizontalDivider(color = c.card)
        Spacer(Modifier.height(G.x4))
        OutlinedButton(
            onClick = { prefs.loggedIn = false; onLogout() }, modifier = Modifier.fillMaxWidth()
        ) { Text("退出登录", color = c.bad) }
        Spacer(Modifier.height(G.x3))
        Text("退出后可重新选择身份", color = c.dim, fontSize = T.cap)
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清理缓存") },
            text = { Text("将清空待发图片、已下载安装包、图片缓存，共 " + cacheText + "。\n聊天记录与设置不受影响。", fontSize = T.body) },
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
                    Text("安装包大小：" + sizeText(info.size), fontSize = T.body)
                    if (info.notes.isNotEmpty()) {
                        Spacer(Modifier.height(G.x2))
                        Text(info.notes, fontSize = T.body)
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
    /**
     * 输入框里粘贴了一张图片时回调（含输入法自带的图片粘贴）。
     * 传入 Uri，交给上层加进待发附件；不传则粘贴图片按普通文本处理（保持旧行为）。
     */
    onPasteImage: ((Uri) -> Unit)? = null,
) {
    val c = LocalAppColors.current
    // TextWatcher 在 factory 里只挂一次，必须经 rememberUpdatedState 拿到最新回调，
    // 否则后续重组的新回调永远不生效（输入内容回传的是旧闭包）。
    val onChange by rememberUpdatedState(onValueChange)
    // 粘贴回调同理：只挂一次，读的必须是当前这一份。
    val onPaste by rememberUpdatedState(onPasteImage)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            PasteAwareEditText(context) { onPaste }.apply {
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

/**
 * 支持把剪贴板/输入法里的图片交出来的 EditText。
 *
 * 原生 EditText 粘贴图片只会走两条路，两条都要接：
 *   ① 系统剪贴板里是图片（复制了一张图）→ onTextContextMenuItem 的 paste 分支；
 *   ② 输入法自带的图片提交（如 Gboard 贴图）→ InputConnection.commitContent。
 * 都只把 Uri 交给上层（上层统一走 addImage 拷进沙盒），输入框自身不放图片。
 */
private class PasteAwareEditText(
    context: Context,
    private val onImage: () -> ((Uri) -> Unit)?,
) : EditText(context) {

    override fun onTextContextMenuItem(id: Int): Boolean {
        if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            val clip = cb?.primaryClip
            val uri = clipImageUri(clip)
            if (uri != null) {
                onImage()?.invoke(uri)
                return true
            }
        }
        return super.onTextContextMenuItem(id)
    }

    override fun onCreateInputConnection(outAttrs: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection? {
        // 告诉输入法「这个框能收图片」：Gboard 一类才会把贴图按钮亮出来。
        outAttrs.contentMimeTypes = arrayOf("image/*")
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : android.view.inputmethod.InputConnectionWrapper(base, true) {
            override fun commitContent(
                inputMethod: android.view.inputmethod.InputContentInfo,
                flags: Int,
                opts: android.os.Bundle?,
            ): Boolean {
                val desc = inputMethod.description
                val mime = if (desc != null && desc.mimeTypeCount > 0) desc.getMimeType(0) else null
                val uri = inputMethod.contentUri
                if (mime != null && mime.startsWith("image/") && uri != null) {
                    val handler = onImage()
                    if (handler != null) {
                        handler(uri)
                        return true
                    }
                }
                return super.commitContent(inputMethod, flags, opts)
            }
        }
    }
}

/** 从剪贴板里取一张图片的 Uri（没有图片返回 null）。 */
private fun clipImageUri(clip: android.content.ClipData?): Uri? {
    if (clip == null || clip.itemCount == 0) return null
    for (i in 0 until clip.itemCount) {
        val it = clip.getItemAt(i)
        if (it.uri != null) return it.uri
    }
    return null
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

/**
 * 跳到「忽略电池优化」请求页；机型不支持该直连 action 时退到电池优化设置列表。
 * 为什么需要：realme/ColorOS 等会主动回收后台进程，加入白名单是最有效的自救手段，
 * 但入口在各机型设置里藏得深，这里给一键跳转（2026-10-10）。
 */
private fun openBatteryWhitelist(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        runCatching {
            ctx.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}

/** 打开系统里本应用的详情页（自启动、省电策略、通知权限都在那一页）。 */
private fun openAppDetails(ctx: android.content.Context) {
    runCatching {
        ctx.startActivity(
            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:" + ctx.packageName))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
