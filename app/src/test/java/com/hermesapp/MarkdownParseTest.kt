package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段2 单测第 2 块：Markdown 纯解析（代码围栏 + 表格 + 内联清理）。
 */
class MarkdownParseTest {

    @Test
    fun table_outside_fence_is_parsed_as_table() {
        val src = "| a | b |\n|---|---|\n| 1 | 2 |"
        val blocks = parseMdBlocks(src)
        assertEquals(1, blocks.size)
        val t = blocks[0]
        assertTrue("expected Table, got " + t, t is MdBlock.Table)
        val rows = (t as MdBlock.Table).rows
        // 分隔行（|---|---|）只用于识别表头，不进 rows：剩表头 + 一行数据 = 2 行。
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("1", "2"), rows[1])
    }

    @Test
    fun pipe_inside_code_fence_is_not_a_table() {
        val src = "```\n| a | b |\n|---|---|\n| 1 | 2 |\n```"
        val blocks = parseMdBlocks(src)
        // 不得产出任何 Table —— 围栏内的管道是代码，不是表格
        assertTrue(
            "fence content must not become a table: " + blocks,
            blocks.none { it is MdBlock.Table },
        )
        // 且内容以段落形式保留
        assertTrue(blocks.any { it is MdBlock.Para })
    }

    @Test
    fun cleanInline_strips_bold_and_backticks() {
        assertEquals("bold code", cleanInline("**bold** `code`"))
    }

    @Test
    fun attachment_line_is_recognized() {
        val src = "[\uD83D\uDCCE report.md](hermes-media://tok123)"
        val blocks = parseMdBlocks(src)
        val att = blocks.filterIsInstance<MdBlock.Attachment>()
        assertEquals(1, att.size)
        assertEquals("report.md", att[0].name)
        assertEquals("tok123", att[0].token)
    }

    @Test
    fun plain_paragraph_preserved() {
        val blocks = parseMdBlocks("hello **world**")
        assertTrue(blocks.any { it is MdBlock.Para })
    }
}
