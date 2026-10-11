package com.hermesapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键清空全部对话」两条边界的纯 JVM 校验（不依赖设备/模拟器）：
 *   1) 盘上哪些文件该扫掉、哪些必须留下（isClearableFileName）
 *   2) prefs 里哪些键属于「对话内容」、哪些是身份级配置不能连坐（isConversationContentPrefKey）
 *
 * 这两个函数是清空的全部判断逻辑，实际删除动作（File.delete / prefs 扫表）靠真机点一次验收。
 */
class ClearAllSessionsTest {

    // ---------- 盘上文件 ----------

    @Test
    fun own_session_files_are_clearable() {
        assertTrue(isClearableFileName("chat_default_abc-123.json", "default"))
        assertTrue(isClearableFileName("queue_default_abc-123.json", "default"))
        // 归档会话（侧边栏「已归档」里的那些）也属于「全部对话」
        assertTrue(isClearableFileName("chat_default_abc-123.archive.json", "default"))
    }

    @Test
    fun backups_and_temps_go_too() {
        // 只删主文件会留下 .bak：用户删掉的对话还能被回退读回来，等于没删干净
        assertTrue(isClearableFileName("chat_default_abc-123.json.bak", "default"))
        assertTrue(isClearableFileName("chat_default_abc-123.json.tmp", "default"))
        assertTrue(isClearableFileName("queue_default_abc-123.json.bak", "default"))
    }

    @Test
    fun legacy_14_file_is_cleared() {
        // 不清的话，下次重启 migrateLegacy 会把它当成一条「历史对话」补回列表
        assertTrue(isClearableFileName("chat_default.json", "default"))
    }

    @Test
    fun index_exports_and_attachments_survive() {
        // 索引由调用方写空，导出与附件不是对话本身，都不该被清空连坐
        assertFalse(isClearableFileName("sessions_default.json", "default"))
        assertFalse(isClearableFileName("exports", "default"))
        assertFalse(isClearableFileName("attachments", "default"))
        assertFalse(isClearableFileName("hermes.log", "default"))
    }

    @Test
    fun other_profiles_survive() {
        assertFalse(isClearableFileName("chat_friend_abc.json", "default"))
        assertFalse(isClearableFileName("queue_friend_abc.json", "default"))
        assertFalse(isClearableFileName("chat_friend.json", "default"))
    }

    @Test
    fun prefix_lookalikes_are_not_swept() {
        // profile="a" 时，"chat_ab_x.json" 属于身份 "ab"，结尾下划线保证不误伤
        assertFalse(isClearableFileName("chat_ab_x.json", "a"))
        assertTrue(isClearableFileName("chat_a_x.json", "a"))
    }

    @Test
    fun empty_profile_never_sweeps() {
        // 空 profile 会让 "chat_" 前缀扫掉所有身份的文件，宁可不删
        assertFalse(isClearableFileName("chat_default_abc.json", ""))
        assertFalse(isClearableFileName("chat_default.json", ""))
        assertFalse(isClearableFileName("", "default"))
        // 畸形文件名：前缀后没有内容
        assertFalse(isClearableFileName("chat_default_", "default"))
        assertFalse(isClearableFileName("queue_default_", "default"))
    }

    // ---------- prefs 键 ----------

    @Test
    fun conversation_content_keys_are_cleared() {
        assertTrue(isConversationContentPrefKey("draft_input:abc-123"))
        assertTrue(isConversationContentPrefKey("run:abc-123"))
        assertTrue(isConversationContentPrefKey("seq:abc-123"))
        assertTrue(isConversationContentPrefKey("ptitle:abc-123"))
        // 通知栏直接回复的暂存：一段用户打的字 + 它属于哪个会话
        assertTrue(isConversationContentPrefKey("pending_reply"))
        assertTrue(isConversationContentPrefKey("pending_reply_profile"))
    }

    @Test
    fun credentials_and_settings_survive() {
        // 连坐凭据 = 把用户踢下线，属于事故
        assertFalse(isConversationContentPrefKey("credential:default"))
        assertFalse(isConversationContentPrefKey("credential:friend"))
        assertFalse(isConversationContentPrefKey("logged_in"))
        assertFalse(isConversationContentPrefKey("theme_mode"))
        assertFalse(isConversationContentPrefKey("server_url"))
        assertFalse(isConversationContentPrefKey("keep_alive"))
        assertFalse(isConversationContentPrefKey("play_completion_voice"))
        assertFalse(isConversationContentPrefKey("voice_rate"))
        assertFalse(isConversationContentPrefKey("notified_reports"))
        assertFalse(isConversationContentPrefKey("shell_cleanup_done"))
        // 身份/指针类由调用方单独处理，不在扫表范围
        assertFalse(isConversationContentPrefKey("profile"))
        assertFalse(isConversationContentPrefKey("system"))
        assertFalse(isConversationContentPrefKey("session_id"))
    }

    @Test
    fun prefix_must_include_the_colon() {
        assertFalse(isConversationContentPrefKey("runner"))
        assertFalse(isConversationContentPrefKey("selected_model"))
        assertFalse(isConversationContentPrefKey("draft_input"))
        assertFalse(isConversationContentPrefKey("pending_reply_extra"))
        assertFalse(isConversationContentPrefKey(""))
    }
}
