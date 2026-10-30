package com.hermesapp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 折线图的一条线：[values] 是 0..100 的百分比采样（旧→新），[color] 决定颜色，
 * [currentText] 是右侧显示的当前值（如「37.2%」）。
 */
data class ChartSeries(
    val label: String,
    val color: Color,
    val values: List<Float>,
    val currentText: String = "",
)

/**
 * 状态页的折线图（替代原来的进度条）。
 *
 * 为什么自己画 Canvas 而不引图表库：本工程一直是零第三方 UI 依赖（离线构建稳、体积零增加），
 * 而且这里只要「几条 0..100 的折线 + 网格」，引一个库比写这几十行贵得多。
 *
 * [maxPoints] 只用于把横轴按「固定窗口」铺开：数据点不满时线从左往右长，不会一上来就拉满整宽
 * （否则刚打开 App 只有一个点，看上去像一条平线，容易误读成「一直这么高」）。
 */
@Composable
fun StatusLineChart(
    series: List<ChartSeries>,
    maxPoints: Int,
    modifier: Modifier = Modifier,
    height: Dp = 104.dp,
) {
    val c = LocalAppColors.current
    val hasData = series.any { it.values.isNotEmpty() }
    Column(modifier.fillMaxWidth()) {
        // 图例：色点 + 名称 + 当前值（同类信息同行，不占额外高度）
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            for ((i, s) in series.withIndex()) {
                if (i > 0) Spacer(Modifier.width(14.dp))
                Box(Modifier.size(7.dp).clip(CircleShape).background(s.color))
                Spacer(Modifier.width(5.dp))
                Text(s.label, color = c.dim, fontSize = T.cap)
                if (s.currentText.isNotEmpty()) {
                    Spacer(Modifier.width(5.dp))
                    Text(s.currentText, color = s.color, fontSize = T.cap)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(Rad.chip))
                .background(c.cardAlt)
        ) {
            if (!hasData) {
                Text(
                    "正在采集…",
                    color = c.faint, fontSize = T.cap,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                Canvas(Modifier.fillMaxWidth().height(height).padding(horizontal = 6.dp, vertical = 8.dp)) {
                    val w = size.width
                    val h = size.height
                    // 横向网格：0% / 50% / 100%（虚线太细，用极淡实线）
                    for (f in listOf(0f, 0.5f, 1f)) {
                        val y = h - f * h
                        drawLine(
                            color = c.faint.copy(alpha = 0.25f),
                            start = Offset(0f, y), end = Offset(w, y),
                            strokeWidth = 1f
                        )
                    }
                    val denom = (maxPoints - 1).coerceAtLeast(1)
                    for (s in series) {
                        val pts = s.values
                        if (pts.isEmpty()) continue
                        // 单点也得画出「一个点」，否则刚启动时整张图是空的
                        if (pts.size == 1) {
                            val y = h - (pts[0] / 100f).coerceIn(0f, 1f) * h
                            drawCircle(s.color, radius = 3.5f, center = Offset(0f, y))
                            continue
                        }
                        val path = Path()
                        var lastX = 0f
                        var lastY = 0f
                        for ((i, v) in pts.withIndex()) {
                            val x = w * i / denom
                            val y = h - (v / 100f).coerceIn(0f, 1f) * h
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            lastX = x
                            lastY = y
                        }
                        drawPath(path, s.color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
                        // 末端点：一眼看出「现在在哪」
                        drawCircle(s.color, radius = 3.2f, center = Offset(lastX, lastY))
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("约 3 分钟前", color = c.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("现在", color = c.faint, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

/** 一行小字说明（图表下方的口径提示，避免用户把折线误读成「磁盘/负载也在图里」）。 */
@Composable
fun ChartCaption(text: String) {
    val c = LocalAppColors.current
    Text(text, color = c.faint, fontSize = T.micro, modifier = Modifier.fillMaxWidth())
}
