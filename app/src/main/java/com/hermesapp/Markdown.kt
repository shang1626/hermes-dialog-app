package com.hermesapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import coil.compose.AsyncImage
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

sealed class MdBlock {
    /** 普通段落（可含链接）。 */
    data class Para(val text: String) : MdBlock()

    /** 管道表格：rows[0] 是表头。 */
    data class Table(val rows: List<List<String>>) : MdBlock()

    /** 内联图片：服务端把 MEDIA: 图片转成 data URL 后送到这里。 */
    data class Image(val alt: String, val dataUrl: String) : MdBlock()

    /** 非图片附件：服务端把 MEDIA: 文件转成 [📎 名](data:...) 链接。 */
    data class Attachment(val name: String, val dataUrl: String) : MdBlock()
}

private val URL_RE = Regex("https?://[^\\s<>()\\[\\]{}\"'\\uFF0C\\u3002\\u3001\\uFF09\\u3011]+")

/** 独占一行的内联图片：![alt](data:image/...) */
private val MD_IMG_LINE_RE = Regex("^!\\[([^\\]]*)\\]\\((data:image/[^)]+)\\)$")

/** 独占一行的附件链接：[📎 文件名](data:...) */
private val MD_ATT_LINE_RE = Regex("^\\[\\uD83D\\uDCCE ([^\\]]+)\\]\\((data:[^)]+)\\)$")

/** 去掉内联强调标记：双星号加粗、反引号代码——手机窄屏上留着反而难看。 */
private fun cleanInline(s: String): String =
    s.replace("**", "").replace("`", "")

/** 判断是否表格分隔行（|---|---|）。 */
private fun isSeparator(t: String): Boolean {
    if (!t.contains('-')) return false
    val core = t.replace("|", "").replace(":", "").trim()
    return core.isNotEmpty() && core.all { it == '-' || it == ' ' }
}

private fun splitRow(t: String): List<String> =
    t.trim().trim('|').split("|").map { cleanInline(it.trim()) }

private fun normalize(rows: List<List<String>>): List<List<String>> {
    val n = rows.maxOfOrNull { it.size } ?: return rows
    return rows.map { r -> if (r.size == n) r else r + List(n - r.size) { "" } }
}

fun parseMdBlocks(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val lines = src.split("\n")
    val buf = StringBuilder()

    /** data URL 图片/附件通常独占一行：按行拆成独立块，其余仍作普通段落。 */
    fun flushPara(raw: String) {
        val pending = StringBuilder()
        fun flushPending() {
            val t = pending.toString().trim('\n')
            if (t.isNotEmpty()) out.add(MdBlock.Para(t))
            pending.setLength(0)
        }
        for (line in raw.split("\n")) {
            val s = line.trim()
            val img = MD_IMG_LINE_RE.matchEntire(s)
            val att = MD_ATT_LINE_RE.matchEntire(s)
            when {
                img != null -> { flushPending(); out.add(MdBlock.Image(img.groupValues[1], img.groupValues[2])) }
                att != null -> { flushPending(); out.add(MdBlock.Attachment(att.groupValues[1], att.groupValues[2])) }
                else -> pending.append(line).append("\n")
            }
        }
        flushPending()
    }

    fun flush() {
        if (buf.isNotEmpty()) {
            flushPara(buf.toString().trim('\n'))
            buf.setLength(0)
        }
    }

    var i = 0
    while (i < lines.size) {
        val t = lines[i].trim()
        if (t.startsWith("|") && i + 1 < lines.size && isSeparator(lines[i + 1].trim())) {
            val rows = mutableListOf(splitRow(t))
            i += 2
            while (i < lines.size && lines[i].trim().startsWith("|")) {
                rows.add(splitRow(lines[i].trim()))
                i++
            }
            flush()
            out.add(MdBlock.Table(normalize(rows)))
        } else {
            buf.append(lines[i]).append("\n")
            i++
        }
    }
    flush()
    return out
}

/** 把纯文本切成带 StringAnnotation("URL") 的 AnnotatedString，链接用强调色。 */
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

/** 气泡正文：逐块渲染，段落可点链接，表格画成网格。 */
@Composable
fun RichText(
    text: String,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    val c = LocalAppColors.current
    val uri = LocalUriHandler.current
    val blocks = remember(text) { parseMdBlocks(text) }

    Column(modifier) {
        for ((idx, b) in blocks.withIndex()) {
            if (idx > 0) Spacer(Modifier.height(6.dp))
            when (b) {
                is MdBlock.Image -> MdImage(b.dataUrl, b.alt)
                is MdBlock.Attachment -> MdAttachmentCard(b.name, b.dataUrl)
                is MdBlock.Para -> {
                    val ann = remember(b.text, c.accent) { linkAnnotated(b.text, c.accent) }
                    ClickableText(
                        text = ann,
                        style = TextStyle(
                            color = color, fontSize = fontSize,
                            fontFamily = FontFamily.Monospace
                        ),
                        onClick = { off ->
                            ann.getStringAnnotations("URL", off, off).firstOrNull()?.let {
                                runCatching { uri.openUri(it.item) }
                            }
                        }
                    )
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

/** 内联图片：直接吃 data URL 的字节，点一下用系统看图/浏览器打开。 */
@Composable
private fun MdImage(dataUrl: String, alt: String) {
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    val decoded = remember(dataUrl) { decodeDataUrl(dataUrl) }
    if (decoded == null) {
        Text("[图片解析失败]", color = c.dim, fontSize = 12.sp)
        return
    }
    AsyncImage(
        model = decoded.bytes,
        contentDescription = alt.ifBlank { "图片" },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { openAttachment(ctx, alt.ifBlank { "image" } + extFor(decoded.mime), decoded) }
    )
}

/** 非图片附件卡片：点一下落盘再拉起系统应用打开（HTML 走浏览器）。 */
@Composable
private fun MdAttachmentCard(name: String, dataUrl: String) {
    val ctx = LocalContext.current
    val c = LocalAppColors.current
    val decoded = remember(dataUrl) { decodeDataUrl(dataUrl) }
    Row(
        Modifier
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(c.card)
            .border(0.5.dp, c.dim, RoundedCornerShape(8.dp))
            .clickable { if (decoded != null) openAttachment(ctx, name, decoded) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\\uD83D\\uDCCE", fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, color = c.text, fontSize = 13.sp, maxLines = 2)
            Text(
                if (decoded == null) "解析失败" else fmtSize(decoded.bytes.size) + " · 点击打开",
                color = c.dim, fontSize = 11.sp
            )
        }
    }
}
