package com.hermesapp

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 运行日志：把「发送 / 起流 / 收流 / 断流 / 重连 / 状态探测 / 网络请求 / 回执 / 审批 /
 * 澄清 / 排队 / 插话 / 停止 / 前台服务 / 附件」这些关键节点按时间记下来。
 *
 * 为什么需要它：报「一直重连、连不上」时，界面上只有一行 retryNote，看不到究竟卡在
 * 哪一跳——DNS 解析不了、TLS 握手失败、服务端 4xx/5xx、探测超时，还是 run 其实还在跑
 * 而流被系统掐了。手机上没法抓包，只能让 App 自己把每一跳的结果留痕。
 *
 * 位置：filesDir/run.log（App 私有，卸载即清）。内存里同时保留最近若干行供界面即时显示，
 * 落盘用于「复制全文」或「导出文件」发出来定位。绝不抛异常——它自己再崩就没意义了。
 *
 * 分级：错误行带 `ERROR ` 前缀（形如 `10-07 10:05:12.345 [ERROR send] ...`），
 * 排查时直接搜 `ERROR` 就能把出问题的行挑出来。
 */
object AppLog {
    private const val FILE = "run.log"
    private const val MAX_MEM = 1200         // 内存保留行数
    private const val MAX_FILE = 8000        // 落盘保留行数
    private const val MAX_FILE_BYTES = 4_000_000L

    private val mem = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.CHINA)
    private val lock = Any()
    private var app: Context? = null

    fun install(ctx: Context) {
        app = ctx.applicationContext
        // 首行写环境摘要：只发一段日志就能知道是哪台设备、哪个版本、什么系统。
        log("app", "日志启动 " + env(ctx))
    }

    /** 环境摘要：版本 / 机型 / Android / 进程 pid。发日志时这一行就能交代清环境。 */
    fun env(ctx: Context): String =
        "版本=" + versionName(ctx) +
            " 机型=" + Build.MANUFACTURER + " " + Build.MODEL +
            " Android=" + Build.VERSION.RELEASE + "(API " + Build.VERSION.SDK_INT + ")" +
            " pid=" + android.os.Process.myPid()

    fun log(tag: String, msg: String) = write(tag, msg, null)

    /** 错误：tag 前加 `ERROR ` 前缀，方便按关键字筛出来。t 可省。 */
    fun err(tag: String, msg: String, t: Throwable? = null) = write("ERROR " + tag, msg, t)

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

    /**
     * 把日志全文落进可分享目录 exports/（file_paths.xml 已声明，可直接 FileProvider 分享），
     * 返回文件；失败返回 null。给「导出日志文件」用——直接发文件比复制粘贴更省事、
     * 也不会被剪贴板长度截断。
     */
    fun exportToFile(ctx: Context): File? = runCatching {
        val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
        val name = "run-" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date()) + ".log"
        val f = File(dir, name)
        val text = read(ctx)
        f.writeText(if (text.isEmpty()) "(空日志)" else text)
        f
    }.getOrNull()

    fun clear(ctx: Context) {
        synchronized(lock) { mem.clear() }
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun versionName(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.versionName + " (" + pi.longVersionCode + ")"
    }.getOrDefault("?")
}