package com.hermesapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 流式语音播放（方案丙）+ 多任务串行队列。
 *
 * 服务端边合成边把 mp3 块通过 SSE 的 audio.delta 推过来，这里边收边写本地文件，
 * 播放器拿「正在增长的文件」当数据源边读边播：首块约 1 秒就出声（原来要等整段合成完）。
 * 为什么用文件不用内存队列：ExoPlayer 要 seek（探 mp3 头、可能回退重读），文件天然支持随机读。
 *
 * 2.116 起改成队列：一条任务的语音正在播时，别的任务完成的语音不再抢麦把它打断，
 * 而是各自收完、落盘、按到达顺序排队，等前一条播完再依次播。
 *
 * 2.117 修队列自身引入的竞态 + 补「轮到谁了」的可见性：
 *  - 外部播放器（重播 / 老附件）要出声时，原实现是「让当前这条让位、留在队首等外部播完再从头播」。
 *    但外部播放器准备阶段又会调一次自己的 stop()，那个 stop 会给队列发「让位结束」信号，
 *    于是刚让位的语音立刻被拉起来从头响 —— 用户看到的就是「点了别的任务，当前这条停不掉」。
 *    现在改成 [yieldAndDrop]：停掉并**从队列摘掉**当前这条，队列里其余等待的照旧依次播。
 *  - 新增 [playingSession]：把「正在播的语音属于哪个会话」暴露给界面，
 *    多任务排队时用户能看出轮到谁了（聊天页据此显示提示条）。
 *
 * 线程约束（2.111 修复）：ExoPlayer 硬性要求创建/prepare/play/release 都发生在
 * 带 Looper 的线程（主线程）。本类方法从 SSE 回调线程（OkHttp 线程池，无 Looper）调进来，
 * 直接 new ExoPlayer 会抛异常并被 catch 吞掉 -> 全程无声。所以播放器操作统一 post 到主线程，
 * 文件读写留在原线程。
 */
object StreamVoicePlayer {

    /** 一条任务的语音流：文件、写句柄、收尾标志。多个任务各持一份，互不覆盖。 */
    private class Item(val key: String, val runId: String, val file: File) {
        @Volatile var raf: RandomAccessFile? = null
        @Volatile var player: ExoPlayer? = null
        @Volatile var ended = false
        @Volatile var firstLogged = false
        /** 归属会话：用于「正在播放：X 的语音」提示（多任务排队时用户要知道轮到谁了）。 */
        @Volatile var sid: String = ""
        @Volatile var title: String = ""
    }

    private val items = ConcurrentHashMap<String, Item>()

    /** 到达顺序：队首 = 下一条该播的。 */
    private val order = ArrayDeque<String>()

    /** 当前在播的 key；空闲为空串。 */
    @Volatile private var activeKey = ""

    @Volatile private var appCtx: Context? = null

    /** 语速（与 VoicePlayer / VoiceReplayPlayer 同源，由设置页写入）。 */
    @Volatile var rate: Float = 1.0f

    /** 当前流式播放的 key；空闲为空串。 */
    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    /**
     * 正在播的语音属于哪个会话（会话 id to 标题）；null = 没在播。
     *
     * 为什么要单独一份：多任务排队时用户只听到声音、看不出轮到哪条任务了，
     * 尤其「别的会话」的语音排进来时完全无从判断。聊天页据此显示一条提示条。
     */
    private val _playingSession = MutableStateFlow<Pair<String, String>?>(null)
    val playingSession: StateFlow<Pair<String, String>?> = _playingSession.asStateFlow()

    /** 唯一的「改在播状态」入口：key 与归属会话永远一起变，不会一半新一半旧。 */
    private fun setPlaying(key: String, sid: String?, title: String?) {
        _nowPlaying.value = key
        _playingSession.value =
            if (key.isEmpty() || sid.isNullOrEmpty()) null else sid to title.orEmpty()
    }

    private val dropped = ConcurrentHashMap.newKeySet<String>()

    /** 主线程 Handler：所有 ExoPlayer 操作都投到这里执行。 */
    private val main = Handler(Looper.getMainLooper())

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** 某个 run 的语音文件（重播与流式共用同一份）。runId 为空时退回单文件。 */
    fun voiceFile(ctx: Context, runId: String): File {
        if (runId.isEmpty()) return File(ctx.cacheDir, "stream_voice.mp3")
        val dir = File(ctx.cacheDir, "voice_replay").apply { mkdirs() }
        return File(dir, runId + ".mp3")
    }

    /**
     * 收到 audio.start：建文件、入队。
     * 不再掐掉别人——前面有语音在播就排队等，保证同一时刻只响一条。
     * sid/title = 这条语音归属的会话，仅用于界面提示「正在播放：X 的语音」。
     */
    @Synchronized
    fun begin(ctx: Context, key: String, runId: String = "", sid: String = "", title: String = "") {
        appCtx = ctx.applicationContext
        if (items.containsKey(key)) return          // 同一个 run 重复 start：忽略
        try {
            val f = voiceFile(ctx, runId)
            runCatching { f.delete() }              // 清上一轮残留，避免先播旧字节
            f.createNewFile()
            val it = Item(key, runId, f)
            it.sid = sid
            it.title = title
            it.raf = RandomAccessFile(f, "rw")
            items[key] = it
            order.addLast(key)
            AppLog.log("voice", "语音入队 key=" + key + " 文件=" + f.absolutePath + " 队列=" + order.size)
        } catch (e: Exception) {
            AppLog.err("voice", "语音开文件失败", e)
        }
        pump()
    }

    /** 收到 audio.delta：按 key 追加进对应任务的文件（不管有没有轮到它播）。 */
    fun append(ctx: Context, key: String, b64: String) {
        val it = items[key]
        val r = it?.raf
        if (it == null || r == null) {
            if (dropped.add(key)) {
                AppLog.log("voice", "音频块到达但无对应条目 key=" + key + "（begin 未生效或已被取消）")
            }
            return
        }
        try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (bytes.isEmpty()) return
            synchronized(it) {
                r.seek(r.length())
                r.write(bytes)
            }
            if (!it.firstLogged) {
                it.firstLogged = true
                AppLog.log("voice", "首个音频块 " + bytes.size + " 字节 key=" + key + " 文件长=" + r.length())
            }
            pump()
        } catch (e: Exception) {
            AppLog.err("voice", "流式语音写入失败", e)
        }
    }

    /** 收到 audio.end：标记写完；轮到它时 pump 会起播（数据源读到末尾自然收尾）。 */
    fun end(ctx: Context, key: String) {
        items[key]?.ended = true
        pump()
    }

    /**
     * 队列推进：没有别的语音在响时，让队首那条「已有数据」的起播。
     * 只在这里起播，保证同一时刻只有一条出声。
     */
    @Synchronized
    private fun pump() {
        if (activeKey.isNotEmpty()) return
        if (VoicePlayer.nowPlaying.value.isNotEmpty()) return        // 老附件路径在响
        if (VoiceReplayPlayer.nowPlaying.value.isNotEmpty()) return  // 手动重播在响
        val ctx = appCtx ?: return
        while (true) {
            val k = order.peekFirst() ?: return
            val it = items[k]
            if (it == null) {
                order.removeFirst()
                continue
            }
            val len = it.file.length()
            if (len <= 0L) {
                if (it.ended) {          // 空音频：丢掉，别堵住队列
                    order.removeFirst()
                    items.remove(k)
                    closeItem(it)
                    continue
                }
                return                   // 还没数据，等下一块
            }
            activeKey = k
            setPlaying(k, it.sid, it.title)
            onMain { startOnMain(ctx, k) }
            return
        }
    }

    /** 外部播放器播完了 / 用户按了停止：让队列接着走。 */
    fun resumeQueue() {
        pump()
    }

    /**
     * 外部播放器（重播 / 老附件）要出声：停掉正在播的这条，并把它从队列里摘掉。
     *
     * 为什么是「摘掉」而不是「让位后重播」：用户是主动点了另一条语音，
     * 这条若还留在队首、等外部播完再从头响一遍，就成了「当前这条停不掉」。
     * 想再听点它自己的播放按钮即可。队列里其余等待的照旧保留。
     *
     * 注意：外部播放器准备阶段**不能**调自己的公开 stop()（那个会给队列发
     * 「让位结束」信号、把这里刚摘掉的语音又拉起来）——见 VoicePlayer.releaseCurrent。
     */
    @Synchronized
    fun yieldAndDrop() {
        val k = activeKey
        if (k.isEmpty()) return
        val it = items.remove(k)
        order.remove(k)
        activeKey = ""
        setPlaying("", null, null)
        val old = it?.player
        it?.player = null
        onMain {
            runCatching { old?.stop() }
            runCatching { old?.release() }
        }
        it?.let { closeItem(it) }
    }

    /** 跳过当前这条（从队列摘掉），接着播下一条。提示条上的「跳过」用它。 */
    @Synchronized
    fun skipCurrent() {
        val k = activeKey
        if (k.isEmpty()) return
        val it = items.remove(k)
        order.remove(k)
        activeKey = ""
        setPlaying("", null, null)
        val old = it?.player
        it?.player = null
        onMain {
            runCatching { old?.stop() }
            runCatching { old?.release() }
        }
        it?.let { closeItem(it) }
        pump()
    }

    /** 去掉某个 run 的流式条目（用户已用重播按钮放它，留着会播两遍）。 */
    @Synchronized
    fun cancelRun(runId: String) {
        if (runId.isEmpty()) return
        val keys = items.values.filter { it.runId == runId }.map { it.key }
        for (k in keys) {
            val it = items.remove(k) ?: continue
            order.remove(k)
            if (activeKey == k) {
                activeKey = ""
                setPlaying("", null, null)
            }
            val old = it.player
            it.player = null
            onMain {
                runCatching { old?.stop() }
                runCatching { old?.release() }
            }
            closeItem(it)
        }
    }

    /**
     * 立刻把当前正在播的流式语音改成新 rate（设置页调语速时用）。
     * 没在播就什么都不做——rate 字段已更新，下次起播自然用新速度。
     */
    fun applyRateNow() {
        val r = rate
        val k = activeKey
        val it = if (k.isEmpty()) null else items[k]
        onMain {
            runCatching { it?.player?.setPlaybackSpeed(r.coerceIn(0.5f, 2.0f)) }
        }
    }

    /** 真正起播：必须在主线程执行（ExoPlayer 的硬性要求）。 */
    private fun startOnMain(ctx: Context, key: String) {
        val it = items[key] ?: return
        if (it.player != null) return
        val f = it.file
        if (f.length() <= 0) return
        try {
            val exo = ExoPlayer.Builder(ctx).build()
            it.player = exo
            val ds = GrowingFileDataSource(f) { it.ended }
            val src = ProgressiveMediaSource.Factory { ds }
                .createMediaSource(MediaItem.fromUri("file://stream_voice"))
            exo.setMediaSource(src)
            exo.prepare()
            val r = rate
            if (r != 1.0f) {
                runCatching { exo.setPlaybackSpeed(r.coerceIn(0.5f, 2.0f)) }
            }
            AudioFocus.request(ctx)
            exo.playWhenReady = true
            exo.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED && it.player === exo) {
                        finishItem(key)
                    }
                }
            })
            AppLog.log("voice", "流式语音已起播 key=" + key + " 已知长度=" + f.length() + " 队列剩=" + order.size)
        } catch (e: Exception) {
            AppLog.err("voice", "流式语音起播失败", e)
            it.player = null
            if (activeKey == key) {
                activeKey = ""
                setPlaying("", null, null)
            }
            order.remove(key)
            items.remove(key)
            closeItem(it)
            pump()
        }
    }

    /** 一条播完：释放它，队列推进到下一条。 */
    @Synchronized
    private fun finishItem(key: String) {
        val it = items.remove(key) ?: return
        order.remove(key)
        if (activeKey == key) {
            activeKey = ""
            setPlaying("", null, null)
        }
        val old = it.player
        it.player = null
        onMain {
            runCatching { old?.stop() }
            runCatching { old?.release() }
        }
        AudioFocus.abandon()
        closeItem(it)
        AppLog.log("voice", "流式语音播完 key=" + key + " 队列剩=" + order.size)
        pump()
    }

    private fun closeItem(it: Item) {
        val r = it.raf
        it.raf = null
        onMain { runCatching { r?.close() } }
    }

    /** 停止全部流式播放并清空队列（用户按停止 / 关掉语音开关）。 */
    @Synchronized
    fun stop() {
        val olds = items.values.map { it.player }
        val rafs = items.values.map { it.raf }
        items.values.forEach {
            it.player = null
            it.raf = null
        }
        items.clear()
        order.clear()
        activeKey = ""
        setPlaying("", null, null)
        onMain {
            for (p in olds) {
                runCatching { p?.stop() }
                runCatching { p?.release() }
            }
            for (r in rafs) runCatching { r?.close() }
        }
        AudioFocus.abandon()
    }

    /**
     * 数据源：读一个「边写边读」的文件。
     *
     * 关键：文件还没写完时绝不能返回 -1。media3 把 read() == -1 当「流结束」，会在首块播完
     * 立刻 STATE_ENDED 释放播放器，后面的块全丢（只响半句）。所以没数据可读时「等一小会再试」
     * （最多 WAIT_MS），只有真的收尾（audio.end 已到 + 数据读完）才返回 -1；等待超时也返回 -1，
     * 宁可提前收尾，也不把加载线程永久卡住。
     * 嵌套类读不到外部 object 的成员，收尾标志只能由构造参数传进来。
     */
    private class GrowingFileDataSource(
        private val f: File,
        private val isEnded: () -> Boolean,
    ) : DataSource {
        private var raf: RandomAccessFile? = null
        private var pos = 0L
        private var uri: android.net.Uri? = null
        private val WAIT_MS = 3000L

        override fun open(dataSpec: DataSpec): Long {
            uri = dataSpec.uri
            pos = dataSpec.position
            val r = RandomAccessFile(f, "r")
            raf = r
            r.seek(pos)
            return -1L    // 长度未知：让它一直读到我们给 -1 为止
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val r = raf ?: return -1
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (true) {
                val avail = (f.length() - pos).coerceAtLeast(0L)
                if (avail > 0L) {
                    val toRead = minOf(length.toLong(), avail).toInt()
                    val n = r.read(buffer, offset, toRead)
                    if (n > 0) {
                        pos += n
                        return n
                    }
                }
                if (isEnded() || System.currentTimeMillis() >= deadline) return -1
                Thread.sleep(20)
            }
        }

        override fun getUri(): android.net.Uri? = uri

        override fun close() {
            runCatching { raf?.close() }
            raf = null
        }

        override fun addTransferListener(transferListener: TransferListener) {}
    }
}