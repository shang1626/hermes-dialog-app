package com.hermesapp

/**
 * 把 HTTP 错误压成一句人话。
 *
 * 为什么需要：网关重启/反代不可用时会回一整页 HTML（实测雷池 502 是 `<!DOCTYPE html>…` 一屏），
 * 旧代码把这段原文直接当回执提示挂到气泡上（`Receipt.FAILED/UNCERTAIN` 的 note），
 * 用户点开看到的就是一坨网页源码 —— 真机 2026-10-10 21:36:46 上报里就有一次。
 *
 * 只动**给用户看的那句**；排查用的原文照旧走 AppLog（见 ChatViewModel 的 `发送失败` 行）。
 */
object HttpNote {

    /** 正文超过这个长度就不再原样展示（正常错误正文都是一句话）。 */
    private const val MAX_PLAIN = 120

    fun friendly(msg: String): String {
        val m = msg.trim()
        val code = Regex("^HTTP (\\d{3})").find(m)?.groupValues?.get(1)
        val body = m.substringAfter("HTTP " + code, "").trim()
        val looksHtml = body.startsWith("<") ||
            body.contains("<html", ignoreCase = true) ||
            body.contains("<!doctype", ignoreCase = true)
        return when {
            code == null -> m.take(MAX_PLAIN)
            looksHtml && code.startsWith("5") ->
                "服务端暂时不可用（HTTP $code），可能在重启，稍后重发即可"
            looksHtml -> "服务端拒绝了这次请求（HTTP $code）"
            body.isEmpty() -> "服务端返回 HTTP $code"
            else -> ("HTTP $code " + body).take(MAX_PLAIN)
        }
    }
}
