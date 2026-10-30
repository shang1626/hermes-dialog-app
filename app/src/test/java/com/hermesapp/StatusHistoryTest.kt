package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态页 CPU 折线序列（StatusHistory）。
 *
 * 2.231 起数据**只来自服务端** `/api/sysinfo/cpu_history`（网关 5 秒采一点、10 分钟窗口），
 * App 侧只做「整段替换 + 截断」，不再本地追加采样点。所以这里只验三件事：
 *  ① 顺序与数值原样保留（左旧右新，不做插值、不补假点）；
 *  ② 超过 120 点只留最近 120；
 *  ③ 越界的脏值被夹回 0..100，空输入不动已有曲线。
 */
class StatusHistoryTest {

    @Test
    fun seed_fills_curve_in_order() {
        val h = StatusHistory().seedCpu(listOf(10f, 20f, 30f))
        assertEquals(listOf(10f, 20f, 30f), h.cpu)
    }

    @Test
    fun seed_caps_at_max_points_keeping_newest() {
        // 用 0..99 的循环值（都在合法区间内），避免和「越界夹取」那条混在一起
        val many = (0 until 200).map { (it % 100).toFloat() }
        val h = StatusHistory().seedCpu(many)
        assertEquals(StatusHistory.MAX_POINTS, h.cpu.size)
        // 窗口=120 点：留下来的应该是最后 120 个，最新一点在末尾
        assertEquals((80 % 100).toFloat(), h.cpu.first(), 0.001f)
        assertEquals((199 % 100).toFloat(), h.cpu.last(), 0.001f)
    }

    @Test
    fun seed_clamps_out_of_range_values() {
        val h = StatusHistory().seedCpu(listOf(-5f, 42f, 250f))
        assertEquals(listOf(0f, 42f, 100f), h.cpu)
    }

    @Test
    fun seed_with_empty_list_keeps_existing_curve() {
        val h0 = StatusHistory().seedCpu(listOf(1f, 2f, 3f))
        val h1 = h0.seedCpu(emptyList())
        assertEquals(h0.cpu, h1.cpu)
        assertTrue("空列表不得把曲线清空", h1.cpu.isNotEmpty())
    }

    @Test
    fun reseed_replaces_whole_series_not_appends() {
        // 每 5 秒拉一次历史：必须整段替换，否则点会无限增长 / 重复堆叠
        val h0 = StatusHistory().seedCpu(listOf(1f, 2f, 3f))
        val h1 = h0.seedCpu(listOf(4f, 5f))
        assertEquals(listOf(4f, 5f), h1.cpu)
    }

    @Test
    fun left_label_follows_real_span() {
        // 网关刚重启：只有 40 秒的点，横轴不能写「10 分钟前」
        assertEquals("约 1 分钟前", StatusHistory().seedCpu(listOf(1f, 2f), span = 40).leftLabel())
        // 攒满 10 分钟
        assertEquals("约 10 分钟前", StatusHistory().seedCpu(listOf(1f, 2f), span = 595).leftLabel())
        // 没给跨度时按满窗口算（服务端窗口就是 600 秒）
        assertEquals("约 10 分钟前", StatusHistory().seedCpu(listOf(1f, 2f)).leftLabel())
    }
}
