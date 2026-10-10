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
import android.os.PowerManager
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

    /**
     * 任务在跑时持有的 CPU 唤醒锁。
     *
     * 为什么需要：前台服务只保证「进程不被系统杀掉」，不阻止 CPU 进入休眠。息屏/切后台后
     * CPU 一睡，TCP 连接还挂着但收不到任何字节，SSE 就这么静默断掉——实测后台 61 秒 0 事件，
     * 直到系统唤醒才一次性补收（表现为「切回来显示重新连接」「任务跑完不通知」）。
     * 只在有任务在跑时持锁，无任务立刻释放，避免空转耗电。
     */
    private var wakeLock: PowerManager.WakeLock? = null
    /** Wi-Fi 高性能锁：防某些机型在省电模式下把 Wi-Fi 收包节流/掐断（唤醒锁管不了这一层）。 */
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notif = buildNotification()
        try {
            // Android 10+ 必须声明前台服务类型，且要与清单里 android:foregroundServiceType
            // 保持一致（清单写的是 dataSync）。不传类型在部分机型上会被拒。
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 15+（API 35+）对 dataSync 类型有「每 24 小时最多 6 小时」的配额，
                // 烧完就再也起不来（除非用户把 App 切到前台重置）。我们这条服务是「维持与
                // 自托管网关的长连接、把已完成任务的结果取回来」，属 specialUse 的用途，
                // 不受该配额限制 —— 2026-10-10 改（真机日志实测一天被回收 8 次，配额是一个
                // 迟早会踩的坑）。清单里同时声明 specialUse|dataSync，老机型仍走 dataSync。
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        // 记下「服务确实在前台跑着」（R17）：后台要把 active 推给已在运行的服务时，
        // 先据此判断要不要发 startService，避免在后台硬启撞上 Android 12+ 的限制。
        isRunning = true
        // 保活：有任务在跑才持锁，无任务立刻释放。
        // intent 为 null 时拿不到 EXTRA_ACTIVE（系统重建/异常路径），按「无任务」处理并留痕。
        val active = intent?.getBooleanExtra(EXTRA_ACTIVE, false) ?: false
        AppLog.log("service", "onStartCommand active=" + active + " intentNull=" + (intent == null))
        // 保活心跳：一行一个时间点，用来客观判断「进程活了多久、白名单有没有生效」。
        AppLog.log("keepalive", "服务在线 " + KeepAlive.ts() + " active=" + active)
        if (active) acquireLocks() else releaseLocks()
        // 不用 START_STICKY：进程被杀后系统在后台把它拉回来，正好会撞上
        // 「后台不许起前台服务」的限制，反而制造崩溃。任务本身在服务端跑，
        // 重开 App 会重新拉结果，不需要系统替我们拉活。
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        releaseLocks()
        super.onDestroy()
    }

    /**
     * 取两把锁：CPU 唤醒锁（防 CPU 休眠）+ Wi-Fi 高性能锁（防省电模式节流收包）。
     * 每一步都落日志——「锁到底拿没拿到」必须可查，不能再靠猜。
     */
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true && wifiLock?.isHeld == true) return
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hermes:run").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Throwable) {
            AppLog.err("service", "唤醒锁获取失败", e)
        }
        AppLog.log("service", "唤醒锁 held=" + (wakeLock?.isHeld == true))
        try {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE)
                    as android.net.wifi.WifiManager
                wifiLock = wm.createWifiLock(
                    android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hermes:run"
                ).apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Throwable) {
            AppLog.err("service", "WifiLock 获取失败", e)
        }
        AppLog.log("service", "WifiLock held=" + (wifiLock?.isHeld == true))
    }

    private fun releaseLocks() {
        val w = wakeLock?.isHeld == true
        val f = wifiLock?.isHeld == true
        runCatching { if (w == true) wakeLock?.release() }
        wakeLock = null
        runCatching { if (f == true) wifiLock?.release() }
        wifiLock = null
        AppLog.log("service", "释放锁 wake=" + w + " wifi=" + f)
    }

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 频道等级创建后不可再改，降噪只能换新 id；顺手删掉旧频道清掉遗留通知。
            runCatching { mgr.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
            val ch = NotificationChannel(CHANNEL_ID, "后台运行", NotificationManager.IMPORTANCE_LOW)
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
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Hermes 在线")
            .setContentText("")
        // 前台服务那条也挂 App 图标（三处统一走同一个 helper，见 Notifier.applyAppIcon）。
        return Notifier.applyAppIcon(b, this)
            .setContentIntent(tap)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        // 常驻通知渠道换 id：渠道重要性创建后不可改，而这条要从 MIN（用户看不到）升到 LOW
        // （状态栏可见但仍静默）—— 部分机型会因「前台通知不可见」而更倾向杀掉进程。
        const val CHANNEL_ID = "hermes_run3"
        const val LEGACY_CHANNEL_ID = "hermes_run2"
        const val NOTIF_ID = 1001
        const val EXTRA_ACTIVE = "hermes_run_active"

        /** 服务当前是否真的在前台跑着（进程内静态标记，供 updateActive 判断）。 */
        @Volatile var isRunning: Boolean = false

        /** active=true 表示有任务在跑：持 CPU 唤醒锁，保证后台也能持续收 SSE。 */
        fun start(ctx: Context, active: Boolean = false) {
            AppLog.log("service", "RunService.start")
            runCatching {
                val i = Intent(ctx, RunService::class.java)
                i.putExtra(EXTRA_ACTIVE, active)
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

        /**
         * 后台把「还有没有任务」推给**已在运行**的服务（R17）。
         *
         * 为什么需要：`updateRunService` 在 App 处于后台时不敢新起前台服务（Android 12+ 会拦），
         * 于是最后一个任务在后台跑完时，它既不 start 也不 stop —— 已经拿到的 CPU 唤醒锁与
         * Wi-Fi 锁就一直占着，直到用户下次切回前台或进程被杀。这里用 `startService`（不是
         * `startForegroundService`）把 active=false 推给已经在跑的服务，服务收到即释放锁、
         * 通知保留。服务没在跑就什么都不做，避免后台硬启撞墙。
         */
        fun updateActive(ctx: Context, active: Boolean) {
            if (!isRunning) return
            runCatching {
                ctx.startService(
                    Intent(ctx, RunService::class.java).putExtra(EXTRA_ACTIVE, active)
                )
            }
        }
    }
}
