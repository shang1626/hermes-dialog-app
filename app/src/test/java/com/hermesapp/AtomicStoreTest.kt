package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 阶段2 单测第 4 块：原子落盘与回退（掉电/半截文件场景）。
 */
class AtomicStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun write_then_read_roundtrip() {
        val f = File(tmp.root, "a.json")
        AtomicStore.writeAtomic(f, "hello")
        assertEquals("hello", AtomicStore.readTextOrBackup(f))
    }

    @Test
    fun overwrite_leaves_backup_of_previous() {
        val f = File(tmp.root, "b.json")
        AtomicStore.writeAtomic(f, "v1")
        AtomicStore.writeAtomic(f, "v2")
        assertEquals("v2", AtomicStore.readTextOrBackup(f))
        // 上一版进了 .bak
        assertEquals("v1", File(tmp.root, "b.json.bak").readText())
    }

    @Test
    fun no_tmp_file_left_behind() {
        val f = File(tmp.root, "c.json")
        AtomicStore.writeAtomic(f, "x")
        assertTrue("tmp must be renamed away", !File(tmp.root, "c.json.tmp").exists())
    }

    @Test
    fun missing_primary_falls_back_to_backup() {
        val f = File(tmp.root, "d.json")
        AtomicStore.writeAtomic(f, "v1")
        AtomicStore.writeAtomic(f, "v2")
        // 模拟掉电留下零长度主文件：内容读不出，回退 .bak
        f.writeText("")
        assertEquals("", AtomicStore.readTextOrBackup(f))
        // 主文件整个消失时回退备份
        assertTrue(f.delete())
        assertEquals("v1", AtomicStore.readTextOrBackup(f))
    }

    @Test(expected = java.io.FileNotFoundException::class)
    fun no_backup_throws_original() {
        val f = File(tmp.root, "e.json")
        AtomicStore.readTextOrBackup(f)
    }
}
