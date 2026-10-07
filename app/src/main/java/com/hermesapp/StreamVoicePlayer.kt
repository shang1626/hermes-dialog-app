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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 流式语音播放（方案丙）：服务端边合成边把 mp3 块通过 SSE 的 audio.delta 事件推过来，
 * 这里边收边写本地文件，播放器拿这个「正在增长的文件」当数据源边读边播。
 *
 * 为什么用文件而不是内存队列：ExoPlayer 要能 seek（它会先探 mp3 头、可能回退重读），
 * 文件天然支持随机读；内存队列要把 seek 语义自己实现一遍，容易出杂音。块到达即落盘，
 * 播完文件就是完整的，重播/兜底都用它。
 *
 * 出声时间：实测首块 1.05 秒就绪 → 约 1 秒就能开始播（原来要等整段合成完，
 * 1000 字 14 秒、3000 字 21 秒）。
 *
 * 降级：服务端关掉 api_server_tts_stream 时走原来的整段 MEDIA 附件，
 * 由 VoicePlayer 那条老路播放，本类完全不参与。
 *
 * 线程约束（2.111 修复）：ExoPlayer 硬性要求「创建、prepare、play、release」都发生在
 * 带 Looper 的线程（主线程）。本类的方法是从 SSE 回调线程（OkHttp 线程池，无 Looper）
 * 调进来的，直接在上面 new ExoPlayer 会抛异常并被 catch 吞掉 → 播放器从未起播 →
 * nowPlaying 被清空 → 后续 audio.delta 因「nowPlaying 为空」全被忽略 → 全程无声。
 * 所以所有播放器操作统一 post 到主线程；文件读写仍在原线程（文件 IO 无所谓线程）。
 */
object StreamVoicePlayer {

    @Volatile
    private var player: ExoPlayer? = null

    @Volatile
    private var file: File? = null

    @Volatile
    private var raf: RandomAccessFile? = null

    @Volatile
    private var finished = false

    /** 当前流式播放的会话/来源标识；空闲为空串。界面靠它切按钮状态。 */
    private val _nowPlaying = MutableStateFlow("")
    val nowPlaying: StateFlow<String> = _nowPlaying.asStateFlow()

    /** 语速（与 VoicePlayer 同源，由设置页写入）。 */
    @Volatile
    var rate: Float = 1.0f

    /** 主线程 Handler：所有 ExoPlayer 操作都投到这里执行（见类注释的线程约束）。 */
    private val main = Handler(Looper.getMainLooper())

    /** 已在主线程就直跑，否则 post 过去（保持调用顺序）。 */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * 收到 audio.start：开一条新的流式播报。
     * 会先掐掉上一条（同一个 App 只响一条，避免叠着念）。
     */
    fun begin(ctx: Context, key: String) {
        stop()
        try {
            val f = File(ctx.cacheDir, "stream_voice.mp3")
            // 清掉上一轮残留：ExoPlayer 会读到旧字节，导致先播上一段语音。
            runCatching { f.delete() }
            f.createNewFile()
            file = f
            raf = RandomAccessFile(f, "rw")
            ended = false
            finished = false
            _nowPlaying.value = key
            AppLog.log("voice", "流式语音开始 key=" + key + " 文件=" + f.absolutePath)
        } catch (e: Exception) {
            AppLog.err("voice", "流式语音开文件失败", e)
            _nowPlaying.value = ""
        }
    }

    /** 收到 audio.delta：把这一块追加进文件。 */
    fun append(ctx: Context, b64: String) {
        val r = raf ?: return
        try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            if (bytes.isEmpty()) return
            r.seek(r.length())
            r.write(bytes)
            startIfReady(ctx)     // 有数据就起播：首个 6KB 块到达即出声
        } catch (e: Exception) {
            AppLog.err("voice", "流式语音写入失败", e)
        }
    }

    /**
     * 收到 audio.end：标记写完，并启动播放（若还没开始播）。
     * 提前 start 会读不到数据；这里等有数据了再起。
     */
    fun end(ctx: Context) {
        ended = true           // 告诉数据源：写完可以收尾了（read 才会给 -1）
        startIfReady(ctx)     // 兜底：块都很小时也能起播
    }

    /**
     * 第一块到达后就可以起播：ExoPlayer 读不到数据会自动重试等待，
     * 但要给它一个能算出长度的数据源，所以用「文件当前长度」当已知上限、
     * 文件增长时后续继续读。
     *
     * 注意：这里只是把真正的起播动作投到主线程，绝不在调用线程 new ExoPlayer。
     */
    fun startIfReady(ctx: Context) {
        onMain { startOnMain(ctx) }
    }

    /** 真正起播：必须在主线程执行（ExoPlayer 的硬性要求）。 */
    private fun startOnMain(ctx: Context) {
        val p = player
        if (p != null) return              // 已经在播
        val f = file ?: return
        if (f.length() <= 0) return        // 还没有数据，等下一块
        try {
            val exo = ExoPlayer.Builder(ctx).build()
            player = exo
            val ds = GrowingFileDataSource(f) { ended }
            val src = ProgressiveMediaSource.Factory { ds }
                .createMediaSource(MediaItem.fromUri("file://stream_voice"))
            exo.setMediaSource(src)
            exo.prepare()
            val r = rate
            if (r != 1.0f) {
                runCatching { exo.setPlaybackSpeed(r.coerceIn(0.5f, 2.0f)) }
            }
            exo.playWhenReady = true
            exo.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) {
                        releaseQuietly()
                    }
                }
            })
            AppLog.log("voice", "流式语音已起播 已知长度=" + f.length())
        } catch (e: Exception) {
            AppLog.err("voice", "流式语音起播失败", e)
            releaseQuietly()
        }
    }

    /** 释放播放器：播放器操作（stop/release）也必须在主线程。 */
    private fun releaseQuietly() {
        onMain {
            val p = player
            player = null
            _nowPlaying.value = ""
            runCatching { p?.stop() }
            runCatching { p?.release() }
            runCatching { raf?.close() }
            raf = null
        }
    }

    fun stop() {
        ended = true
        releaseQuietly()
    }

    /**
     * 数据源：读一个「边写边读」的文件。
     *
     * 关键：**文件还没写完时绝不能返回 -1**。media3 把 read() == -1 当成「流结束」，
     * 会在首块播完时立刻 STATE_ENDED 并释放播放器 —— 后面的块全部丢掉（只响半句）。
     * 所以这里在没数据可读时「等一小会再试」（最多等 WAIT_MS），只有真的收尾了
     * （audio.end 已到 + 数据读完）才返回 -1。
     *
     * 读操作跑在 ExoPlayer 的加载线程上，短暂 sleep 不会卡界面。
     * open() 返回 C.LENGTH_UNSET：长度未知，让它一直读到我们给 -1 为止。
     */
    private class GrowingFileDataSource(
        private val f: File,
        /** 收尾标志：audio.end 已到。嵌套类读不到外部 object 的成员，只能传进来。 */
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
            return -1L    // 长度未知：让它一直读到我们给 -1 为止（C.LENGTH_UNSET 是 Int，返回 Long 处不能直接用）
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
                // 收尾了就是真结束；否则等一小会再试（合成比播放慢时属正常）。
                // 等待超时也返回 -1：宁可提前收尾，也不把 ExoPlayer 的加载线程永久卡住。
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

    /** audio.end 已到：数据读完就可以给 -1（否则 read 会一直等）。 */
    @Volatile
    private var ended = false
}