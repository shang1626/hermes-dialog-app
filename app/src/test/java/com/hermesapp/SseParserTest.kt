package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 阶段2 单测第 3 块：SSE 行解析（单行 data / id / event / 空行提交 / 心跳忽略）。
 */
class SseParserTest {

    @Test
    fun single_data_line_emits_event_on_blank() {
        val p = SseParser()
        assertNull(p.onLine("id: 7"))
        assertNull(p.onLine("event: message"))
        assertNull(p.onLine("data: {\"a\":1}"))
        val e = p.onLine("")
        assertEquals(7, e?.id)
        assertEquals("message", e?.event)
        assertEquals("{\"a\":1}", e?.data)
    }

    @Test
    fun blank_without_data_emits_nothing() {
        val p = SseParser()
        assertNull(p.onLine(""))
    }

    @Test
    fun keepalive_comment_is_ignored() {
        val p = SseParser()
        assertNull(p.onLine(": keepalive"))
        // 心跳之后 data 帧仍能正常提交
        assertNull(p.onLine("data: {}"))
        val e = p.onLine("")
        assertEquals("{}", e?.data)
    }

    @Test
    fun parser_resets_between_events() {
        val p = SseParser()
        p.onLine("id: 1")
        p.onLine("data: {\"x\":1}")
        val e1 = p.onLine("")
        assertEquals(1, e1?.id)
        // 第二个事件没有 id，不应继承上一个
        p.onLine("data: {\"x\":2}")
        val e2 = p.onLine("")
        assertNull(e2?.id)
        assertEquals("{\"x\":2}", e2?.data)
    }

    @Test
    fun multi_data_lines_are_concatenated() {
        val p = SseParser()
        p.onLine("data: {\"a\":")
        p.onLine("data: 1}")
        val e = p.onLine("")
        assertEquals("{\"a\":1}", e?.data)
    }
}
