package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「统计」页的纯格式化函数单测：token 缩写、时长、千分位、来源/用途中文名。
 *
 * 只测纯函数——buildStats 要 org.json（JVM 单测里是桩），改由真机联调覆盖。
 */
class StatsFormatTest {

    @Test
    fun fmtTok_units() {
        assertEquals("0", fmtTok(0))
        assertEquals("999", fmtTok(999))
        assertEquals("1.0K", fmtTok(1000))
        assertEquals("240.8K", fmtTok(240762))
        assertEquals("1.2M", fmtTok(1_234_567))
        assertEquals("926.8M", fmtTok(926_800_000))
        assertEquals("2.24B", fmtTok(2_235_539_181))
    }

    @Test
    fun fmtTok_negative_is_zero() {
        assertEquals("0", fmtTok(-5))
    }

    @Test
    fun fmtNum_thousands_separator() {
        assertEquals("0", fmtNum(0))
        assertEquals("999", fmtNum(999))
        assertEquals("1,000", fmtNum(1000))
        assertEquals("13,623", fmtNum(13623))
        assertEquals("2,235,539,181", fmtNum(2235539181))
    }

    @Test
    fun fmtDur_tiers() {
        assertEquals("38 秒", fmtDur(38))
        assertEquals("45 分", fmtDur(45 * 60 + 20))
        assertEquals("3 小时 12 分", fmtDur(3 * 3600 + 12 * 60 + 59))
        assertEquals("0 秒", fmtDur(-1))
    }

    @Test
    fun labels_map_known_values() {
        assertEquals("App", sourceLabel("api_server"))
        assertEquals("微信", sourceLabel("weixin"))
        assertEquals("定时任务", sourceLabel("cron"))
        assertEquals("子任务", sourceLabel("subagent"))
        assertEquals("其它", sourceLabel(""))
        assertEquals("主对话", taskLabel("main"))
        assertEquals("压缩摘要", taskLabel("compression"))
        assertEquals("原样保留未知", taskLabel("原样保留未知"))
    }
}
