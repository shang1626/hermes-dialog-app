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

    /**
     * 待落盘队列 + 唯一写线程。
     *
     * 为什么改成异步：原来每条日志都在调用线程上 appendText（一次 open/write/close），
     * 必要时还读全文截断；而日志几乎遍布每个关键路径（流式期间每条事件一条）。这些开销
     * 全落在 UI 线程上，切会话、滚动、流式渲染都会被它拖慢。改成入队 + 单写线程批量落盘后，
     * 调用线程只做一次入队（微秒级），且只有一个线程写文件，不会互相截断。
     * ERROR 行仍然立即落盘 —— 崩溃前那几行必须在文件里。
     */
    private val pending = java.util.concurrent.ConcurrentLinkedQueue<String>()
    @Volatile private var writerThread: Thread? = null
    private const val MAX_PENDING = 5000

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
        // 内存队列（界面即时显示用）：仍然要在锁里维护，多个线程会同时写。
        synchronized(lock) {
            mem.addLast(line)
            while (mem.size > MAX_MEM) mem.removeFirst()
        }
        if (c == null) return
        while (pending.size > MAX_PENDING) pending.poll()   // 极端情况丢最旧的，绝不无界增长
        pending.add(line)
        // 错误行也走写线程：原来 ERROR 行当场调 drainToFile，与 400ms
        // 写线程并发 appendText/trim 同一文件，会丢行或撕裂行。
        ensureWriter(c)
    }

    /** 唯一写线程：每 400ms 批量落盘一次，避免每条日志一次 open/write/close。 */
    private fun ensureWriter(c: Context) {
        if (writerThread?.isAlive == true) return
        synchronized(lock) {
            if (writerThread?.isAlive == true) return
            val th = Thread {
                while (true) {
                    try {
                        Thread.sleep(400L)
                        drainToFile(c)
                    } catch (e: InterruptedException) {
                        return@Thread
                    } catch (e: Throwable) {
                        // 写日志自己绝不往外抛
                    }
                }
            }
            th.isDaemon = true
            th.name = "hermes-log"
            writerThread = th
            th.start()
        }
    }

    /** 把队列里的行一次性追加落盘。只有写线程（或 ERROR 行当场）会进来。 */
    private fun drainToFile(c: Context) {
        // 整个方法进同一把锁：写线程每 400ms 调、回后台/读日志时主线程
        // flush() 也调，不锁会两边 appendText/trim 同一文件互相覆盖/撕裂行。
        synchronized(lock) {
            if (pending.isEmpty()) return
            val batch = StringBuilder()
            while (true) {
                val l = pending.poll() ?: break
                batch.append(l).append('\n')
            }
            runCatching {
                val f = File(c.filesDir, FILE)
                f.appendText(batch.toString())
                if (f.length() > MAX_FILE_BYTES) trim(f)
            }
        }
    }

    /** 立刻把待写的日志落盘（退到后台 / 进程可能被杀之前调）。 */
    fun flush() {
        val c = app ?: return
        runCatching { drainToFile(c) }
    }

    /** 只保留最近 MAX_FILE 行，避免日志无限增长。 */
    private fun trim(f: File) {
        val lines = f.readLines()
        if (lines.size <= MAX_FILE) return
        f.writeText(lines.takeLast(MAX_FILE).joinToString("\n", postfix = "\n"))
    }

    /** 日志全文（落盘优先，没有则用内存）。绝不抛异常。 */
    fun read(ctx: Context): String = runCatching {
        flush()   // 先把队列里还没落盘的写下去，界面/导出看到的是最新的
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