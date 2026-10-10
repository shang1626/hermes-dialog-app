package com.hermesapp

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌（2026-10-10 UI 打磨）：字阶 / 圆角 / 间距。
 *
 * 起因：此前全 App 直接写死字号，实测 12sp 用了 81 处、11sp 55 处、13sp 49 处、10sp 25 处 ——
 * 正文只有 12sp、辅助 11sp、角标 10sp，整体又小又灰、层级分不出来；圆角 56 处全是 8dp 一刀切；
 * 间距混用 10/12/14/28dp，没有节奏。Material 组件的 Typography/Shapes 又是默认值，
 * 与手写 Text 的硬编码字号互相打架。
 *
 * 规则（改界面时照这个来）：
 * - 字号只从 [T] 里取，不再新增裸 sp 数字；
 * - 圆角只从 [R] 里取，按“元素级别”分档；
 * - 间距只从 [G] 里取（4dp 网格）。
 */
object T {
    /** 品牌字（登录页/抽屉里的 Hermes 字样）。 */
    val hero = 30.sp

    /** 大标题（登录页、空态主标题）。 */
    val display = 22.sp

    /** 页面/顶栏标题。 */
    val title = 17.sp

    /** 正文：聊天气泡、设置项标题、按钮文字。 */
    val body = 15.sp

    /** 次级：说明文字、卡片副标题、按钮。 */
    val sub = 13.sp

    /** 说明：辅助信息、表单提示。 */
    val cap = 12.sp

    /** 最小：时间戳、角标、状态标记。 */
    val micro = 11.sp

    /** 聊天气泡正文（比设置页正文大一号，长段落更好读）。 */
    val chat = 16.sp

    /** 正文行高（约 1.47 倍，中文长段落更透气）。 */
    val lineBody = 22.sp

    /** 次级行高。 */
    val lineSub = 19.sp

    val bold = FontWeight.SemiBold
    val heavy = FontWeight.Bold
}

/** 圆角：按元素级别分档（原来一律 8dp）。
 *
 * 注意：**绝不能把这个对象命名为 `R`** —— 模块里叫 `R` 的顶层对象会盖住 Android 生成的
 * `com.hermesapp.R`，全工程所有 `R.drawable.xxx` 立刻变成「Unresolved reference: drawable」
 * （2026-10-10 亲测踩过）。所以这里叫 `Rad`。
 */
object Rad {
    /** 助手/用户气泡：靠发送者那一侧的小圆角，另一侧大圆角。 */
    val bubble = 18.dp
    val bubbleTight = 6.dp

    /** 卡片、面板、对话框。 */
    val card = 16.dp
    val panel = 20.dp

    /** 输入框（胶囊感，与气泡区分）。 */
    val field = 24.dp

    /** 图片、附件缩略图。 */
    val image = 12.dp

    /** 标签、按钮、引文块。 */
    val chip = 10.dp
    val pill = 999.dp
}

/** 间距：4dp 网格。 */
object G {
    val x1 = 4.dp
    val x2 = 8.dp
    val x3 = 12.dp
    val x4 = 16.dp
    val x5 = 20.dp
    val x6 = 24.dp

    /** 页面左右内边距。 */
    val page = 16.dp

    /** 列表项最小高度（手指点得准、也不显稀）。 */
    val touch = 48.dp
}

/**
 * Material 组件的字阶：把默认值换成与手写 Text 同一套，避免「Material 组件 14sp + 手写 12sp」两套尺度并存。
 */
val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = T.title, lineHeight = 24.sp, fontWeight = T.bold),
    titleMedium = TextStyle(fontSize = T.body, lineHeight = T.lineSub, fontWeight = T.bold),
    bodyLarge = TextStyle(fontSize = T.body, lineHeight = T.lineBody),
    bodyMedium = TextStyle(fontSize = T.sub, lineHeight = T.lineSub),
    bodySmall = TextStyle(fontSize = T.cap, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = T.sub, fontWeight = T.bold),
    labelMedium = TextStyle(fontSize = T.cap),
    labelSmall = TextStyle(fontSize = T.micro),
)

/** Material 组件的形状：与 [R] 对齐。 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Rad.chip),
    small = RoundedCornerShape(Rad.chip),
    medium = RoundedCornerShape(Rad.card),
    large = RoundedCornerShape(Rad.panel),
    extraLarge = RoundedCornerShape(Rad.panel),
)
