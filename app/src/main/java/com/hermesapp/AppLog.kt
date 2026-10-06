package com.hermesapp

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：把「发送 / 起流 / 收流 / 断流 / 重连 / 状态探测」这些关键节点按时间记下来。
 *
 * 为什么需要它：报「一直重连、连不上」时，界面上只有一行 retryNote，看不到究竟卡在
 * 哪一跳——DNS 解析不了、TLS 握手失败、服务端 4xx/5xx、探测超时，还是 run 其实还在跑
 * 而流被系统掐了。手机上没法抓包，只能让 App 自己把每一跳的结果留痕。
 *
 * 位置：filesDir/run.log（App 私有，卸载即清）。内存里同时保留最近若干行供界面即时显示，
 * 落盘用于「复制全文」发出来定位。绝不抛异常——它自己再崩就没意义了。
 */
object AppLog {
    private const val FILE = "run.log"
    private const val MAX_MEM = 400          // 内存保留行数
    private const val MAX_FILE = 2000        // 落盘保留行数
    private const val MAX_FILE_BYTES = 600_000L

    private val mem = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val lock = Any()
    private var app: Context? = null

    fun install(ctx: Context) {
        app = ctx.applicationContext
        log("app", "日志启动，版本 " + versionName(ctx))
    }

    fun log(tag: String, msg: String) = write(tag, msg, null)

    fun err(tag: String, msg: String, t: Throwable?) = write(tag, msg, t)

    private fun write(tag: String, msg: String, t: Throwable?) {
        val sb = StringBuilder()
        sb.append(fmt.format(Date())).append(" [").append(tag).append("] ").append(msg)
        if (t != null) {
            sb.append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message ?: "")
        }
        val line = sb.toString()
        val c = app
        // 内存队列与文件追加放同一把锁里：原来只锁内存，多个线程同时
        // appendText/trim 会互相截断（trim 把别的线程刚写的行覆盖掉），
        // 表现为日志莫名少行——正是排查断流时最需要的那几行。
        synchronized(lock) {
            mem.addLast(line)
            while (mem.size > MAX_MEM) mem.removeFirst()
            if (c == null) return
            runCatching {
                val f = File(c.filesDir, FILE)
                f.appendText(line + "\n")
                if (f.length() > MAX_FILE_BYTES) trim(f)
            }
        }
    }

    /** 只保留最近 MAX_FILE 行，避免日志无限增长。 */
    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size <= MAX_FILE) return
        f.writeText(lines.takeLast(MAX_FILE).joinToString("\n", postfix = "\n"))
    }

    /** 日志全文（落盘优先，没有则用内存）。绝不抛异常。 */
    fun read(ctx: Context): String = runCatching {
        val f = File(ctx.filesDir, FILE)
        if (f.exists() && f.length() > 0) f.readText()
        else synchronized(lock) { mem.joinToString("\n") }
    }.getOrDefault("")

    /** 最近 n 行（界面预览用，避免一次渲染上万行卡住）。 */
    fun tail(ctx: Context, n: Int): String {
        val all = read(ctx)
        if (all.isEmpty()) return ""
        return all.split('\n').takeLast(n).joinToString("\n")
    }

    fun clear(ctx: Context) {
        synchronized(lock) { mem.clear() }
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun versionName(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.versionName + " (" + pi.longVersionCode + ")"
    }.getOrDefault("?")
}
