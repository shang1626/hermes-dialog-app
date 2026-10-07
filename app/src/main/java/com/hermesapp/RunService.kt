package com.hermesapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * 任务运行期间的前台服务：让 App 切到后台/息屏时进程不被冻结，
 * SSE 流和正在进行的一轮任务不中断。
 *
 * 说明：Android 规定前台服务必须常驻一条通知，无法真正「无通知」。
 * 这里把这条通知压到最低干扰：IMPORTANCE_MIN 频道 + 静音 + 无正文，
 * 只保留一个可折叠的静默条目，不再有提示音/震动/正文横幅。
 *
 * 关于「前台服务启动超时」崩溃（ForegroundServiceDidNotStartInTimeException）：
 * 根因不是服务启动慢，而是 startForeground() 抛异常后被静默吞掉——系统看到
 * 「喊了要转前台、却一直没转」，约 10 秒后判违约，直接把进程杀掉。
 * 现在改为：失败必须留证据（CrashLog.recordFault）并立刻 stopSelf 干净收场，
 * 从「崩溃」降级为「安静降级」。另外 Android 12+ 不允许从后台启动前台服务，
 * 所以调用方只在 App 处于前台时才启动（见 ChatViewModel.updateRunService）。
 */
class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        try {
            // Android 10+ 必须声明前台服务类型，且要与清单里 android:foregroundServiceType
            // 保持一致（清单写的是 dataSync）。不传类型在部分机型上会被拒。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (e: Throwable) {
            // 绝不吞异常。这里是原崩溃的真正入口：Android 12+ 从后台拉起、
            // 通知权限被禁、类型不符都会在这里抛。留痕 + 主动停，别再让系统判违约。
            CrashLog.recordFault(this, "前台服务启动失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
        // 不用 START_STICKY：进程被杀后系统在后台把它拉回来，正好会撞上
        // 「后台不许起前台服务」的限制，反而制造崩溃。任务本身在服务端跑，
        // 重开 App 会重新拉结果，不需要系统替我们拉活。
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 频道等级创建后不可再改，降噪只能换新 id；顺手删掉旧频道清掉遗留通知。
            runCatching { mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            val ch = NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            ch.setSound(null, null)
            ch.enableVibration(false)
            mgr.createNotificationChannel(ch)
        }
        // 点常驻条目要把 App 拉到前台。原来漏挂 contentIntent，点了完全没反应
        // （另两条通知都挂了，所以只有这条点不动）。用各自的 requestCode 避免覆盖。
        val tap = PendingIntent.getActivity(
            this, 3,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes 在线")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "hermes_run2"
        const val LEGACY_CHANNEL_ID = "hermes_run"
        const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            AppLog.log("service", "RunService.start")
            runCatching {
                val i = Intent(ctx, RunService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            }
        }

        fun stop(ctx: Context) {
            AppLog.log("service", "RunService.stop")
            runCatching { ctx.stopService(Intent(ctx, RunService::class.java)) }
        }
    }
}
