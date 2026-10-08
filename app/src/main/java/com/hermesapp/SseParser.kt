package com.hermesapp

/**
 * SSE 行解析器（纯逻辑，无 Android / org.json 依赖，便于单测）。
 *
 * 服务端每个事件是**单行** `data: {json}` 后跟一个空行（`gateway/platforms/api_server.py`
 * 的事件发射处），内容全在 JSON 字符串里、换行是转义 `\n`——所以这里对 data 行做 trim
 * 不会破坏内容（多行 data 拼接在本架构下永不触发）。
 *
 * 用法：逐行 [onLine]，遇到空行且已攒到 data 时返回一个 [RawSseEvent]（data 仍是原始
 * JSON 字符串，由调用方决定何时解析）；其余行返回 null。keepalive 注释帧（`:` 开头）
 * 落不到任何分支、返回 null，由调用方在 [onLine] 之外单独刷「流还活着」时间戳。
 */
class SseParser {

    private var id: Int? = null
    private var event: String? = null
    private val data = StringBuilder()

    /**
     * 喂入一行。返回本次解析出的事件（仅空行且 data 非空时）；否则 null。
     */
    fun onLine(line: String): RawSseEvent? {
        return when {
            line.startsWith("id:") -> {
                id = line.substring(3).trim().toIntOrNull()
                null
            }
            line.startsWith("event:") -> {
                event = line.substring(6).trim()
                null
            }
            line.startsWith("data:") -> {
                data.append(line.substring(5).trim())
                null
            }
            line.isEmpty() -> {
                val out = if (data.isNotEmpty()) RawSseEvent(id, event, data.toString()) else null
                id = null
                event = null
                data.setLength(0)
                out
            }
            else -> null
        }
    }
}

/** 解析出的一条原始 SSE 事件；data 是未解析的 JSON 字符串。 */
data class RawSseEvent(val id: Int?, val event: String?, val data: String)
