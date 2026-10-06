package com.hermesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 每帧 16 毫秒（约 60fps）：攒字放送的节奏。 */
internal const val STREAM_DELTA_FRAME_MS = 16L

/** 每帧最少放几个字：积压少时放慢，看着顺滑。 */
private const val MIN_CHARS_PER_FRAME = 8

/** 每帧最多放几个字：积压多时放快，不落后于真实进度。 */
private const val MAX_CHARS_PER_FRAME = 48

/** 期望用多少帧把当前积压排空，用来算自适应配额。 */
private const val TARGET_DRAIN_FRAMES = 8

/**
 * 流式输出的攒帧器。
 *
 * 问题：模型吐字经常是「憋一下、然后一次来一大串」。每收到一段就直接刷界面，
 * 表现就是文字一大块一大块地跳，即使渲染本身很快。
 *
 * 做法：收到的字先进缓冲，每 [frameMs] 放出一小段；放多少跟积压量自适应——
 * 积压少每帧 8 字，积压多最多每帧 48 字。这样既不落后于真实进度，看着也顺。
 *
 * 切分时会检查有没有把中文/emoji 的一个字（代理对）劈成两半，劈到就退一格。
 *
 * 生命周期边界（工具事件、回合结束、中断、失败）调用 [flushNow] 把缓冲一次性放完，
 * 保证最后一段字不丢、也不越过终止事件往后跑；[discard] 用于停止任务时丢弃残余。
 */
internal class StreamDeltaCoalescer(
    private val scope: CoroutineScope,
    private val onFlush: (String) -> Unit,
    private val frameMs: Long = STREAM_DELTA_FRAME_MS,
) {
    private val lock = Any()
    private val pending = StringBuilder()

    /** 排空循环是否在跑。为 true 时新来的字只入缓冲，由既有循环带走。 */
    private var running = false
    private var job: Job? = null

    init {
        require(frameMs >= 0L) { "frameMs 不能为负" }
    }

    /** 收到一段流式正文：入缓冲，必要时起排空循环。 */
    fun append(delta: String) {
        if (delta.isEmpty()) return
        var start = false
        synchronized(lock) {
            pending.append(delta)
            if (!running) {
                running = true
                start = true
            }
        }
        if (start) {
            val j = scope.launch { drainLoop() }
            synchronized(lock) { job = j }
        }
    }

    private suspend fun drainLoop() {
        while (true) {
            delay(frameMs)
            val batch = synchronized(lock) {
                if (pending.isEmpty()) {
                    // 缓冲已空：收工。下次 append 会重新起循环。
                    running = false
                    null
                } else {
                    val budget = frameBudget(pending.length)
                    val safe = codePointSafePrefixLength(pending, budget)
                    val s = pending.substring(0, safe)
                    pending.delete(0, safe)
                    s
                }
            }
            if (batch == null) break
            if (batch.isNotEmpty()) onFlush(batch)
        }
    }

    /** 立刻把缓冲全部放出去（终止事件、工具切换、回合结束前调用）。 */
    fun flushNow() {
        job?.cancel()
        val batch = synchronized(lock) {
            running = false
            if (pending.isEmpty()) {
                ""
            } else {
                val s = pending.toString()
                pending.setLength(0)
                s
            }
        }
        if (batch.isNotEmpty()) onFlush(batch)
    }

    /** 丢弃缓冲，不再放出（停止任务时用）。 */
    fun discard() {
        job?.cancel()
        synchronized(lock) {
            running = false
            pending.setLength(0)
        }
    }

    private companion object {
        /** 自适应配额：按积压量算出每帧放多少字，落在 [MIN, MAX] 区间内。 */
        fun frameBudget(pendingChars: Int): Int {
            if (pendingChars <= 0) return 0
            val adaptive =
                (pendingChars + TARGET_DRAIN_FRAMES - 1) / TARGET_DRAIN_FRAMES
            return adaptive
                .coerceIn(MIN_CHARS_PER_FRAME, MAX_CHARS_PER_FRAME)
                .coerceAtMost(pendingChars)
        }

        /**
         * 取一个不会把代理对（emoji / 生僻汉字）劈成两半的长度。
         * 若第 requestedLength 个字符正好是低位代理而前一个是高位代理，就少切一个。
         */
        fun codePointSafePrefixLength(sb: StringBuilder, requestedLength: Int): Int {
            if (requestedLength <= 0) return 0
            if (requestedLength >= sb.length) return sb.length
            return if (
                Character.isHighSurrogate(sb[requestedLength - 1]) &&
                Character.isLowSurrogate(sb[requestedLength])
            ) {
                requestedLength - 1
            } else {
                requestedLength
            }
        }
    }
}