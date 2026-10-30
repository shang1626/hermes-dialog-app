package com.hermesapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件页的数据解析与筛选（纯函数）。
 *
 * 重点是三件容易出错的事：
 *  ① 老网关没有 /api/plugins（返回体没 plugins 数组）→ supported=false，界面提示「服务端较旧」而不是空列表；
 *  ② 状态/来源的显示文案是本地映射，状态值本身必须原样保留（回传给服务端用）；
 *  ③ 分类筛选：第三方只看 user、内置看非 user、已停用含 disabled 与 not enabled，且启用项排前面。
 */
class PluginsBuildTest {

    private fun raw(json: String) = JSONObject(json)

    @Test
    fun parse_reads_counts_and_items() {
        val m = parsePlugins(raw("""
            {"counts":{"total":3,"enabled":2,"disabled":1,"third_party":1},
             "plugins":[
               {"name":"agnes","key":"agnes","version":"1.0.0","description":"d","source":"user","status":"enabled","toggleable":true},
               {"name":"browser-firecrawl","key":"browser/firecrawl","version":"1.0.0","description":"","source":"bundled","status":"enabled","toggleable":false},
               {"name":"old-one","key":"old-one","version":"0.9","description":"","source":"user","status":"disabled","toggleable":true}
             ]}
        """.trimIndent()))
        assertTrue(m.supported)
        assertEquals(3, m.total)
        assertEquals(2, m.enabledCount)
        assertEquals(1, m.thirdPartyCount)
        assertEquals(3, m.items.size)
        assertEquals("browser/firecrawl", m.items[1].key)
        assertFalse("内置不可切换", m.items[1].toggleable)
    }

    @Test
    fun parse_without_array_means_old_gateway() {
        val m = parsePlugins(raw("""{"detail":"not found"}"""))
        assertFalse("没有 plugins 数组 = 老网关，界面要提示升级", m.supported)
        assertTrue(m.items.isEmpty())
    }

    @Test
    fun parse_skips_items_without_name() {
        val m = parsePlugins(raw("""
            {"plugins":[{"key":"k1","status":"enabled"},{"name":"good","key":"good","status":"enabled"}]}
        """.trimIndent()))
        assertEquals(1, m.items.size)
        assertEquals("good", m.items[0].name)
    }

    @Test
    fun labels_are_local_only() {
        assertEquals("已启用", pluginStatusZh("enabled"))
        assertEquals("已停用", pluginStatusZh("disabled"))
        assertEquals("未启用", pluginStatusZh("not enabled"))
        assertEquals("内置", pluginSourceZh("bundled"))
        assertEquals("第三方", pluginSourceZh("user"))
        // 未知值原样透出，不吞成空
        assertEquals("weird-state", pluginStatusZh("weird-state"))
    }

    private fun item(name: String, source: String, status: String) = PluginItem(
        name = name, key = name, version = "1.0", description = "",
        source = source, status = status, toggleable = source == "user",
    )

    @Test
    fun filter_third_party_only_user_source() {
        val items = listOf(
            item("agnes", "user", "enabled"),
            item("browser-x", "bundled", "enabled"),
            item("hindsight", "user", "disabled"),
        )
        val out = filterPlugins(items, PluginTab.THIRD_PARTY)
        assertEquals(listOf("agnes", "hindsight"), out.map { it.name })
    }

    @Test
    fun filter_bundled_excludes_user() {
        val items = listOf(item("agnes", "user", "enabled"), item("browser-x", "bundled", "enabled"))
        assertEquals(listOf("browser-x"), filterPlugins(items, PluginTab.BUNDLED).map { it.name })
    }

    @Test
    fun filter_disabled_includes_not_enabled_and_sorts_enabled_first() {
        val items = listOf(
            item("zzz", "bundled", "not enabled"),
            item("aaa", "bundled", "disabled"),
            item("mmm", "bundled", "enabled"),
        )
        val out = filterPlugins(items, PluginTab.DISABLED)
        // enabled 的 mmm 不在这档；两个停用项按名字排
        assertEquals(listOf("aaa", "zzz"), out.map { it.name })
    }
}
