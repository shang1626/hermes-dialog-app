package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 会话改名（侧边栏「重命名」）的纯 JVM 校验：标题归一 + 拒收原因。
 * 不依赖设备/模拟器。改名的网络同步部分在 ChatViewModel，靠服务端 PATCH 自测。
 */
class RenameTitleTest {

    @Test
    fun normalize_collapses_whitespace_and_trims() {
        assertEquals("我的对话", normalizeTitle("  我的对话  "))
        assertEquals("a b c", normalizeTitle("a\n\nb\t c"))
        assertEquals("标题", normalizeTitle("\u3000标题\u3000"))
        // 全角空格/不换行空格与 ASCII 空白一视同仁（服务端 Python 的 \s 就是这样）
        assertEquals("a b", normalizeTitle("a\u3000b"))
        assertEquals("a b", normalizeTitle("a\u00a0b"))
    }

    @Test
    fun blank_titles_are_rejected_locally() {
        assertEquals("标题不能为空", titleRejectReason(""))
        assertEquals("标题不能为空", titleRejectReason(normalizeTitle("   \n\t ")))
    }

    @Test
    fun length_limit_is_60_and_counts_chars_not_bytes() {
        val ok = "长".repeat(RENAME_TITLE_MAX)
        assertNull(titleRejectReason(ok))
        assertEquals("标题太长，最多 60 个字", titleRejectReason(ok + "长"))
        // 中文按字符计（不是 UTF-8 字节）：60 个汉字合法
        assertEquals(60, ok.length)
    }

    @Test
    fun normalize_then_check_is_the_order_the_viewmodel_uses() {
        // 前后一堆空白 + 60 字正文：归一后正好 60，应当放行（不能因为空白超长被误拒）
        val raw = "   " + "字".repeat(60) + "\n"
        assertNull(titleRejectReason(normalizeTitle(raw)))
    }
}
