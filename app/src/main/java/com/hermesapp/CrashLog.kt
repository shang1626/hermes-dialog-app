package com.hermesapp

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃留痕：把未捕获异常的完整堆栈写进 App 私有目录 crash/last_crash.txt。
 *
 * 为什么需要它：闪退是进程被系统直接杀掉，界面、内存状态、日志全都留不下东西，
 * 只看代码只能猜是哪一行。装上它之后复现一次，堆栈就摆在那里，一眼能定位。
 *
 * 位置：filesDir/crash/last_crash.txt（App 私有，卸载即清），另留一份
 * last_crash_prev.txt 保存上一次，便于对比「是不是同一个错」。
 * 读取入口：设置页「上次闪退记录」（可复制全文）。
 *
 * 另有 service_fault.txt：非崩溃类的故障（前台服务启动失败等）。这类错误被
 * catch 住了、进程不会死，所以走不到未捕获处理器，但恰恰可能是「闪退」的真凶，
 * 必须单独留一份，否则永远是「看不到原因」。读取入口：设置页「服务故障记录」。
 */
object CrashLog {
    private const val DIR = "crash"
    private const val LAST = "last_crash.txt"
    private const val PREV = "last_crash_prev.txt"
    private const val FAULT = "service_fault.txt"

    fun file(ctx: Context): File = File(File(ctx.filesDir, DIR), LAST)

    /** 最近一次崩溃的全文；没有则返回空串。绝不抛异常。 */
    fun read(ctx: Context): String = runCatching {
        val f = file(ctx)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    fun clear(ctx: Context) {
        runCatching { file(ctx).delete() }
    }

    /**
     * 装到当前进程。崩溃时先落盘，再交回系统原有的处理器——该退还是退，
     * 不吞异常、不假装没事（否则 App 会卡在一个已经坏掉的状态里）。
     */
    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { write(app, t, e) }
            if (prev != null) prev.uncaughtException(t, e)
        }
    }

    private fun write(ctx: Context, t: Thread, e: Throwable) {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val f = File(dir, LAST)
        if (f.exists()) runCatching { f.copyTo(File(dir, PREV), overwrite = true) }
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        f.writeText(head(ctx, t.name) + sw.toString())
    }

    // ---------- 非崩溃故障（前台服务启动失败等） ----------

    private fun faultFile(ctx: Context): File =
        File(File(ctx.filesDir, DIR).apply { mkdirs() }, FAULT)

    /** 最近一次服务故障全文；没有则空串。绝不抛异常。 */
    fun readFault(ctx: Context): String = runCatching {
        val f = faultFile(ctx)
        if (f.exists()) f.readText() else ""
    }.getOrDefault("")

    fun clearFault(ctx: Context) {
        runCatching { faultFile(ctx).delete() }
    }

    /**
     * 记录一次被捕获的非致命故障。覆盖写入（只保留最近一次，避免无限增长）。
     * 不抛异常：这是故障处理的兜底路径，它自己再崩就没意义了。
     */
    fun recordFault(ctx: Context, title: String, e: Throwable) {
        runCatching {
            val app = ctx.applicationContext
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            faultFile(app).writeText(
                head(app, Thread.currentThread().name) +
                    "故障：" + title + "\n" +
                    "----------------------------------------\n" +
                    sw.toString()
            )
        }
    }

    private fun head(ctx: Context, threadName: String): String = buildString {
        append("时间：")
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date()))
        append('\n')
        append("线程：").append(threadName).append('\n')
        append("机型：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        append(" / Android ").append(Build.VERSION.RELEASE)
        append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        append("版本：").append(appVersion(ctx)).append('\n')
        append("----------------------------------------\n")
    }

    private fun appVersion(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.versionName + " (" + pi.longVersionCode + ")"
    }.getOrDefault("?")
}
