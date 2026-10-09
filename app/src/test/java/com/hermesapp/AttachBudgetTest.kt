package com.hermesapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * R16 回归：附件发送必须走有界读，超限时中止而不是整包进堆。
 *
 * 这些用例正是「旧代码 readBytes() 无边界」会被判失败的地方：
 *  - 超过 limit 的文件返回 null（旧代码会读进来）；
 *  - 正好等于 limit 的允许；
 *  - 不存在的文件返回 null（不能抛异常打断发送流程）。
 */
class AttachBudgetTest {

    @Test
    fun readFileBounded_readsFileUnderLimit() {
        val f = File.createTempFile("bounded", ".bin")
        try {
            val data = ByteArray(1024) { (it % 251).toByte() }
            f.writeBytes(data)
            val got = readFileBounded(f, 4096L)
            assertArrayEquals(data, got)
        } finally {
            f.delete()
        }
    }

    @Test
    fun readFileBounded_allowsExactlyLimit() {
        val f = File.createTempFile("bounded", ".bin")
        try {
            val data = ByteArray(2048) { 7 }
            f.writeBytes(data)
            val got = readFileBounded(f, 2048L)
            assertEquals(2048, got?.size)
        } finally {
            f.delete()
        }
    }

    @Test
    fun readFileBounded_rejectsOverLimit() {
        val f = File.createTempFile("bounded", ".bin")
        try {
            f.writeBytes(ByteArray(4096) { 1 })
            // 旧代码 readBytes() 在这里会返回 4096 字节的数组；有界读必须拒绝。
            assertNull(readFileBounded(f, 4096L - 1))
        } finally {
            f.delete()
        }
    }

    @Test
    fun readFileBounded_missingFileIsNull() {
        val f = File(System.getProperty("java.io.tmpdir"), "definitely-not-here-r16.bin")
        f.delete()
        assertNull(readFileBounded(f, 1024L))
    }

    @Test
    fun attachBudget_boundary() {
        assertFalse(exceedsAttachBudget(0L, 64L * 1024 * 1024))
        assertTrue(exceedsAttachBudget(0L, 64L * 1024 * 1024 + 1))
        assertTrue(exceedsAttachBudget(64L * 1024 * 1024, 1L))
    }
}
