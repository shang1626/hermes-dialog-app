package com.hermesapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** App 是否在前台：MainActivity 的 onResume/onPause 维护，用于判断要不要弹通知。 */
object AppForeground {
    @Volatile var isForeground: Boolean = false
}

/**
 * 聊天页是否真的在眼前（R18）。
 *
 * 为什么单列：审批/澄清提醒原来用「App 在前台 且 会话 id 是当前会话」判断用户能不能看见
 * 那张卡片并据此抑制系统通知。可用户在设置页/状态页/任务页时，currentId 仍然是同一个会话，
 * 于是通知被抑制，而那张卡其实在聊天页里根本看不到——人一直卡着等，界面却一声不响。
 * 由 MainScaffold 在切页时发布真实可见性。
 */
object ChatVisibility {
    @Volatile var chatVisible: Boolean = false

    /** 真的看得见才算：App 在前台且停在聊天页。 */
    fun visible(): Boolean = AppForeground.isForeground && chatVisible
}

/**
 * 后台消息提醒：App 不在前台时收到助手回复，走系统通知（提示音 + 震动）。
 * 渠道重要性 HIGH，声音/震动由渠道属性控制；Android 13+ 需 POST_NOTIFICATIONS 授权。
 */
object Notifier {
    const val CHANNEL_ID = "hermes_msg"

    /**
     * 「任务完成」专用渠道（2026-10-10 用户要求：任务跑完要有震动 + 响铃）。
     *
     * 为什么另开一条而不是复用「新消息」或常驻那条：
     * ① 渠道的重要性/声音/震动**创建后不可改**，而「后台运行」那条常驻通知必须是静默的
     *    （IMPORTANCE_LOW），混在同一条渠道里要么常驻通知吵、要么完成提醒不响；
     * ② 独立渠道还能让用户单独调：不想听完成提示音时只关这一条，不动「后台运行」。
     */
    const val DONE_CHANNEL_ID = "hermes_done"
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

    /** 完成/待处理渠道：HIGH（横幅）+ 提示音 + 震动。 */
    fun ensureDoneChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(DONE_CHANNEL_ID) != null) return
        val ch = NotificationChannel(DONE_CHANNEL_ID, "任务完成", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "任务跑完、定时任务有产出、或停下来等你处理时提醒（带提示音与震动）"
        ch.enableVibration(true)
        ch.vibrationPattern = DONE_VIBRATE
        ch.enableLights(true)
        ch.lightColor = 0xFF4EA1FF.toInt()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .build()
        ch.setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI, attrs)
        mgr.createNotificationChannel(ch)
    }

    fun notifyMessage(
        ctx: Context, title: String, text: String, sessionId: String = "", openTab: Int = -1,
        channel: String = CHANNEL_ID,
    ): Boolean {
        ensureChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // 2026-10-08：原来这里收了 sessionId 却从没 putExtra —— 点「任务完成 / 新消息」
                // 通知只是把 App 拉到前台，停在原来的会话（用户报「点了不进对应会话」）。
                // 与 notifyAction 对齐，带上会话 id；MainActivity.openFromNotification 已有跳转逻辑。
                if (sessionId.isNotEmpty()) putExtra(EXTRA_OPEN_SESSION, sessionId)
                // 定时任务产出的通知要跳到「定时任务」页（tab 3），不是某个会话。
                // 原来这类通知不带任何标识，点击只把 App 拉前台、停在原页（用户报
                // 「定时任务完成的通知点了不跳定时任务界面」）。
                // ⚠️ 只挂 extra，不做落盘兜底。PendingIntent 的 Intent 由系统持有，
                // 进程被杀后重建也会原样投递，extra 足够可靠；若在「造通知」时写盘，
                // 用户没点这条通知、直接打开 App 也会被顶到定时任务页（误跳）。
                if (openTab >= 0) putExtra(EXTRA_OPEN_TAB, openTab)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // 通知栏直接回复：展开通知就是输入框，打完直接发，不用点进 App
        val replyIntent = Intent(ctx, ReplyReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_SESSION_ID, sessionId)
            // 记下这条通知**发出时**的身份（R10）：接收端过去只写「收到时的当前身份」，
            // 于是切身份后才执行的旧通知会被标成新身份，身份闸门形同虚设。
            putExtra(EXTRA_PROFILE, Prefs(ctx).profile)
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
        val n = applyAppIcon(NotificationCompat.Builder(ctx, channel), ctx)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(tap)
            .apply { if (channel == DONE_CHANNEL_ID) withDoneAlert(this) }
            .addAction(action)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        // 无授权时静默失败，绝不因通知崩溃。但**把结果与失败原因写进日志** ——
        // 原来这行 runCatching 什么都不留，用户报「没通知/没响」时日志里一片空白，
        // 分不清是没触发、被系统拒了、还是渠道被静音（2026-10-10 排查吃过这个亏）。
        return try {
            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n)
            true
        } catch (e: Throwable) {
            AppLog.err("notif", "通知发送失败 channel=$channel", e)
            false
        }
    }

    /**
     * 审批/澄清提醒：任务停下来等人点头，但 App 不在前台。
     * 用独立通知 id（不覆盖「新消息」那条），点开拉 App 并切到对应会话。
     */
    fun notifyAction(ctx: Context, title: String, text: String, sessionId: String) {
        ensureDoneChannel(ctx)
        val tap = PendingIntent.getActivity(
            ctx, 2,
            Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_SESSION, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = applyAppIcon(NotificationCompat.Builder(ctx, DONE_CHANNEL_ID), ctx)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .apply { withDoneAlert(this) }
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ACTION_NOTIF_ID, n) }
    }

    /**
     * 任务完成/定时任务产出的统一出口：走「任务完成」渠道（提示音 + 震动）。
     * 兜底闹钟（KeepAliveReceiver）补提醒也走这里，保证口径一致。
     */
    fun notifyDone(ctx: Context, title: String, text: String, sessionId: String = "", openTab: Int = -1) {
        ensureDoneChannel(ctx)
        // 先探明「这条通知到底能不能响」：权限、渠道是否存在、渠道的声音/震动是否被关。
        // 探不明就直响兜底 —— 用户报「任务跑完没铃响」时，日志能一眼看出断在哪一环。
        val nm = NotificationManagerCompat.from(ctx)
        val allowed = runCatching { nm.areNotificationsEnabled() }.getOrDefault(false)
        val ch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .getNotificationChannel(DONE_CHANNEL_ID)
        } else null
        val chDesc = if (ch == null) "渠道=无" else
            "渠道=importance${ch.importance} 声音=${ch.sound != null} 震动=${ch.shouldVibrate()}"
        val audible = allowed && ch != null &&
            ch.importance >= NotificationManager.IMPORTANCE_DEFAULT &&
            (ch.sound != null || ch.shouldVibrate())
        AppLog.log("notif", "发完成通知 权限=$allowed $chDesc 可响=$audible " +
            (if (AppForeground.isForeground) "前台" else "后台"))
        val ok = notifyMessage(ctx, title, text, sessionId, openTab, channel = DONE_CHANNEL_ID)
        if (!ok || !audible) {
            AppLog.log("notif", "通知不会响（发送ok=$ok 可响=$audible）→ 走兜底直响")
            directAlert(ctx)
        }
    }

    /**
     * 兜底直响：不走通知渠道，App 自己震动 + 播放系统提示音。
     *
     * 为什么需要：渠道的「重要性/声音」是**创建后不可改**的，用户或系统一旦把它关掉
     * （或被厂商省电策略静音），再发通知也是哑的；此时唯一能保证「响一下」的办法
     * 就是绕开通知，直接用 Vibrator / Ringtone 出声。只在通知不会响时才走这条路，
     * 避免与渠道提示音双响。
     */
    private fun directAlert(ctx: Context) {
        runCatching {
            val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            }
            if (vib.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(DONE_VIBRATE, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(DONE_VIBRATE, -1)
                }
                AppLog.log("notif", "兜底震动已触发")
            } else {
                AppLog.log("notif", "兜底震动跳过：无振动器")
            }
        }.onFailure { AppLog.err("notif", "兜底震动失败", it) }
        runCatching {
            val uri = android.provider.Settings.System.DEFAULT_NOTIFICATION_URI
            val rt = uri?.let { RingtoneManager.getRingtone(ctx, it) }
            if (rt == null) {
                AppLog.log("notif", "兜底提示音跳过：系统未设默认铃声")
            } else {
                rt.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                rt.play()
                AppLog.log("notif", "兜底提示音已播放")
            }
        }.onFailure { AppLog.err("notif", "兜底提示音失败", it) }
    }

    /** 给「任务完成」类通知显式挂上震动与提示音（渠道已开，这里再显式来一层，防机型差异）。 */
    private fun withDoneAlert(b: NotificationCompat.Builder): NotificationCompat.Builder =
        b.setVibrate(DONE_VIBRATE)
            .setSound(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)
            .setDefaults(NotificationCompat.DEFAULT_VIBRATE)

    /** 震动花样：两短一长，与「新消息」区分。 */
    private val DONE_VIBRATE = longArrayOf(0, 200, 120, 200, 120, 400)

    /**
     * 清掉业务提醒：新消息/任务完成（2001）与审批/澄清（2002）。
     *
     * App 回到前台时调。此前这两条只在**点通知**时才自动消失（`setAutoCancel(true)` 只挂
     * 在 tap 的 PendingIntent 上），用户直接点图标进 App、不点通知时，横幅就一直挂在通知栏
     * （用户 2026-10-09 报障：a 对话完成的通知，直接开 App 进 a 对话后仍不消失）。
     *
     * ⚠ 只清这两条业务提醒，**绝不动 RunService 的后台常驻通知（id 1001）**——那条是
     * 「后台运行」的可见性凭据，清掉会让用户以为服务挂了。
     */
    fun clearBusinessNotifications(ctx: Context) {
        val mgr = NotificationManagerCompat.from(ctx)
        runCatching { mgr.cancel(NOTIF_ID) }
        runCatching { mgr.cancel(ACTION_NOTIF_ID) }
    }

    const val ACTION_NOTIF_ID = 2002
    const val EXTRA_OPEN_SESSION = "hermes_open_session"
    const val EXTRA_OPEN_TAB = "hermes_open_tab"

    const val KEY_TEXT_REPLY = "hermes_reply_text"
    const val ACTION_REPLY = "com.hermesapp.REPLY"
    const val EXTRA_SESSION_ID = "hermes_reply_session"
    /** 通知发出时的身份（R10）：接收端据此标记暂存回复的来源，不用「收到时的当前身份」。 */
    const val EXTRA_PROFILE = "hermes_reply_profile"
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
        // 记下这句话是哪个身份下打的（F18）：切身份后不许当成新身份的消息发出去。
        val p = Prefs(ctx)
        p.pendingReplyProfile = intent.getStringExtra(Notifier.EXTRA_PROFILE).orEmpty().ifEmpty { p.profile }
        p.pendingReply = sid + "\u0000" + text
        runCatching { NotificationManagerCompat.from(ctx).cancel(Notifier.NOTIF_ID) }
        PendingReply.poke()
    }
}