package com.hermesapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hermesapp.net.HermesApi
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 兜底保活（2026-10-10）。
 *
 * 为什么需要：真机日志实测这台 realme 一天把 App 进程回收 8 次（前台服务也保不住），
 * 而 App 侧**没有任何**兜底路径——没有开机自启、没有周期任务、服务还是 START_NOT_STICKY。
 * 于是进程一死：正在跑的任务进度断了、完成通知永远收不到，必须用户手动打开 App 才恢复
 * （日志实测：02:36 被杀后到 09:21 用户手动打开，中间一条记录都没有）。
 *
 * 这里加一层**低成本**兜底：15 分钟一次的系统闹钟（inexact，Doze 下由系统延后合并），
 * 醒来只做两件事，做完即退，不留常驻进程、不占内存：
 *   ① 落一行心跳日志 —— 以后判断「白名单有没有生效、进程活了多久」有客观依据；
 *   ② 补提醒 —— 已结束但没来得及通知的对话任务、以及新到的定时任务产出，
 *      用「任务完成」渠道弹一条带提示音与震动的通知。
 * 用 AlarmManager 而不是 WorkManager：不引新依赖（镜像源不一定有），行为等价（都是 15 分钟粒度）。
 */
object KeepAlive {
    const val ACTION_TICK = "com.hermesapp.KEEPALIVE_TICK"
    const val INTERVAL_MS = 15 * 60 * 1000L
    private const val REQ_CODE = 7001

    private fun pi(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, REQ_CODE,
        Intent(ctx, KeepAliveReceiver::class.java).apply { action = ACTION_TICK },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** 装上（或刷新）15 分钟一次的兜底闹钟。重复调用无副作用。 */
    fun arm(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            // inexact + 不唤醒：Doze 下由系统合并延后，省电优先；精确闹钟要额外权限，不用。
            am.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + INTERVAL_MS,
                INTERVAL_MS,
                pi(ctx),
            )
            AppLog.log("keepalive", "兜底闹钟已装（15 分钟粒度）")
        }.onFailure { AppLog.err("keepalive", "兜底闹钟安装失败", it) }
    }

    fun cancel(ctx: Context) {
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi(ctx))
        }
    }

    fun ts(now: Long = System.currentTimeMillis()): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(now))
}

/**
 * 15 分钟一次的兜底唤醒：心跳日志 + 补提醒。
 *
 * 纪律：任何异常都不能让接收器崩（系统会对崩溃的接收器记账）；全程 try/catch + 落日志；
 * 前台时直接返回（前台由 App 自己的逻辑负责，别重复响）。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != KeepAlive.ACTION_TICK) return
        val app = ctx.applicationContext
        val p = Prefs(app)
        val pending = p.activeRunsMap()
        AppLog.log(
            "keepalive", "兜底唤醒 " + KeepAlive.ts() + " 活跃标记=" + pending.size +
                " keepAlive=" + p.keepAlive + " 前台=" + AppForeground.isForeground
        )
        if (AppForeground.isForeground) return      // 前台不用兜底，避免重叠打扰

        val go = goAsync()
        Thread {
            try {
                runCatching { checkFinishedRuns(app, p, pending) }
                    .onFailure { AppLog.err("keepalive", "补提醒（对话任务）失败", it) }
                runCatching { checkInbox(app, p) }
                    .onFailure { AppLog.err("keepalive", "补提醒（定时任务）失败", it) }
            } finally {
                runCatching { go.finish() }
            }
        }.start()
    }

    private fun api(ctx: Context, p: Prefs): HermesApi {
        val key = p.credential(p.profile)
        val prefix = if (p.profile == "default") "" else "/p/friend"
        return HermesApi(p.serverUrl, key, prefix)
    }

    /** 已结束但没通知到的那一轮：补一条「任务完成」通知，然后把活跃标记清掉（只提醒一次）。 */
    private fun checkFinishedRuns(ctx: Context, p: Prefs, pending: Map<String, String>) {
        if (pending.isEmpty()) return
        val a = api(ctx, p)
        for ((sid, rid) in pending) {
            val st = runCatching { a.probeRun(rid) }.getOrNull() ?: continue
            if (st is HermesApi.RunStatus.Unknown) continue          // 探不出来 ≠ 结束
            // ⚠ 还在跑（running/queued/stopping/等审批/等澄清）**绝不能**算结束。
            //
            // 这条兜底闹钟是固定的 15 分钟粒度，长任务跑到第 15 分钟闹钟必响——旧代码只排除
            // 了 Unknown，探到 Known(running) 也照发「任务完成」并清掉活跃标记，于是用户看到
            // 「任务还在跑、手机却报已完成」，而且标记被清后真跑完也不再提醒了
            //（2026-10-10 用户报「两次了，任务跑到 15 分钟还在进行中，通知说已完成」；
            // 真机日志实证：19:55:07 还在收工具事件，19:55:15 兜底就发了完成通知）。
            if (st is HermesApi.RunStatus.Known && st.status in ReconnectPolicy.RUNNING_STATES) continue
            val text = when (st) {
                is HermesApi.RunStatus.Known -> {
                    val out = runCatching { st.payload?.optString("output", "").orEmpty() }.getOrDefault("")
                    if (out.isNotEmpty()) out.replace(Regex("\\s+"), " ").trim().take(120)
                    else "任务已结束，点开查看结果"
                }
                else -> "服务端已没有这一轮，点开查看结果"            // Missing
            }
            val status = if (st is HermesApi.RunStatus.Known) st.status else "missing"
            val title = if (st is HermesApi.RunStatus.Known && st.status.contains("fail")) "任务失败" else "任务完成"
            AppLog.log("keepalive", "补提醒 sid=" + sid.take(8) + " run=" + rid.take(12) +
                " 探测状态=" + status + " 文本=" + text.take(20))
            Notifier.notifyDone(ctx, title, text, sid)
            p.removeActiveRun(sid)
        }
    }

    /** 新到的定时任务产出：与 App 内同一套落盘去重集合，不会重复响。 */
    private fun checkInbox(ctx: Context, p: Prefs) {
        val a = api(ctx, p)
        val resp = a.inbox(limit = 50)
        val arr: JSONArray = resp.optJSONArray("items") ?: return
        val notified = p.notifiedReportIds()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val unread = o.isNull("acked_at") || o.optString("acked_at", "").isEmpty()
            val id = o.optString("id", "")
            if (!unread || id.isEmpty() || id in notified) continue
            p.markReportNotified(id)
            val failed = o.optString("status", "ok") == "failure"
            val name = o.optString("job_name", "").ifEmpty { o.optString("job_id", "定时任务") }
            val body = o.optString("body", "").replace(Regex("\\s+"), " ").trim().take(80)
            AppLog.log("keepalive", "补提醒（定时任务）" + name + " 失败=" + failed)
            Notifier.notifyDone(ctx, (if (failed) "定时任务失败：" else "定时任务完成：") + name, body, "", openTab = 3)
        }
    }
}

/** 开机后把兜底闹钟重新装上（闹钟不跨重启存活）。只装闹钟，不在后台硬起前台服务。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        AppLog.log("keepalive", "开机完成（Android " + Build.VERSION.SDK_INT + "）：重装兜底闹钟")
        KeepAlive.arm(ctx.applicationContext)
    }
}
