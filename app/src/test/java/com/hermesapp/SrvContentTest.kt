package com.hermesapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 服务端历史行 content 投影（2.215 真事故）：带图消息的 content 是「分块数组」
 * （文本块 + 内联图片 data URL）。旧实现一律 optString，数组被序列化成 20 万字的 JSON
 * 文本气泡，首屏渲染把主线程拖死 → 用户报「App 卡死」。
 */
class SrvContentTest {

    private val caption = "“https://tokengo.example-old.com/v1”在客户端用这个地址，怎么没看到\"auto\"模型？"
    private val bigB64 = "A".repeat(120_000)

    /** 服务端对带图用户消息的真实形状。 */
    private fun realRow(): JSONObject {
        val parts = JSONArray()
        parts.put(JSONObject().put("type", "text")
            .put("text", caption + "\n\n[Image attached at: ~/.hermes/profiles/friend/cache/images/img_733ea5ec6696.jpg]"))
        parts.put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + bigB64)))
        return JSONObject().put("id", 197920).put("role", "user").put("content", parts)
    }

    @Test
    fun contentText_takes_caption_and_drops_inline_image() {
        val t = contentText(realRow())
        assertEquals(caption, t)
        assertFalse("正文里绝不能留下 base64", t.contains("base64"))
        assertFalse("图注要剥掉（它是服务端加的，不参与比对）", t.contains("Image attached"))
        assertTrue("投影后必须是短正文", t.length < 200)
    }

    @Test
    fun optString_would_stringify_the_whole_array_so_we_must_not_use_it() {
        // 这条钉住「为什么不能 optString」：数组会被 String.valueOf 成整段 JSON。
        val naive = realRow().optString("content", "")
        assertTrue(naive.length > 100_000)
        assertTrue(naive.contains("base64"))
        assertTrue(naive != caption)
    }

    @Test
    fun contentText_handles_plain_string_and_object_and_empty() {
        assertEquals("你好", contentText(JSONObject().put("content", "你好")))
        assertEquals("", contentText(JSONObject().put("content", "")))
        assertEquals("", contentText(JSONObject()))
        assertEquals("正文", contentText(JSONObject().put("content", JSONObject().put("type", "text").put("text", "正文"))))
        // 只有图片、没打字：投影成空正文（不把 base64 当文字）
        val only = JSONArray().put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/png;base64," + bigB64)))
        assertEquals("", partsToText(only))
    }

    @Test
    fun stripImageHints_removes_markers_and_placeholders() {
        assertEquals("你好", stripImageHints("你好\n\n[Image attached at: ~/x.jpg]"))
        assertEquals("你好", stripImageHints("你好\n[screenshot]"))
        assertEquals("你好", stripImageHints("你好\n@image:`~/x.jpg`"))
        assertEquals("你好", stripImageHints("你好"))
    }

    @Test
    fun sanitizeLoadedMessages_fixes_poisoned_row_and_drops_duplicate_projection() {
        // 现场形状：本地那条（带图片缩略图、回执）在前，被写坏的投影行紧随其后（同一时刻）。
        val local = Msg("user", caption, ts = 1_792_324_205_015, images = listOf("file:///a.jpg"))
        val poisonedRaw = realRow().getJSONArray("content").toString()
        val poisoned = Msg("user", poisonedRaw, ts = 1_792_324_205_838)
        val answer = Msg("assistant", "查清了，也修了", ts = 1_792_325_009_436)
        val out = sanitizeLoadedMessages(listOf(local, poisoned, answer))
        assertEquals("投影行与它对应的本地消息是同一条：必须丢掉，不能留成重复气泡", 2, out.size)
        assertEquals(caption, out[0].text)
        assertTrue(out[0].images.isNotEmpty())
        assertEquals("查清了，也修了", out[1].text)
    }

    @Test
    fun sanitizeLoadedMessages_keeps_projection_when_no_counterpart() {
        // 别人从别的端发的带图消息（本机没有对应本地行）：正文要还原，行要留着。
        val poisonedRaw = realRow().getJSONArray("content").toString()
        val only = Msg("user", poisonedRaw, ts = 1_792_324_205_838)
        val out = sanitizeLoadedMessages(listOf(Msg("assistant", "在", ts = 1), only))
        assertEquals(2, out.size)
        assertEquals(caption, out[1].text)
        assertTrue(out[1].text.length < 200)
    }

    @Test
    fun sanitizeLoadedMessages_leaves_normal_messages_untouched() {
        val a = Msg("user", "看图\n\n![img](data:image/png;base64," + bigB64 + ")", ts = 1)
        val b = Msg("assistant", "收到", ts = 2)
        val out = sanitizeLoadedMessages(listOf(a, b))
        assertEquals(2, out.size)
        assertEquals(a.text, out[0].text)
        assertEquals(b.text, out[1].text)
    }

    @Test
    fun capPara_truncates_with_a_note_and_keeps_short_text_intact() {
        assertEquals("短正文", capPara("短正文"))
        val long = "汉".repeat(MAX_PARA_CHARS + 500)
        val capped = capPara(long)
        assertTrue(capped.startsWith("汉".repeat(100)))
        assertTrue(capped.contains("已省略 500 字"))
        assertTrue(capped.length < long.length)
    }

    @Test
    fun render_path_caps_giant_paragraph() {
        // 兜底：即便正文没被投影（例如别的来源），渲染层也不许把 20 万字一次性铺出来。
        val blocks = parseMdBlocks("X".repeat(200_000))
        assertEquals(1, blocks.size)
        val para = blocks[0] as MdBlock.Para
        assertTrue(para.text.length < 21_000)
        assertTrue(para.text.contains("已省略"))
    }

    @Test
    fun looksLikeContentArray_is_narrow() {
        assertTrue(looksLikeContentArray("[{\"type\":\"text\",\"text\":\"a\"}]"))
        assertFalse(looksLikeContentArray("普通正文"))
        assertFalse(looksLikeContentArray("[1,2,3]"))
        assertFalse(looksLikeContentArray("[{\"a\":1}]"))
        assertFalse(looksLikeContentArray(""))
    }
}
