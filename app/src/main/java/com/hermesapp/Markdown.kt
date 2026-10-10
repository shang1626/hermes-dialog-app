package com.hermesapp

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Download

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import android.net.Uri
import android.widget.Toast
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 极简 Markdown 渲染：只认两类——管道表格 + URL 链接。
 * 不引第三方库（离线构建稳、体积零增加），其余内容一律按等宽纯文本原样显示。
 */


private val URL_RE = Regex("https?://[^\\s<>()\\[\\]{}\"'\\uFF0C\\u3002\\u3001\\uFF09\\u3011]+")

fun linkAnnotated(raw: String, link: Color): AnnotatedString {
    val sb = AnnotatedString.Builder()
    var i = 0
    for (m in URL_RE.findAll(raw)) {
        sb.append(cleanInline(raw.substring(i, m.range.first)))
        sb.pushStringAnnotation("URL", m.value)
        sb.pushStyle(SpanStyle(color = link))
        sb.append(m.value)
        sb.pop()
        sb.pop()
        i = m.range.last + 1
    }
    sb.append(cleanInline(raw.substring(i)))
    return sb.toAnnotatedString()
}

/**
 * 在已链接标注的文本上，把搜索命中词再加一层黄底 SpanStyle。
 * 链接的颜色标注保留（黄底只叠背景），命中判定大小写不敏感。
 */
fun highlightHits(src: AnnotatedString, query: String): AnnotatedString {
    val q = query.trim()
    if (q.isEmpty()) return src
    val plain = src.text
    val lower = plain.lowercase()
    val needle = q.lowercase()
    val hits = mutableListOf<IntRange>()
    var i = 0
    while (i <= lower.length - needle.length) {
        val k = lower.indexOf(needle, i)
        if (k < 0) break
        hits.add(k until (k + needle.length))
        i = k + needle.length
    }
    if (hits.isEmpty()) return src
    val sb = AnnotatedString.Builder(src)
    for (r in hits) {
        sb.addStyle(SpanStyle(background = Color(0xFFFFE066), color = Color(0xFF1A1A1A)), r.first, r.last + 1)
    }
    return sb.toAnnotatedString()
}

/** 气泡正文：逐块渲染，段落可点链接，表格画成网格。 */
@Composable
fun RichText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    /** 变化即重建 SelectionContainer：用于点空白/点正文取消文本选中。 */
    selectionReset: Int = 0,
    /** 点正文空白处（非链接）时回调：外层据此取消选中。 */
    onClearSelection: () -> Unit = {},
    /** 搜索命中的词：非空时正文里给它加黄底。 */
    hitQuery: String = "",
    /** 富卡片按钮点击回调：由外层把它变成一条消息 / 打开链接。 */
    onCardAction: (CardAction) -> Unit = {},
) {
    val c = LocalAppColors.current
    val uri = LocalUriHandler.current
    // 音频附件（完成语音播报）不占正文块：它改由气泡底部时间行里的迷你图标呈现。
    val blocks = remember(text) {
        parseMdBlocks(text).filterNot { it is MdBlock.Attachment && VoicePlayer.isAudio(it.name) }
    }

    Column(modifier) {
        for ((idx, b) in blocks.withIndex()) {
            if (idx > 0) Spacer(Modifier.height(6.dp))
            when (b) {
                is MdBlock.Image -> MdImage(b.dataUrl, b.alt)
                is MdBlock.Attachment -> MdAttachmentCard(b.name, b.dataUrl, b.token)
                is MdBlock.Card -> HermesCardView(b.card, onCardAction)
                is MdBlock.Para -> {
                    val ann = remember(b.text, c.accent, hitQuery) {
                        highlightHits(linkAnnotated(b.text, c.accent), hitQuery)
                    }
                    // key 变化 → SelectionContainer 被重建，选中态随之清除（点空白/点正文时触发）。
                    key(selectionReset) {
                        SelectionContainer {
                            ClickableText(
                                text = ann,
                                style = TextStyle(
                                    color = color, fontSize = fontSize,
                                    fontFamily = FontFamily.Monospace
                                ),
                                onClick = { off ->
                                    val hit = ann.getStringAnnotations("URL", off, off).firstOrNull()
                                    if (hit != null) {
                                        runCatching { uri.openUri(hit.item) }
                                    } else {
                                        onClearSelection()   // 点正文空白：取消选中
                                    }
                                }
                            )
                        }
                    }
                }
                is MdBlock.Table -> MdTable(b.rows, color, fontSize)
            }
        }
    }
}

@Composable
private fun MdTable(rows: List<List<String>>, color: Color, fontSize: TextUnit) {
    val c = LocalAppColors.current
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for ((ri, row) in rows.withIndex()) {
            Row {
                for (cell in row) {
                    Box(
                        Modifier
                            .widthIn(min = 76.dp)
                            .background(if (ri == 0) c.card else Color.Transparent)
                            .border(0.5.dp, c.dim)
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Text(
                            cell,
                            color = if (ri == 0) c.accent else color,
                            fontSize = fontSize * 0.95f,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/** 内联图片（正文里的 data URL）：点击 App 内放大查看，长按保存到相册。 */
@Composable
private fun MdImage(dataUrl: String, alt: String) {
    val c = LocalAppColors.current
    // 解码失败时给一个重试出口：原来只显示一行「[图片解析失败]」就没了，
    // 用户分不清是「数据坏了」还是「这一下没解出来」，也没有任何补救动作。
    var retry by remember { mutableStateOf(0) }
    val decoded = remember(dataUrl, retry) { decodeDataUrl(dataUrl) }
    if (decoded == null) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background(c.card)
                .border(0.5.dp, c.dim, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("图片加载失败", color = c.warn, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "点此重试",
                    color = c.accent, fontSize = 11.sp,
                    modifier = Modifier.clickable { retry++ }
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "数据 " + dataUrl.length + " 字节",
                    color = c.dim, fontSize = 9.sp
                )
            }
        }
        return
    }
    ZoomableImage(
        model = decoded.bytes,
        decoded = decoded,
        alt = alt,
        thumbScale = ContentScale.Fit,
        thumbModifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(8.dp)),
    )
}

/**
 * 用户气泡里的本地图片（content:// 或 file:// Uri）：点击 App 内放大查看，长按保存到相册。
 * 读不到字节时仍能放大（用 Uri 直接渲染），只是不能保存。
 */
@Composable
fun LocalImageView(uri: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val decoded = remember(uri) { uriToDecoded(ctx, Uri.parse(uri)) }
    // 读不到字节：可能是相册给的 content:// 已被系统回收（换机/清数据/授权到期）。
    // 不再默默变白框，明确标出来，重开/重选后若恢复则自动恢复正常渲染。
    if (decoded == null) {
        LocalImageFallback(uri, modifier)
        return
    }
    ZoomableImage(
        model = decoded.bytes,
        decoded = decoded,
        alt = "image",
        thumbScale = ContentScale.Crop,
        thumbModifier = modifier,
    )
}

/** 本地图读不到时的可见兜底：一个带「图片不可用」的小灰块，不占满屏也不静默。 */
@Composable
private fun LocalImageFallback(uri: String, modifier: Modifier) {
    val c = LocalAppColors.current
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(c.card)
            .border(0.5.dp, c.dim, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text("图片不可用", color = c.dim, fontSize = 10.sp, modifier = Modifier.padding(4.dp))
    }
}

/**
 * 可缩放图片：缩略图点击 → 全屏 Dialog；全屏支持双指缩放/拖动，底部「保存到相册」按钮保存。
 * model 可以是 ByteArray（内联图）或 Uri 字符串（本地图）；decoded 为 null 时无保存按钮。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImage(
    model: Any?,
    decoded: DecodedData?,
    alt: String,
    thumbScale: ContentScale,
    thumbModifier: Modifier,
) {
    val ctx = LocalContext.current
    var zoom by remember { mutableStateOf(false) }
    val name = alt.ifBlank { "image" } + extFor(decoded?.mime ?: "image/png")
    fun save() {
        val d = decoded ?: return
        val saved = saveImageToGallery(ctx, name, d)
        Toast.makeText(
            ctx,
            if (saved != null) "已保存到相册：Pictures/Hermes/$saved" else "保存失败",
            Toast.LENGTH_SHORT
        ).show()
    }
    // 三态渲染：加载中显示灰底占位，失败显示「加载失败 + 重试」，成功才画图。
    // 原来直接 AsyncImage，网络一抖或 token 过期就是一片空白，用户分不清「在加载」还是「坏了」。
    var retry by remember { mutableStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    // retry 作为请求参数进缓存键：点重试才会真正重新发起请求（否则模型没变，Coil 直接复用失败的缓存）。
    val req = remember(model, retry) {
        coil.request.ImageRequest.Builder(ctx)
            .data(model)
            .setParameter("retry", retry)
            .build()
    }
    SubcomposeAsyncImage(
        model = req,
        contentDescription = alt.ifBlank { "图片" },
        contentScale = thumbScale,
        modifier = thumbModifier.clickable(enabled = !failed) { zoom = true }
    ) {
        when (painter.state) {
            is AsyncImagePainter.State.Loading -> Box(
                Modifier.fillMaxSize().background(LocalAppColors.current.card),
                contentAlignment = Alignment.Center
            ) { Text("加载中…", color = LocalAppColors.current.dim, fontSize = 10.sp) }
            is AsyncImagePainter.State.Error -> {
                failed = true
                Box(
                    Modifier.fillMaxSize().background(LocalAppColors.current.card),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "加载失败，点此重试",
                        color = LocalAppColors.current.warn, fontSize = 10.sp,
                        modifier = Modifier.clickable { failed = false; retry++ }
                    )
                }
            }
            else -> {
                failed = false
                SubcomposeAsyncImageContent()
            }
        }
    }
    if (zoom) {
        // 全屏查看：双指缩放 + 拖动，底部按钮保存/关闭（点空白不再误关，方便缩放）
        var scale by remember { mutableStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val state = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            offset = if (scale <= 1f) Offset.Zero else offset + panChange
        }
        Dialog(
            onDismissRequest = { zoom = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.95f)),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = model,
                    contentDescription = alt.ifBlank { "图片" },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y
                        )
                        .transformable(state)
                )
                // 顶部操作条：不再依赖长按，按钮一目了然
                Row(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (decoded != null) {
                        OutlinedButton(
                            onClick = { save() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text("保存到相册", color = Color.White, fontSize = 13.sp) }
                    }
                    OutlinedButton(
                        onClick = { zoom = false },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("关闭", color = Color.White, fontSize = 13.sp) }
                }
                Text(
                    "双指缩放 · 拖动查看",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                )
            }
        }
    }
}

/** 非图片附件卡片：点一下落盘再拉起系统应用打开（HTML 走浏览器）。 */
@Composable
private fun MdAttachmentCard(name: String, dataUrl: String, token: String) {
    // 语音附件已在上层过滤掉，不会走到这里；兜底也不显示成文件卡片。
    if (VoicePlayer.isAudio(name)) return
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // 内联 data URL：直接解出字节；网关托管：点开时才按需下载
    val decoded = remember(dataUrl) { if (dataUrl.isNotEmpty()) decodeDataUrl(dataUrl) else null }
    val sub = when {
        busy -> "下载中…"
        decoded != null -> fmtSize(decoded.bytes.size) + " · 点击打开"
        token.isNotEmpty() -> "网关托管 · 点击下载打开"
        else -> "解析失败"
    }
    // 分享/保存都要拿到字节：内联的直接用，网关托管的先按需下载再动作。
    fun withBytes(action: (DecodedData) -> Unit) {
        when {
            decoded != null -> action(decoded)
            token.isNotEmpty() -> {
                busy = true
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) { MediaFetch.download(token) }
                    busy = false
                    if (bytes != null && bytes.isNotEmpty()) action(DecodedData(guessMime(name), bytes))
                    else {
                        // 分享/保存与「打开」提示策略对齐：静默失败用户分不清是没成功还是没点到。
                        val why = MediaFetch.lastError.ifEmpty { "文件下载不到" }
                        AppLog.log("attach", "附件下载失败（分享/保存）" + why + " name=" + name)
                        Toast.makeText(ctx, "下载失败：" + why, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
    Row(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.card)
            .border(0.5.dp, c.dim, RoundedCornerShape(8.dp))
            .clickable {
                if (busy) return@clickable
                when {
                    decoded != null -> openAttachment(ctx, name, decoded)
                    token.isNotEmpty() -> {
                        busy = true
                        scope.launch {
                            val bytes = withContext(Dispatchers.IO) { MediaFetch.download(token) }
                            busy = false
                            if (bytes != null && bytes.isNotEmpty()) {
                                openAttachment(ctx, name, DecodedData(guessMime(name), bytes))
                            } else {
                                val why = MediaFetch.lastError.ifEmpty { "文件下载不到" }
                                AppLog.log("attach", "打开失败：" + why + " name=" + name)
                                Toast.makeText(ctx, "打开失败：" + why, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    else -> {
                        AppLog.log("attach", "打开失败：附件解析不出内容 name=" + name)
                        Toast.makeText(ctx, "打开失败：附件内容解析不出来", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\uD83D\uDCCE", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, color = c.text, fontSize = 13.sp, maxLines = 2)
            Text(sub, color = c.dim, fontSize = 11.sp)
        }
        if (decoded != null || token.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Rounded.Share, "分享", tint = c.accent,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable {
                    if (busy) return@clickable
                    withBytes { shareAttachment(ctx, name, it) }
                }
            )
            Spacer(Modifier.width(10.dp))
            Icon(
                Icons.Rounded.Download, "保存到下载", tint = c.accent,
                modifier = Modifier.size(18.dp).clip(CircleShape).clickable {
                    if (busy) return@clickable
                    withBytes { d ->
                        val saved = saveAttachmentToDownloads(ctx, name, d)
                        Toast.makeText(
                            ctx,
                            if (saved != null) "已保存到下载：Download/Hermes/" + saved else "保存失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }
    }
}

/**
 * 语音附件的迷你播放图标：16dp 纯图标，跟时间并排同一行显示（不单独占一行、不显示文件名）。
 * 空闲是播放三角，正在播这条时变停止方块，点一下播 / 再点一下停，播完可重播。
 */
@Composable
fun VoiceMiniButton(target: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    val playing by VoicePlayer.nowPlaying.collectAsStateWithLifecycle()
    val isThis = target.isNotEmpty() && playing == target
    Icon(
        imageVector = if (isThis) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
        contentDescription = if (isThis) "停止播放" else "播放语音",
        tint = if (isThis) c.accent else c.dim,
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .clickable(enabled = target.isNotEmpty()) { VoicePlayer.toggle(ctx, target) }
    )
}

/**
 * 语音重播的迷你播放图标：16dp 纯图标，跟时间并排同一行（与附件版同款）。
 * 空闲是播放三角，正在播这条时变停止方块，点一下播 / 再点一下停，可反复重播。
 * 音频不存在（服务端已淘汰该留档）时点了不响，只在日志里记一行。
 */
@Composable
fun VoiceReplayMiniButton(runId: String, inlineTarget: String = "", modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    val playing by VoiceReplayPlayer.nowPlaying.collectAsStateWithLifecycle()
    // 同一条语音可能由**三个**播放器之一在响，按钮必须全都认得，谁在响这条就亮 ■、
    // 点了停谁——只盯自己那套就会「正响着这条时按钮一直是 ▶，点它反而从头重播一遍」：
    // - 流式自动播报（任务跑完在后台自动念的）→ StreamVoicePlayer，key = "stream:<runId>"；
    //   用户报「任务结束后的首次语音播报没法立即停止」（2026-10-10，2.172 只修了下面
    //   两种、漏了这种主路径）。点 ■ = 停全部并清队列（与既有「停止」语义一致）。
    // - 完成语音附件（老消息内联 base64/托管 token）→ VoicePlayer，按 inlineTarget。
    // - 手动重播 → VoiceReplayPlayer，按 runId。
    val inlineNow by VoicePlayer.nowPlaying.collectAsStateWithLifecycle()
    val inlinePlaying = inlineTarget.isNotEmpty() && inlineNow == inlineTarget
    val streamNow by StreamVoicePlayer.nowPlaying.collectAsStateWithLifecycle()
    val streamPlaying = runId.isNotEmpty() && streamNow == "stream:" + runId
    val isThis = runId.isNotEmpty() && playing == runId
    val active = isThis || inlinePlaying || streamPlaying
    Icon(
        imageVector = if (active) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
        contentDescription = if (active) "停止播放" else "播放语音",
        tint = if (active) c.accent else c.dim,
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .clickable(enabled = runId.isNotEmpty() || inlineTarget.isNotEmpty()) {
                when {
                    streamPlaying -> StreamVoicePlayer.stop()    // 停流式自动播报（停全部清队列）
                    inlinePlaying -> VoicePlayer.stop()           // 停完成附件播报
                    isThis -> VoiceReplayPlayer.stop()            // 停手动重播
                    else -> VoiceReplayPlayer.toggle(ctx, runId)  // 都没在响：重播
                }
            }
    )
}

/**
 * 「这条语音正在播」的小喇叭：在播时出现、播完消失，跟着时间行显示。
 *
 * 为什么要它：播放按钮只在「手动重播」时变成停止方块；自动播报（流式）期间按钮
 * 始终是三角，多任务排队轮到某条时用户完全看不出在播谁。这里把「在播」显式标出来。
 */
@Composable
fun VoiceSpeakerMark(runId: String) {
    val c = LocalAppColors.current
    val streamKey by StreamVoicePlayer.nowPlaying.collectAsStateWithLifecycle()
    val replay by VoiceReplayPlayer.nowPlaying.collectAsStateWithLifecycle()
    if (runId.isNotEmpty() && (streamKey == "stream:" + runId || replay == runId)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Rounded.VolumeUp,
                contentDescription = "正在播放这条语音",
                tint = c.accent,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

/**
 * 富卡片渲染：左侧一条强调色竖条 + 标题/副标题 + 正文 + 字段表 + 按钮行 + 页脚。
 * 色板按 accent（info/success/warning/danger）取主题色，其余按 info。
 */
@Composable
fun HermesCardView(card: HermesCard, onAction: (CardAction) -> Unit) {
    val c = LocalAppColors.current
    val accent = when (card.accent) {
        "success" -> c.ok
        "warning" -> c.warn
        "danger" -> c.bad
        else -> c.accent
    }
    Row(
        Modifier
            .widthIn(max = 320.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(c.card)
            .border(0.5.dp, c.dim, RoundedCornerShape(10.dp))
    ) {
        Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(10.dp)) {
            if (card.title.isNotEmpty()) {
                Text(card.title, color = c.text, fontSize = 14.sp)
            }
            if (card.subtitle.isNotEmpty()) {
                if (card.title.isNotEmpty()) Spacer(Modifier.height(2.dp))
                Text(card.subtitle, color = c.dim, fontSize = 11.sp)
            }
            if (card.body.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(card.body, color = c.text, fontSize = 12.sp)
            }
            if (card.fields.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for ((k, v) in card.fields) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(k, color = c.dim, fontSize = 11.sp, modifier = Modifier.width(76.dp))
                        Text(v, color = c.text, fontSize = 11.sp, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (card.actions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (a in card.actions) {
                        OutlinedButton(
                            onClick = { onAction(a) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                            shape = RoundedCornerShape(8.dp),
                        ) { Text(a.label, color = accent, fontSize = 12.sp) }
                    }
                }
            }
            if (card.footer.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(card.footer, color = c.dim, fontSize = 10.sp)
            }
        }
    }
}

/** 按扩展名猜 MIME（网关托管的附件返回时用它给系统应用定位）。 */
fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "pdf" -> "application/pdf"
    "json" -> "application/json"
    "csv" -> "text/csv"
    "zip" -> "application/zip"
    "md" -> "text/markdown"
    "txt", "log" -> "text/plain"
    "mp3" -> "audio/mpeg"
    "mp4" -> "video/mp4"
    else -> "application/octet-stream"
}
