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
    fun merge_fills_truncated_local_answer_with_server_full_text() {
        // 断线重连：本地只收到半截正文，服务端是完整版——必须被补齐
        val local = listOf(u("问题"), a("短答案"))
        val srv = listOf(u("问题"), a("短答案，新增关键说明"))
        val merged = mergeByUserAnchor(local, srv)
        val ans = merged.last { it.role == "assistant" }
        assertEquals("短答案，新增关键说明", ans.text)
    }

    @Test
    fun merge_keeps_local_metadata_when_filling_text() {
        // 补齐正文时不能丢掉本地独有的元数据（trace / runId）
        val local = listOf(u("q"), a("半截").copy(trace = "工具轨迹", runId = "run-1"))
        val srv = listOf(u("q"), a("半截，完整版"))
        val merged = mergeByUserAnchor(local, srv)
        val ans = merged.last { it.role == "assistant" }
        assertEquals("半截，完整版", ans.text)
        assertEquals("工具轨迹", ans.trace)
        assertEquals("run-1", ans.runId)
    }

    @Test
    fun merge_keeps_longer_local_answer() {
        // 本地更长（服务端压缩改写）时不能反向截短
        val local = listOf(u("q"), a("很长的本地正文内容"))
        val srv = listOf(u("q"), a("很短"))
        val merged = mergeByUserAnchor(local, srv)
        val ans = merged.last { it.role == "assistant" }
        assertEquals("很长的本地正文内容", ans.text)
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

    // ---- 过程被劈成上下两截（2026-10-31 实测：同一轮 23 步 + 96 步，中间夹着用户消息）----

    private fun blank(trace: String, ts: Long, startedAt: Long = ts) =
        Msg("assistant", "", pending = true, ts = ts, startedAt = startedAt, trace = trace)

    @Test
    fun merge_moves_orphan_blank_assistant_after_the_user_message() {
        // 恢复/接管抢在落盘读盘之前新起的占位气泡（后半段轨迹）被当成了「开头」，
        // 落盘那条（前半段轨迹）留在用户消息之后 —— 合并后必须归位成 用户 → 助手。
        val later = blank("· ✓ terminal 后半段", ts = 2_000L, startedAt = 2_000L)
        val user = Msg("user", "继续完善帝王/枭雄一站式脚本", ts = 1_000L)
        val earlier = blank("· ✓ terminal 前半段", ts = 1_000L, startedAt = 1_000L)
        val merged = mergeByUserAnchor(listOf(later, user, earlier), emptyList())
        assertEquals(listOf("user", "assistant"), merged.map { it.role })
        val asst = merged.last()
        assertEquals("· ✓ terminal 前半段\n· ✓ terminal 后半段", asst.trace)
        // 计时锚点取更早那次，不能从后半段重新计时（否则「耗时」显示被腰斩）
        assertEquals(1_000L, asst.startedAt)
        assertEquals(1_000L, asst.ts)
        assertTrue(asst.pending)
    }

    @Test
    fun merge_orphan_heal_is_idempotent() {
        val later = blank("后半段", ts = 2_000L)
        val user = Msg("user", "q", ts = 1_000L)
        val earlier = blank("前半段", ts = 1_000L)
        val once = mergeByUserAnchor(listOf(later, user, earlier), emptyList())
        val twice = mergeByUserAnchor(once, emptyList())
        assertEquals(once.size, twice.size)
        assertEquals(2, twice.size)
        assertEquals(1, twice.count { it.role == "assistant" })
    }

    @Test
    fun merge_keeps_head_assistant_with_text() {
        // 有正文的助手行属于上一轮的正常开头，不能被这条修复挪走
        val head = a("上一轮的收尾说明", ts = 500L)
        val user = Msg("user", "q", ts = 1_000L)
        val ans = a("本轮答案", ts = 2_000L)
        val merged = mergeByUserAnchor(listOf(head, user, ans), emptyList())
        assertEquals(listOf("assistant", "user", "assistant"), merged.map { it.role })
        assertEquals("上一轮的收尾说明", merged.first().text)
    }

    @Test
    fun heal_loaded_messages_repairs_poisoned_order() {
        // 落盘里已经是坏形状（内存为空时 ensureLoaded 直接赋值，不会走合并）
        val later = blank("后半段", ts = 2_000L)
        val user = Msg("user", "q", ts = 1_000L)
        val earlier = blank("前半段", ts = 1_000L)
        val healed = healSplitTurnBubbles(listOf(later, user, earlier))
        assertEquals(listOf("user", "assistant"), healed.map { it.role })
        assertEquals("前半段\n后半段", healed.last().trace)
        assertEquals(2, healed.size)
        // 再跑一遍不变（幂等，落盘/读盘反复安全）
        assertEquals(healed, healSplitTurnBubbles(healed))
    }

    @Test
    fun heal_leaves_normal_history_untouched() {
        val msgs = listOf(
            Msg("user", "一问", ts = 1_000L), a("一答", ts = 2_000L),
            Msg("user", "二问", ts = 3_000L), a("二答", ts = 4_000L),
        )
        assertEquals(msgs, healSplitTurnBubbles(msgs))
    }
}
