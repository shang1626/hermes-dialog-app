package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定时任务中文化的回归用例。
 *
 * 用户反复报「任务页又有英文了 / 卡片上没有『这是干嘛的』那一句」——根因都是
 * 服务端加了任务，App 的 JobFormat.kt 没跟上。名单取 2026-10-15 审计时的实况
 * （default 12 条 + friend 1 条）；服务端以后再加任务，把名字补进 ROSTER 并补
 * 翻译即可，补充之前这里会直接转红。
 */
class JobFormatTest {

    /** 2026-10-15 审计时的服务端任务名单（jobs.json 实况）。 */
    private val roster = listOf(
        "browser-idle-reaper",
        "gradle-idle-reaper",
        "apk-keep-30",
        "patch-registry-audit",
        "上游优先级自动切换",
        "FreeLLMAPI密钥用途守卫",
        "Hindsight 每日巡检",
        "Hindsight回填保活",
        "Hindsight回填验收",
        "归纳时间窗管制",
        "browser-chain-check",
        "归纳完成通知",
        "ds-upstream-watch",
    )

    private fun hasCjk(s: String) = s.any { it.code in 0x4E00..0x9FFF }

    @Test
    fun 每个现存任务的显示名都是中文不是英文原名() {
        for (name in roster) {
            val shown = jobDisplayName(name, "job-id")
            assertTrue("$name 的显示名回落成英文/空：$shown", hasCjk(shown))
        }
    }

    @Test
    fun 每个现存任务都有这任务是干嘛的这一句备注() {
        for (name in roster) {
            val note = jobZhNote(name)
            assertTrue("$name 没有备注，卡片上会缺「这是干嘛的」那一句", note.isNotEmpty())
            assertTrue("$name 的备注不像中文：$note", hasCjk(note))
        }
    }

    @Test
    fun browser_chain_check走精确表不落笼统兜底() {
        assertEquals("浏览器链路自检", jobZhName("browser-chain-check"))
        val note = jobZhNote("browser-chain-check")
        assertTrue(note, note.contains("Chromium"))
        assertTrue(note, note.contains("包装脚本"))
        assertTrue(note, note.contains("守护"))
    }

    @Test
    fun apk_keep_30备注写的是每天四点不再写每十五分钟() {
        val note = jobZhNote("apk-keep-30")
        assertTrue(note, note.contains("04:00"))
        assertTrue(note, note.contains("只保留最近 30 个"))
        assertFalse("备注频率文案又和 jobs.json 的日程脱节了：$note", note.contains("每 15 分钟"))
    }

    @Test
    fun 上游优先级备注写始终优先ClinePass并含换腿冷却() {
        val note = jobZhNote("上游优先级自动切换")
        assertTrue(note, note.contains("始终优先 ClinePass"))
        assertTrue(note, note.contains("5 小时"))
        assertTrue(note, note.contains("高峰段"))
    }

    @Test
    fun Hindsight与归纳相关任务的备注带关键频率与断言() {
        assertTrue(jobZhNote("Hindsight 每日巡检").contains("09:20"))
        assertTrue(jobZhNote("Hindsight 每日巡检").contains("5 条断言"))
        assertTrue(jobZhNote("Hindsight回填保活").contains("每 10 分钟"))
        assertTrue(jobZhNote("Hindsight回填验收").contains("每 20 分钟"))
        assertTrue(jobZhNote("归纳时间窗管制").contains("高峰"))
        assertTrue(jobZhNote("归纳完成通知").contains("3 次"))
    }

    @Test
    fun 未收录的任务名回落原始名而不是空() {
        assertEquals("brand-new-job", jobDisplayName("brand-new-job", "id-1"))
        assertEquals("id-1", jobDisplayName("", "id-1"))
        assertEquals("", jobZhNote("brand-new-job-完全没见过的中文名"))
    }
}
