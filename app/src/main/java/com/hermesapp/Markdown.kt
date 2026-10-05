package com.hermesapp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * 极简 Markdown 渲染：只认两类——管道表格 + URL 链接。
 * 不引第三方库（离线构建稳、体积零增加），其余内容一律按等宽纯文本原样显示。
 */

sealed class MdBlock {
    /** 普通段落（可含链接）。 */
    data class Para(val text: String) : MdBlock()

    /** 管道表格：rows[0] 是表头。 */
    data class Table(val rows: List<List<String>>) : MdBlock()
}

private val URL_RE = Regex("https?://[^\\s<>()\\[\\]{}\"'\uFF0C\u3002\u3001\uFF09\u3011]+")

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

    fun flush() {
        if (buf.isNotEmpty()) {
            val t = buf.toString().trim('\n')
            if (t.isNotEmpty()) out.add(MdBlock.Para(t))
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
