package com.hermesapp

/**
 * 会话内搜索：纯逻辑，不碰界面状态，便于在后台线程调用、也便于单测。
 *
 * 只搜当前会话已经落在本地的消息（上限 300 条），范围含正文与工具轨迹。
 * 跨会话搜索不在这里。
 */
object ChatSearch {

    /**
     * 命中的消息 id，按会话顺序返回；一条消息只算一次（正文或轨迹命中都算）。
     * 大小写不敏感；查询词两侧空白忽略。
     */
    fun matchIds(messages: List<Msg>, query: String): List<Long> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return emptyList()
        val out = ArrayList<Long>()
        for (m in messages) {
            val hit = m.text.lowercase().contains(needle) ||
                (m.trace.isNotEmpty() && m.trace.lowercase().contains(needle))
            if (hit) out.add(m.id)
        }
        return out
    }
}
