package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-14 事故的回归用例（纯函数层）。
 *
 * 事故：手机上侧边栏的 4 条会话一起消失，索引文件只剩 26 字节
 * （`{"schema":2,"sessions":[]}`）。日志实据：
 *   11:32:02.109 ChatViewModel 新建（Activity 重建）
 *   11:32:02.121 切会话 -> 9ce357ab（UI 恢复上次停留的会话）
 *   11:32:02.426 存索引 profile=friend 条数=0     ← 空列表被写盘
 *   11:32:02.560 启动读索引 条数=0 → 启动分支=空态
 * 也就是「切会话 → saveCurrentAsync → saveIndexAsync（300ms 去抖）」跑在
 * bootstrapSessions 把索引读进内存之前，落盘那一刻内存列表还是空的。
 *
 * 这里把三道防线钉死：① 索引未读完不许写；② 不许把非空索引写成空；
 * ③ 索引为空但正文文件还在时按文件重建。真机文件层的等价用例见
 * SessionStoreIndexRecoveryTest。
 */
class IndexGuardTest {

    // ---------- ① 索引未就绪：一律不写（事故根因） ----------

    @Test
    fun not_ready_blocks_write_even_with_entries() {
        // 事故那一刻 _sessions.value 就是空的，落盘把 4 条索引覆盖成 26B 空数组。
        assertFalse(indexWriteAllowed(indexReady = false, listSize = 0,
            lastKnownCount = 4, allowEmpty = false))
        // 即便列表非空也不写：此刻内存里的列表还没跟磁盘对齐，写了就是拿半成品覆盖。
        assertFalse(indexWriteAllowed(indexReady = false, listSize = 4,
            lastKnownCount = 4, allowEmpty = false))
    }

    @Test
    fun ready_allows_normal_write() {
        assertTrue(indexWriteAllowed(indexReady = true, listSize = 4,
            lastKnownCount = 4, allowEmpty = false))
        assertTrue(indexWriteAllowed(indexReady = true, listSize = 5,
            lastKnownCount = 4, allowEmpty = false))
    }

    // ---------- ② 非空 → 空的写入必须显式放行 ----------

    @Test
    fun empty_over_nonempty_is_refused_by_default() {
        assertFalse(indexWriteAllowed(indexReady = true, listSize = 0,
            lastKnownCount = 4, allowEmpty = false))
    }

    @Test
    fun empty_is_allowed_only_with_explicit_flag() {
        // 「清空全部对话」「删掉最后一个会话」两条路径传 allowEmpty = true。
        assertTrue(indexWriteAllowed(indexReady = true, listSize = 0,
            lastKnownCount = 4, allowEmpty = true))
    }

    @Test
    fun empty_on_empty_disk_is_fine() {
        // 本来就一条都没有（首次启动、空态），写空不是「覆盖」，放行。
        assertTrue(indexWriteAllowed(indexReady = true, listSize = 0,
            lastKnownCount = 0, allowEmpty = false))
    }

    // ---------- ③ 索引为空时按正文文件重建 ----------

    @Test
    fun rebuild_needed_only_when_index_empty_and_files_exist() {
        assertTrue(shouldRebuildIndexFromFiles(indexEntryCount = 0, messageFileCount = 4))
        // 索引里有条目：列表就该来自索引，别被文件结果盖掉
        assertFalse(shouldRebuildIndexFromFiles(indexEntryCount = 4, messageFileCount = 4))
        // 一条正文都没有：重建只会得到空列表，照原样走空态
        assertFalse(shouldRebuildIndexFromFiles(indexEntryCount = 0, messageFileCount = 0))
    }

    // ---------- 重建标题与首次发消息同一口径 ----------

    @Test
    fun title_from_first_user_text() {
        assertEquals("游戏脚本的问题，修好了吗？", deriveTitle("游戏脚本的问题，修好了吗？"))
        // 超 20 字截断并加省略号（与 touchSession 原来的写法一致）
        val long = "一二三四五六七八九十一二三四五六七八九十一二三四五"
        assertEquals(long.take(20) + "…", deriveTitle(long))
        // 换行/多空格折叠成一个空格
        assertEquals("a b c", deriveTitle("a \n b\tc "))
        // 空 / 只有空白（含全角空格与非换行空格）→ 新对话
        assertEquals("新对话", deriveTitle(null))
        assertEquals("新对话", deriveTitle("   "))
        assertEquals("新对话", deriveTitle("\u3000\u00a0"))
    }
}
