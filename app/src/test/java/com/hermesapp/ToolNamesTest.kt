package com.hermesapp

import com.hermesapp.net.SseEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过程轨迹显示层的两条用户定版要求：
 * ① 工具名要中文（不铺内部标识符给用户）；
 * ② 耗时不能一律 0.0s（服务端 canonical turn 链路不给 duration）。
 */
class ToolNamesTest {

    @Test
    fun known_tools_are_chinese() {
        assertEquals("执行命令", toolNameZh("terminal"))
        assertEquals("改代码", toolNameZh("patch"))
        assertEquals("读文件", toolNameZh("read_file"))
        assertEquals("查记忆", toolNameZh("hindsight_recall"))
    }

    @Test
    fun unknown_tool_keeps_original_name() {
        // 插件/自定义工具随时新增：不能空、不能崩、也不能瞎编
        assertEquals("my_custom_tool", toolNameZh("my_custom_tool"))
        assertEquals("", toolNameZh(""))
    }

    @Test
    fun mcp_tool_shows_last_segment() {
        val zh = toolNameZh("mcp__codebase_memory__search_code")
        assertTrue(zh.startsWith("外部·"))
        assertTrue(zh.contains("search_code"))
    }

    @Test
    fun duration_format_never_shows_zero() {
        assertEquals("", fmtToolDuration(-1.0))
        assertEquals("<0.1s", fmtToolDuration(0.0))
        assertEquals("<0.1s", fmtToolDuration(0.049))
        assertEquals("0.35s", fmtToolDuration(0.35))
        assertEquals("1.2s", fmtToolDuration(1.23))
        assertEquals("65s", fmtToolDuration(65.3))
    }

    private fun ev(tool: String, duration: Double? = null, preview: String = ""): SseEvent {
        val o = JSONObject()
        o.put("tool", tool)
        if (duration != null) o.put("duration", duration)
        if (preview.isNotEmpty()) o.put("preview", preview)
        return SseEvent(1, "tool.completed", o)
    }

    @Test
    fun tool_line_uses_chinese_name_and_computed_duration() {
        val line = toolLine(ev("terminal"), failed = false, durationSec = 1.4)
        assertTrue(line.contains("执行命令"))
        assertTrue(line.contains("1.4s"))
        assertFalse(line.contains("terminal"))
    }

    @Test
    fun tool_line_omits_duration_when_unknown() {
        // 服务端 duration=0 且本地没记到开始时刻：不显示耗时，绝不写 0.0s
        val line = toolLine(ev("patch", duration = 0.0), failed = false, durationSec = -1.0)
        assertTrue(line.contains("改代码"))
        assertFalse(line.contains("0.0s"))
    }

    @Test
    fun tool_line_marks_failure_and_keeps_preview() {
        val line = toolLine(ev("read_file", preview = "/root/a.kt"), failed = true, durationSec = 0.02)
        assertTrue(line.contains("✗"))
        assertTrue(line.contains("<0.1s"))
        assertTrue(line.contains("/root/a.kt"))
    }

    @Test
    fun tool_line_shows_the_command_from_started_preview() {
        // 只写「执行命令 2.3s」太笼统：明细行要用 started 事件的入参预览（= 命令本身）
        val line = toolLine(ev("terminal"), failed = false, durationSec = 2.3, previewOverride = "python3 sync.py --dry-run")
        assertTrue(line.contains("执行命令"))
        assertTrue(line.contains("2.3s"))
        assertTrue(line.contains("$ python3 sync.py --dry-run"))
    }

    @Test
    fun tool_line_falls_back_to_event_preview() {
        val line = toolLine(ev("read_file", preview = "/root/a.kt"), failed = false, durationSec = 0.2)
        assertTrue(line.contains("/root/a.kt"))
    }

    @Test
    fun tool_line_truncates_long_command_but_keeps_it_recognisable() {
        val cmd = "bash -lc " + "x".repeat(400)
        val line = toolLine(ev("terminal"), failed = false, durationSec = 1.0, previewOverride = cmd)
        val detail = line.lines().first { it.trim().startsWith("$") }
        assertTrue(detail.trim().endsWith("…"))
        assertTrue(detail.trim().length <= 150)
    }

    @Test
    fun step_count_counts_steps_not_lines() {
        // 用户实测：5 个操作显示「10 步」——每步至少两行（标题 + 参数预览），
        // 旧实现数换行就是两倍。
        val trace = "\n· ✓ 看技能  <0.1s\n\n· ✓ 执行命令  3.6s\n   ls -la\n" +
            "\n· ✓ 执行命令  0.20s\n   git status\n\n· ✓ 看图片  11s\n   /root/a.png\n" +
            "\n· ✓ 改技能  <0.1s\n"
        assertEquals(5, traceStepCount(trace))
    }

    @Test
    fun step_count_ignores_server_status_lines() {
        val trace = "\n· 服务端：上游等待 3s\n\n· ✓ 执行命令  1.0s\n"
        assertEquals(1, traceStepCount(trace))
    }

    @Test
    fun step_count_falls_back_to_content_lines() {
        // 老数据/别的来源没有步骤标记：按非空行算，不能显示 0 步
        assertEquals(2, traceStepCount("旧格式行一\n旧格式行二\n"))
        assertEquals(0, traceStepCount(""))
    }
}
