package com.hermesapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
 */
class RunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        runCatching { startForeground(NOTIF_ID, buildNotification()) }
        return START_STICKY
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes")
            .setContentText("")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
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
            runCatching { ctx.stopService(Intent(ctx, RunService::class.java)) }
        }
    }
}
