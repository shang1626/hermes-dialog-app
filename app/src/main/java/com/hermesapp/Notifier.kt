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

    /**
     * 给通知挂上「App 自己的图标」：小图标 + 大图标。
     *
     * 为什么以前通知里看不到应用图标：三条通知的小图标全都用的是系统 drawable
     * （stat_notify_chat / stat_sys_warning / stat_notify_sync），而且从未 setLargeIcon。
     * 结果就是状态栏顶着系统那个通用气泡、通知栏里右侧一片空白，看着不像这个软件的消息。
     *
     * 两条 Android 规矩决定了必须这么配：
     * ① **状态栏小图标会被系统强制染成单色**（彩色图会被压成一个实心色块），所以只能给单色
     *    剪影 —— `ic_stat_hermes` 就是从 App 自己的图标取的白剪影，属于 App 自己的形状；
     * ② 想在通知栏里看到**彩色的** App 图标，必须走 setLargeIcon 给位图（`ic_notify_app`）。
     */
    fun applyAppIcon(b: NotificationCompat.Builder, ctx: Context): NotificationCompat.Builder {
        b.setSmallIcon(R.drawable.ic_stat_hermes)
        runCatching {
            b.setLargeIcon(
                android.graphics.BitmapFactory.decodeResource(ctx.resources, R.drawable.ic_notify_app)
            )
        }
        return b
    }

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
                // 2026-10-08：原来这里收了 sessionId 却从没 putExtra —— 点「任务完成 / 新消息」
                // 通知只是把 App 拉到前台，停在原来的会话（用户报「点了不进对应会话」）。
                // 与 notifyAction 对齐，带上会话 id；MainActivity.openFromNotification 已有跳转逻辑。
                if (sessionId.isNotEmpty()) putExtra(EXTRA_OPEN_SESSION, sessionId)
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
        val n = applyAppIcon(NotificationCompat.Builder(ctx, CHANNEL_ID), ctx)
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

    /**
     * 审批/澄清提醒：任务停下来等人点头，但 App 不在前台。
     * 用独立通知 id（不覆盖「新消息」那条），点开拉 App 并切到对应会话。
     */
    fun notifyAction(ctx: Context, title: String, text: String, sessionId: String) {
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SESSION, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = applyAppIcon(NotificationCompat.Builder(ctx, CHANNEL_ID), ctx)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ACTION_NOTIF_ID, n) }
    }

    const val ACTION_NOTIF_ID = 2002
    const val EXTRA_OPEN_SESSION = "hermes_open_session"

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