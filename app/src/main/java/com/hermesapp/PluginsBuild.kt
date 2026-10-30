package com.hermesapp

import org.json.JSONObject

/**
 * 插件页的一项（来自服务端 `GET /api/plugins`）。
 *
 * 服务端的判定口径与 CLI `hermes plugins list` 同源：`status` 是 enabled / not enabled / disabled，
 * `source` 是 bundled（内置）/ user（第三方）/ entrypoint；只有第三方允许从 App 启停。
 */
data class PluginItem(
    val name: String,
    val key: String,
    val version: String,
    val description: String,
    val source: String,
    val status: String,
    val toggleable: Boolean,
) {
    val enabled: Boolean get() = status == "enabled"
}

/** 插件页的分类档位（按钮平铺，不用下拉）。默认停「第三方」。 */
enum class PluginTab(val label: String) {
    THIRD_PARTY("第三方"),
    BUNDLED("内置"),
    DISABLED("已停用"),
}

/**
 * 插件页数据：清单 + 各档计数 + 服务端是否支持该端点。
 *
 * `supported=false` 表示老网关没有 /api/plugins —— 界面要说「服务端版本较旧」，
 * 而不是显示一个空列表让人以为插件装丢了。
 */
data class PluginsModel(
    val items: List<PluginItem> = emptyList(),
    val total: Int = 0,
    val enabledCount: Int = 0,
    val disabledCount: Int = 0,
    val thirdPartyCount: Int = 0,
    val supported: Boolean = true,
)

/** 解析服务端响应。字段缺失一律降级成空串/默认值，绝不因一条脏数据整页失败。 */
internal fun parsePlugins(raw: JSONObject): PluginsModel {
    val arr = raw.optJSONArray("plugins") ?: return PluginsModel(supported = false)
    val out = ArrayList<PluginItem>(arr.length())
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name", "").trim()
        if (name.isEmpty()) continue
        out.add(
            PluginItem(
                name = name,
                key = o.optString("key", name),
                version = o.optString("version", ""),
                description = o.optString("description", ""),
                source = o.optString("source", ""),
                status = o.optString("status", ""),
                toggleable = o.optBoolean("toggleable", false),
            )
        )
    }
    val counts = raw.optJSONObject("counts")
    return PluginsModel(
        items = out,
        total = counts?.optInt("total", out.size) ?: out.size,
        enabledCount = counts?.optInt("enabled", out.count { it.enabled }) ?: out.count { it.enabled },
        disabledCount = counts?.optInt("disabled", out.count { it.status == "disabled" })
            ?: out.count { it.status == "disabled" },
        thirdPartyCount = counts?.optInt("third_party", out.count { it.source == "user" })
            ?: out.count { it.source == "user" },
        supported = true,
    )
}

/**
 * 按档位筛选并排序：启用在前、未启用在后，同状态按名称。
 * 纯函数，便于单测（界面上四个按钮切换时走这里，避免每处各写一遍过滤条件）。
 */
internal fun filterPlugins(items: List<PluginItem>, tab: PluginTab): List<PluginItem> {
    val kept = when (tab) {
        PluginTab.THIRD_PARTY -> items.filter { it.source == "user" }
        PluginTab.BUNDLED -> items.filter { it.source != "user" }
        PluginTab.DISABLED -> items.filter { it.status == "disabled" || it.status == "not enabled" }
    }
    return kept.sortedWith(
        compareByDescending<PluginItem> { it.enabled }
            .thenByDescending { it.status == "enabled" }
            .thenBy { it.name.lowercase() }
    )
}

/** 状态徽章文案（只做显示层翻译，状态值本身仍按服务端原样保存与回传）。 */
internal fun pluginStatusZh(status: String): String = when (status) {
    "enabled" -> "已启用"
    "disabled" -> "已停用"
    "not enabled" -> "未启用"
    else -> if (status.isEmpty()) "未知" else status
}

/** 来源文案：bundled 内置 / user 第三方 / entrypoint 包安装。 */
internal fun pluginSourceZh(source: String): String = when (source) {
    "bundled" -> "内置"
    "user" -> "第三方"
    "entrypoint" -> "包安装"
    else -> if (source.isEmpty()) "" else source
}
