package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回执提示不许把服务端的 HTML 错误页糊给用户（2026-10-10 真机：雷池 502 一屏 HTML）。
 *
 * 正文取自真机上报里的原文（App 日志 `发送失败 内容=HTTP 502: <!DOCTYPE html>...`）。
 */
class HttpNoteTest {

    private val real502 = "HTTP 502: <!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<link rel=\"icon\" href=\"/.safeline/static/favicon.png\" type=\"image/png\">"

    @Test
    fun reproduce_raw_html_body_used_to_be_shown() {
        // [复现] 旧行为：note 就是原文，用户看到一坨网页源码。
        val old = real502
        assertTrue(old.contains("<!DOCTYPE html>"))
        println("[复现] 旧回执提示=${old.take(60)}…（含 HTML 原文）")

        // [修复] 同一段输入压成一句人话。
        val fixed = HttpNote.friendly(real502)
        println("[修复] 新回执提示=$fixed")
        assertFalse(fixed.contains("<"))
        assertEquals("服务端暂时不可用（HTTP 502），可能在重启，稍后重发即可", fixed)
    }

    @Test
    fun html_4xx_is_reported_without_the_body() {
        assertEquals("服务端拒绝了这次请求（HTTP 403）",
            HttpNote.friendly("HTTP 403: <html><body>Forbidden</body></html>"))
    }

    @Test
    fun plain_text_body_is_kept() {
        assertEquals("HTTP 400 invalid api key",
            HttpNote.friendly("HTTP 400 invalid api key"))
        assertEquals("HTTP 500 internal error",
            HttpNote.friendly("HTTP 500 internal error"))
    }

    @Test
    fun code_only_and_bare_messages_stay_readable() {
        assertEquals("服务端返回 HTTP 504", HttpNote.friendly("HTTP 504"))
        assertEquals("Unable to resolve host \"x\": No address associated with hostname",
            HttpNote.friendly("Unable to resolve host \"x\": No address associated with hostname"))
    }

    @Test
    fun very_long_non_html_body_is_truncated() {
        val long = "HTTP 500 " + "x".repeat(500)
        assertEquals(120, HttpNote.friendly(long).length)
    }
}
