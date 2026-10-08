package com.hermesapp

/**
 * Markdown 纯解析层：块模型 + 解析器。
 *
 * 与 Markdown.kt（Compose 渲染层）拆开：这里零 Android / Compose 依赖，
 * 只有 org.json，便于在 app/src/test 里跑纯 JVM 单测（阶段2）。
 */

sealed class MdBlock {
    /** 普通段落（可含链接）。 */
    data class Para(val text: String) : MdBlock()

    /** 管道表格：rows[0] 是表头。 */
    data class Table(val rows: List<List<String>>) : MdBlock()

    /** 内联图片：服务端把 MEDIA: 图片转成 data URL 后送到这里。 */
    data class Image(val alt: String, val dataUrl: String) : MdBlock()

    /** 非图片附件：服务端把 MEDIA: 文件转成 [📎 名](data:...) 或 [📎 名](hermes-media://token) 链接。 */
    data class Attachment(
        val name: String,
        /** 小文件内联：非空时直接解码。 */
        val dataUrl: String = "",
        /** 大文件网关托管：非空时按需带鉴权下载。 */
        val token: String = "",
    ) : MdBlock()

    /** 富卡片：助手正文里独占一行的 CARD:{json} 解析而来。 */
    data class Card(val card: HermesCard) : MdBlock()
}

/**
 * 富卡片模型。与 hermes-relay 的 HermesCard 对齐但精简：
 * 标题 / 副标题 / 正文（纯文本，仍按段落渲染）/ 字段表 / 按钮 / 页脚 / 强调色。
 * 未知字段一律忽略（对方加新字段不炸旧包）。
 */
data class HermesCard(
    val title: String = "",
    val subtitle: String = "",
    val body: String = "",
    /** 字段表：name → value，按顺序显示成两列。 */
    val fields: List<Pair<String, String>> = emptyList(),
    val actions: List<CardAction> = emptyList(),
    val footer: String = "",
    /** 强调色：info / success / warning / danger，其余按 info。 */
    val accent: String = "info",
)

/** 卡片上的一个按钮。action 决定点了做什么。 */
data class CardAction(
    val label: String,
    /** send_text（默认，把 value 当消息发出去）/ open_url（浏览器打开）/ slash_command（当斜杠命令发）。 */
    val action: String = "send_text",
    val value: String = "",
)

/**
 * 解析一行 CARD:{json}。容错：字段缺失用默认值，JSON 坏了返回 null（当普通文本）。
 */
fun parseCard(json: String): HermesCard? = runCatching {
    val o = org.json.JSONObject(json)
    val fields = mutableListOf<Pair<String, String>>()
    o.optJSONObject("fields")?.let { fo ->
        val keys = fo.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            fields.add(k to fo.optString(k, ""))
        }
    }
    val actions = mutableListOf<CardAction>()
    o.optJSONArray("actions")?.let { ao ->
        for (i in 0 until ao.length()) {
            val a = ao.optJSONObject(i) ?: continue
            val label = a.optString("label", "")
            if (label.isEmpty()) continue
            actions.add(CardAction(label, a.optString("action", "send_text"), a.optString("value", "")))
        }
    }
    HermesCard(
        title = o.optString("title", ""),
        subtitle = o.optString("subtitle", ""),
        body = o.optString("body", ""),
        fields = fields,
        actions = actions,
        footer = o.optString("footer", ""),
        accent = o.optString("accent", "info"),
    )
}.getOrNull()

/** 独占一行的内联图片：![alt](data:image/...) */
private val MD_IMG_LINE_RE = Regex("^!\\[([^\\]]*)\\]\\((data:image/[^)]+)\\)$")

/** 独占一行的附件链接：[📎 文件名](data:...) */
private val MD_ATT_LINE_RE = Regex("^\\[\\uD83D\\uDCCE ([^\\]]+)\\]\\(((?:data:|hermes-media://)[^)]+)\\)$")

/** 去掉内联强调标记：双星号加粗、反引号代码——手机窄屏上留着反而难看。 */
internal fun cleanInline(s: String): String =
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
            val cardJson = if (s.startsWith("CARD:")) s.removePrefix("CARD:").trim() else null
            when {
                img != null -> { flushPending(); out.add(MdBlock.Image(img.groupValues[1], img.groupValues[2])) }
                cardJson != null -> {
                    // 富卡片：解析成功才成卡片，坏了就当普通段落（绝不吞掉正文）
                    val card = parseCard(cardJson)
                    if (card != null) { flushPending(); out.add(MdBlock.Card(card)) }
                    else pending.append(line).append("\n")
                }
                att != null -> {
                    flushPending()
                    val name = att.groupValues[1]
                    val target = att.groupValues[2]
                    // 大文件走网关托管：只带 token，点开时再按需下载
                    if (target.startsWith("hermes-media://")) {
                        out.add(MdBlock.Attachment(name, token = target.removePrefix("hermes-media://")))
                    } else {
                        out.add(MdBlock.Attachment(name, dataUrl = target))
                    }
                }
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
    var inFence = false
    while (i < lines.size) {
        val t = lines[i].trim()
        // 围栏状态：``` 内的管道表不能当真表格渲染。
        if (t.startsWith("```")) {
            inFence = !inFence
            buf.append(lines[i]).append("\n")
            i++
            continue
        }
        if (!inFence && t.startsWith("|") && i + 1 < lines.size && isSeparator(lines[i + 1].trim())) {
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
