package com.hermesapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 2026-10-14 事故的文件层回归：真在临时目录里跑索引读写（用的是 App 同一条代码路径）。
 *
 * 三个用例分别对应三道防线：
 *   1) 事故复现 —— 索引被写成空数组后，能从 .bak 把 4 条找回来；
 *   2) 存储层闸门 —— 空列表写入被拒；显式清空才写空，且连备份一起清（否则"清空后又冒出来"）；
 *   3) 第二道网 —— 索引与备份都没了，但正文文件还在时按文件重建列表。
 */
class SessionStoreIndexRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SessionStore(tmp.root, "friend")

    private fun metas(vararg ids: String): List<SessionMeta> =
        ids.mapIndexed { i, id -> SessionMeta(id, "会话$i", 1_700_000_000_000L + i, false, 0L) }

    private fun indexFile() = File(tmp.root, "sessions_friend.json")

    private fun writeMessages(id: String, firstUserText: String) {
        val arr = JSONArray()
        arr.put(JSONObject().put("role", "user").put("text", firstUserText).put("ts", 1L))
        File(tmp.root, "chat_friend_$id.json").writeText(arr.toString())
    }

    @Test
    fun incident_replay_empty_index_recovers_from_backup() {
        val s = store()
        val list = metas("aaaa1111", "bbbb2222", "cccc3333", "dddd4444")
        s.saveIndex(list)
        assertEquals(4, s.lastKnownCount)

        // 复刻事故现场：旧版竞态把 26 字节的空数组写了上去
        // （writeAtomic 会把上一份完整索引留成 .bak，这也是能恢复的原因）。
        AtomicStore.writeAtomic(
            indexFile(),
            JSONObject().put("schema", 2).put("sessions", JSONArray()).toString()
        )
        assertEquals(26, indexFile().length().toInt())

        // 新版本启动：主索引是「合法但空」→ 改用备份恢复，4 条会话回到列表
        val loaded = store().loadIndex()
        assertEquals(4, loaded.size)
        assertEquals(list.map { it.id }, loaded.map { it.id })
        assertEquals("会话0", loaded[0].title)
    }

    @Test
    fun empty_write_is_refused_and_explicit_clear_drops_backup() {
        val s = store()
        s.saveIndex(metas("aaaa1111", "bbbb2222"))

        // 磁盘上还有 2 条，写空被拒（这就是挡掉事故的那道理）
        s.saveIndex(emptyList())
        assertEquals(2, store().loadIndex().size)
        assertEquals(2, s.lastKnownCount)

        // 显式清空（清空全部对话 / 删掉最后一个会话）才写空，且备份一并删掉
        s.saveIndex(emptyList(), allowEmpty = true)
        assertEquals(0, s.lastKnownCount)
        assertEquals(0, store().loadIndex().size)
        assertFalse("显式清空后 .bak 必须一起清掉，否则下次启动会把它恢复回来",
            File(tmp.root, "sessions_friend.json.bak").exists())
    }

    @Test
    fun rebuild_from_message_files_when_index_and_backup_are_lost() {
        writeMessages("aaaa1111", "游戏脚本的问题，修好了吗？")
        writeMessages("bbbb2222", "DS-PLAY 的问题修好了吗？")
        writeMessages("cccc3333", "hermes 现在用的什么记忆系统？")
        // 备份/临时/归档文件不是正文文件，不能算进重建
        File(tmp.root, "chat_friend_dddd4444.json.bak").writeText("[]")
        File(tmp.root, "chat_friend_eeee5555.json.tmp").writeText("[]")
        File(tmp.root, "chat_friend_ffff6666.archive.json").writeText("[]")
        // 别的 profile 的文件也不能算进来
        File(tmp.root, "chat_default_99999999.json").writeText("[]")

        val s = store()
        assertEquals(0, s.loadIndex().size)
        assertTrue(shouldRebuildIndexFromFiles(0, s.messageFileCount()))
        assertEquals(3, s.messageFileCount())

        val rebuilt = s.rebuildFromMessageFiles()
        assertEquals(3, rebuilt.size)
        assertEquals(setOf("aaaa1111", "bbbb2222", "cccc3333"), rebuilt.map { it.id }.toSet())
        // 标题取首条用户消息（与正常路径同一口径）
        assertEquals("游戏脚本的问题，修好了吗？", rebuilt.first { it.id == "aaaa1111" }.title)

        // 重建结果立刻写回索引：下次启动直读，不用再扫文件
        s.saveIndex(rebuilt)
        assertEquals(3, store().loadIndex().size)
    }

    @Test
    fun orphan_message_files_are_recovered_into_the_index() {
        val s = store()
        // 事故现场的真实形状：索引被写空后 App 又建了当前会话，索引里只剩 1 条（147 字节），
        // 而 4 条老会话的正文文件都还在目录里 → 靠「索引为空才重建」救不回来，得按文件补行。
        s.saveIndex(metas("1bc1600d"))
        writeMessages("9ce357ab", "查询hermes记忆系统")
        writeMessages("f8319ebd", "一站式脚本的问题，修好了吗？")
        writeMessages("96d27a62", "DS-PLAY的问题修好了吗？")
        writeMessages("b476eee2", "处理少华山拦路NPC")

        val idx = s.loadIndex()
        assertEquals(1, idx.size)
        val orphans = s.recoverOrphanSessions(idx.map { it.id }.toSet())
        assertEquals(4, orphans.size)
        assertEquals(setOf("9ce357ab", "f8319ebd", "96d27a62", "b476eee2"),
            orphans.map { it.id }.toSet())
        // 索引里已有的那条不能重复补
        assertFalse(orphans.any { it.id == "1bc1600d" })
        // 标题取首条用户消息
        assertEquals("处理少华山拦路NPC", orphans.first { it.id == "b476eee2" }.title)

        // 合并写回 → 下次启动直接是 5 条（4 条老会话 + 当前这条）
        s.saveIndex(idx + orphans)
        assertEquals(5, store().loadIndex().size)
    }

    @Test
    fun orphan_recovery_skips_files_without_messages() {
        writeMessages("aaaa1111", "有正文的会话")
        File(tmp.root, "chat_friend_bbbb2222.json").writeText("[]")   // 空壳文件，别当会话补回来
        val s = store()
        val orphans = s.recoverOrphanSessions(emptySet())
        assertEquals(1, orphans.size)
        assertEquals("aaaa1111", orphans[0].id)
    }
}
