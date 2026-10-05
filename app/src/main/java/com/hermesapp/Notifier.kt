package com.hermesapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** App 是否在前台：MainActivity 的 onResume/onPause 维护，用于判断要不要弹通知。 */
object AppForeground {
    @Volatile var isForeground: Boolean = false
}

/**
 * 后台消息提醒：App 不在前台时收到助手回复，走系统通知（提示音 + 震动）。
 * 渠道重要性 HIGH，声音/震动由渠道属性控制；Android 13+ 需 POST_NOTIFICATIONS 授权。
 */
object Notifier {
    const val CHANNEL_ID = "hermes_msg"
    const val NOTIF_ID = 2001

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "新消息", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "后台收到助手回复时提醒"
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 250, 150, 250)
        ch.enableLights(true)
        ch.lightColor = 0xFF4EA1FF.toInt()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        ch.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        mgr.createNotificationChannel(ch)
    }

    fun notifyMessage(ctx: Context, title: String, text: String, sessionId: String = "") {
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 通知栏直接回复：展开通知就是输入框，打完直接发，不用点进 App
        val replyIntent = Intent(ctx, ReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        val replyPi = PendingIntent.getBroadcast(
            ctx, 1, replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        val input = androidx.core.app.RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel("回复…")
            .build()
        val action = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "回复", replyPi
        ).addRemoteInput(input).build()
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .addAction(action)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // 无授权时静默失败，绝不因通知崩溃
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    const val KEY_TEXT_REPLY = "hermes_reply_text"
    const val ACTION_REPLY = "com.hermesapp.REPLY"
    const val EXTRA_SESSION_ID = "hermes_reply_session"
}

/** 通知栏回复的暂存区：广播收到后写盘并唤醒 App 内的收集者。 */
object PendingReply {
    private val _flow = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val flow: kotlinx.coroutines.flow.SharedFlow<Unit> = _flow
    fun poke() { _flow.tryEmit(Unit) }
}

/** 接收通知栏里打的那句话：落盘（App 可能已被杀），再叫醒界面去发。 */
class ReplyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Notifier.ACTION_REPLY) return
        val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifier.KEY_TEXT_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val sid = intent.getStringExtra(Notifier.EXTRA_SESSION_ID).orEmpty()
        Prefs(ctx).pendingReply = sid + "\u0000" + text
        runCatching { NotificationManagerCompat.from(ctx).cancel(Notifier.NOTIF_ID) }
        PendingReply.poke()
    }
}