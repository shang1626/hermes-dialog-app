package com.hermesapp

import java.io.File

/**
 * 原子落盘（纯 java.io，无 Android 依赖，便于单测）。
 *
 * 为什么必须这样做：直接 writeText 一次性覆盖，进程在写盘途中被杀 / 磁盘满，
 * 留下的是半截文件；读方把解析异常吞掉返回空，界面表现就是「历史整片没了」。
 * 同目录内改名是原子的：读到的要么是旧完整版、要么是新完整版，不会是半截。
 *
 * 关键纪律：**rename 前必须 fsync**。rename 本身原子，但它会把「内容还在页缓存里的
 * 文件」原子地换上去，掉电时留下一个合法的零长度文件（不是可回退的损坏），回退也救不回。
 */
object AtomicStore {

    /**
     * 同一文件的写盘串行锁：两个协程并发写同一份 JSON 会交错截断。
     * 公开给 SessionStore 的删除路径共用——删除时清 .bak/.tmp 与并发写必须互斥。
     */
    val lock = Any()

    /**
     * 原子写：先写 .tmp（fsync 落盘），成功后改名覆盖，旧内容留一份 .bak。
     * 改名失败（极少数文件系统）时退回直接写，内容仍是对的，并清掉临时文件。
     */
    fun writeAtomic(f: File, text: String) {
        synchronized(lock) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            java.io.FileOutputStream(tmp).use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
                os.fd.sync()
            }
            if (f.exists()) {
                runCatching { f.copyTo(File(f.parentFile, f.name + ".bak"), overwrite = true) }
            }
            if (!tmp.renameTo(f)) {
                f.writeText(text)
                tmp.delete()
            }
        }
    }

    /**
     * 读文本，主文件读不出（缺失/损坏）时回退上一次的 .bak；两者都没有才抛出原异常。
     */
    fun readTextOrBackup(f: File): String {
        return try {
            f.readText()
        } catch (e: Exception) {
            val bak = File(f.parentFile, f.name + ".bak")
            if (bak.exists()) bak.readText() else throw e
        }
    }

    /**
     * 同 readTextOrBackup，但**主文件缺失**时也回退 .bak；两者都没有返回 null。
     *
     * 为什么单列一个（R13）：readTextOrBackup 只在「读主文件抛异常」时回退，而调用方
     * 常见写法是先 `if (!f.exists()) return` —— 主文件被删/没落盘时直接返回空，
     * 同目录里那份完好的 .bak 被白白绕过，用户看到「历史全没了」而内容其实可恢复。
     */
    fun readTextOrBackupOrNull(f: File): String? {
        runCatching { f.readText() }.getOrNull()?.let { return it }
        val bak = File(f.parentFile, f.name + ".bak")
        return runCatching { bak.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
