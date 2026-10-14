package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每轮「召回了多少条记忆」这一行的解析（2026-10-14，图标 2026-10-15 改）。
 *
 * 测试输入全部取自真机证据：`journalctl -u hermes-gateway` 里逐轮打出的
 * `👁️ Hindsight — recalled N memories`（22:10:57 recalled 20、22:15:59 recalled 13、
 * 22:16:24 recalled 12、22:26:29 recalled 16、22:31:52 recalled 11）。
 *
 * 注意左右两侧的图标**不是同一个**：服务端原文仍是 👁️（我们不改服务端文案），
 * App 侧渲染的是 `⧫` —— 期望值里必须写 App 侧那个，别照抄输入。
 */
class RecallNoteTest {

    @Test
    fun reproduce_server_line_used_to_be_invisible_in_app() {
        // [复现] 旧行为：服务端原文是英文，而且根本没有进 App 的流（/v1/runs 没挂 status_callback），
        // 界面上一个字都没有 —— 用户看不到这轮用了几条记忆。
        val fromServer = "👁️ Hindsight — recalled 20 memories"
        assertTrue("服务端原文是英文", fromServer.contains("recalled 20 memories"))

        // [修复] 同一段输入在 App 侧压成一句中文提示。
        assertEquals("⧫ 本轮召回 20 条记忆", RecallNote.line(fromServer))
    }

    @Test
    fun singular_and_plural_are_worded_right() {
        assertEquals("⧫ 本轮召回 1 条记忆", RecallNote.line("👁️ Hindsight — recalled 1 memory"))
        assertEquals("⧫ 本轮召回 13 条记忆", RecallNote.line("👁️ Hindsight — recalled 13 memories"))
        assertEquals("⧫ 本轮召回 11 条记忆", RecallNote.line("👁️ Hindsight — recalled 11 memories"))
    }

    @Test
    fun reflect_answer_without_count_still_shows_a_line() {
        // reflect 的答案是合成文本、没有离散条数（服务端 count<=0 时就是这句）
        assertEquals("⧫ 本轮召回了相关记忆（条数不详）", RecallNote.line("recalled relevant memory"))
    }

    @Test
    fun non_recall_statuses_are_not_shown_as_recall_counts() {
        // 补丁同时会转发这些 lifecycle 状态，它们绝不能被当成召回量
        assertNull(RecallNote.line("上游等待中，已重试 1/6"))
        assertNull(RecallNote.line("自动恢复倒计时：8 秒后重试"))
        assertNull(RecallNote.line("模型切换：cline-pass → go47"))
        assertNull(RecallNote.line(""))
        assertNull(RecallNote.line("   "))
    }

    @Test
    fun whitespace_and_case_do_not_break_parsing() {
        assertEquals("⧫ 本轮召回 7 条记忆", RecallNote.line("  RECALLED 7 MEMORIES  "))
        assertTrue(RecallNote.isRecall("👁️ Hindsight — recalled 3 memories"))
        assertTrue(!RecallNote.isRecall("上游等待中"))
    }

    @Test
    fun icon_is_a_monochrome_symbol_not_a_color_emoji() {
        // 用户 2026-10-15 从预览图里挑了小黑菱；这条用例钉住「前缀是正文字体的几何符号」，
        // 防的是以后有人顺手换回彩色 emoji（emoji 字体不吃主题色，三套配色下都不受控）。
        assertEquals("⧫", RecallNote.ICON)
        assertEquals(1, RecallNote.ICON.length)
        assertEquals(0x29EB, RecallNote.ICON[0].code)
        // 彩色 emoji 通常靠 U+FE0F 变体选择符变成 emoji 呈现
        assertTrue(RecallNote.ICON.none { it.code == 0xFE0F })
    }
}
