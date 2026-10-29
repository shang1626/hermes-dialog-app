package com.hermesapp

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * 全屏看图里的一项。
 * [model] 是 Coil 能直接渲染的东西（本地 Uri 字符串 / 已解码字节）；
 * 内联 data URL 图不给 model，进到那一页时才按 [dataUrl] 现解（避免一次解几十张爆内存）。
 * [bytes]/[dataUrl]/[uri] 任一能取到字节就支持「保存到相册」。
 */
data class ViewerImage(
    val name: String,
    val model: Any? = null,
    val dataUrl: String? = null,
    val uri: String? = null,
    val bytes: ByteArray? = null,
)

/** 这一项的可保存字节：优先已解码字节，其次现解 data URL，最后按 Uri 读。 */
private fun saveDataOf(ctx: Context, item: ViewerImage): DecodedData? {
    item.bytes?.let { if (it.isNotEmpty()) return DecodedData(guessMime(item.name), it) }
    item.dataUrl?.let { d -> decodeDataUrl(d)?.let { return it } }
    item.uri?.let { u -> uriToDecoded(ctx, Uri.parse(u))?.let { return it } }
    return null
}

/**
 * 全屏看图：左右滑动切换多张（会话内的多张图连成一组）、双指缩放、拖动、保存到相册。
 * 某张被放大（scale > 1）时临时关掉翻页滑动，免得拖动图片变成翻页。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImagePagerDialog(
    items: List<ViewerImage>,
    start: Int,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    // 当前页是否处于放大态：放大时禁掉翻页，交给图片自己吃拖动
    var pageZoomed by remember { mutableStateOf(false) }
    // 翻页后重新按「未放大」处理，避免上一页放大态把新页的翻页滑动卡住
    LaunchedEffect(pagerState.currentPage) { pageZoomed = false }
    val cur = items[pagerState.currentPage.coerceIn(0, items.lastIndex)]

    fun save(item: ViewerImage) {
        val d = saveDataOf(ctx, item)
        if (d == null) {
            Toast.makeText(ctx, "保存失败：图片数据读不到", Toast.LENGTH_SHORT).show()
            return
        }
        val saved = saveImageToGallery(ctx, item.name, d)
        Toast.makeText(
            ctx,
            if (saved != null) "已保存到相册：Pictures/Hermes/$saved" else "保存失败",
            Toast.LENGTH_SHORT
        ).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.95f)),
            contentAlignment = Alignment.Center
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = !pageZoomed,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                ZoomPage(
                    item = items[page],
                    onZoomedChange = { z -> if (page == pagerState.currentPage) pageZoomed = z }
                )
            }
            // 顶部：计数 + 保存 + 关闭（可见入口，不靠长按/手势）
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (items.size > 1) "${pagerState.currentPage + 1} / ${items.size}" else "",
                    color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { save(cur) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("保存到相册", color = Color.White, fontSize = 13.sp) }
                    OutlinedButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("关闭", color = Color.White, fontSize = 13.sp) }
                }
            }
            // 左右切换箭头：看得见、点得到（不只有手势；到头的那侧不出现）
            if (items.size > 1) {
                if (pagerState.currentPage > 0) {
                    Text(
                        "‹", color = Color.White.copy(alpha = 0.85f), fontSize = 34.sp,
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .clickable {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage - 1)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
                if (pagerState.currentPage < items.lastIndex) {
                    Text(
                        "›", color = Color.White.copy(alpha = 0.85f), fontSize = 34.sp,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .clickable {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
            Text(
                if (items.size > 1) "左右滑动切换 · 双指缩放 · 拖动查看" else "双指缩放 · 拖动查看",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
            )
        }
    }
}

/** 单页：可缩放/拖动的图片。缩放态变化回调给外层，用来临时禁用翻页滑动。 */
@Composable
private fun ZoomPage(item: ViewerImage, onZoomedChange: (Boolean) -> Unit) {
    var scale by remember(item) { mutableStateOf(1f) }
    var offset by remember(item) { mutableStateOf(Offset.Zero) }
    val cb by rememberUpdatedState(onZoomedChange)
    val model = remember(item.model, item.dataUrl) {
        item.model ?: item.dataUrl?.let { decodeDataUrl(it)?.bytes }
    }
    // 手势必须「该让的时候让」：单指且未放大时**不消费**事件，交给 HorizontalPager 翻页；
    // 双指、或已放大时才吃事件做缩放/拖动。用 transformable() 会在单指拖动时就把事件吃掉，
    // 翻页会彻底失效（这是 2.227 的实现要点）。
    val gestureModifier = Modifier.pointerInput(item) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var event: PointerEvent = awaitPointerEvent()
            while (true) {
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2 || scale > 1f) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    scale = (scale * zoomChange).coerceIn(1f, 6f)
                    offset = if (scale <= 1f) Offset.Zero else offset + panChange
                    cb(scale > 1.01f)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                if (!event.changes.any { it.pressed }) break
                event = awaitPointerEvent()
            }
        }
    }
    // 双击：放大到 2.5 倍 / 还原（放大后单指拖动图片，不再翻页）
    val doubleTapModifier = Modifier.pointerInput(item) {
        detectTapGestures(onDoubleTap = {
            if (scale > 1f) {
                scale = 1f
                offset = Offset.Zero
            } else {
                scale = 2.5f
            }
            cb(scale > 1.01f)
        })
    }
    AsyncImage(
        model = model,
        contentDescription = item.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .graphicsLayer(
                scaleX = scale, scaleY = scale,
                translationX = offset.x, translationY = offset.y
            )
            .then(doubleTapModifier)
            .then(gestureModifier)
    )
}
