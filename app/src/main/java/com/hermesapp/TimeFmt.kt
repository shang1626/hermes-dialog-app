package com.hermesapp

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 全 App 统一按北京时间显示。 */
object TimeFmt {
    val BJ: ZoneId = ZoneId.of("Asia/Shanghai")
    private val FMT_HM = DateTimeFormatter.ofPattern("HH:mm")
    private val FMT_MDHM = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val FMT_HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun hm(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(BJ).format(FMT_HM)

    /** 只要时分秒（状态页「上次刷新 12:03:45」用）。 */
    fun hhmmss(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(BJ).format(FMT_HHMMSS)

    fun mdhm(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(BJ).format(FMT_MDHM)

    /** ISO-8601（带偏移或 Z）转北京时间；解析不了就原样返回。 */
    fun isoToBj(s: String): String {
        if (s.isEmpty()) return s
        runCatching { return OffsetDateTime.parse(s).atZoneSameInstant(BJ).format(FMT_MDHM) }
        runCatching { return Instant.parse(s).atZone(BJ).format(FMT_MDHM) }
        return s
    }
}
