package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段4 单测：从 ChatViewModel 抽出的历史合并 / 锚点纯逻辑。
 * 这几个函数是「同步重复堆叠」「断流翻历史认错答案」两类历史 bug 的核心，
 * 抽出来正是为了能在动主状态机前先把行为钉住。
 */
class ChatMergeTest {

    private fun u(text: String, ts: Long = 0L) = Msg("user", text, ts = ts)
    private fun a(text: String, ts: Long = 0L) = Msg("assistant", text, ts = ts)

    @Test
    fun merge_keeps_local_only_rows_and_appends_server_new_ones() {
        val local = listOf(u("你好"), a("本地答案"))
        val srv = listOf(u("你好"), a("本地答案"), a("服务端新增的答案"))
        val merged = mergeByUserAnchor(local, srv)
        // 用户消息不重复，助手行按服务端顺序对齐
        assertEquals(1, merged.count { it.role == "user" })
        assertTrue(merged.any { it.text == "服务端新增的答案" })
        assertTrue(merged.any { it.text == "本地答案" })
    }

    @Test
    fun merge_is_idempotent_when_run_twice() {
        val local = listOf(u("q"), a("answer"))
        val srv = listOf(u("q"), a("answer"))
        val once = mergeByUserAnchor(local, srv)
        val twice = mergeByUserAnchor(once, srv)
        assertEquals(once.size, twice.size)
    }

    @Test
    fun merge_skips_server_user_row_not_present_locally() {
        // 服务端被压缩删掉的老用户消息：本地没有也不该凭空补进来
        val local = listOf(u("第二问"), a("第二答"))
        val srv = listOf(u("第一问"), a("第一答"), u("第二问"), a("第二答"))
        val merged = mergeByUserAnchor(local, srv)
        assertTrue(merged.any { it.text == "第二问" })
    }

    @Test
    fun resolve_anchor_finds_prior_th_user_message() {
        val rows = listOf(
            HistRow("user", "一问", "1"),
            HistRow("assistant", "一答", "2"),
            HistRow("user", "二问", "3"),
        )
        assertEquals(2, resolveAnchor(rows, "二问", 1))
        assertEquals(0, resolveAnchor(rows, "一问", 0))
    }

    @Test
    fun resolve_anchor_rejects_text_mismatch() {
        val rows = listOf(HistRow("user", "一问", "1"))
        assertEquals(-1, resolveAnchor(rows, "对不上的文本", 0))
        assertEquals(-1, resolveAnchor(rows, "一问", 5))
    }

    @Test
    fun answer_signature_changes_when_server_appends() {
        val rows = listOf(
            HistRow("user", "问", "1"),
            HistRow("assistant", "答", "2"),
        )
        val sig1 = answerSignature(rows, 0)
        val rows2 = rows + HistRow("assistant", "又一段", "3")
        val sig2 = answerSignature(rows2, 0)
        assertTrue(sig1 != null && sig2 != null)
        assertTrue("signature must change when server appends", sig1 != sig2)
    }

    @Test
    fun quote_snippet_labels_role_and_truncates() {
        assertEquals("我：你好", quoteSnippet(u("你好")))
        assertEquals("助手：[图片或附件]", quoteSnippet(a("")))
        val long = "x".repeat(200)
        val snip = quoteSnippet(u(long))
        assertTrue(snip.startsWith("我："))
        assertTrue(snip.length < 100)
    }

    @Test
    fun quote_snippet_collapses_newlines() {
        assertEquals("我：a b c", quoteSnippet(u("a\nb\nc")))
    }
}
