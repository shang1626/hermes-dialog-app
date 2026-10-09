package com.hermesapp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 攒帧器的收尾契约（F12 的基石）。
 *
 * F12 的修法是「落盘前先把攒帧缓冲一次并入消息模型」——它成立的前提是
 * [StreamDeltaCoalescer.flushNow] **确实**会把当时缓冲区里的所有字一次放完，
 * 且放完后再取的消息正文与序号是自洽的。这个不变量以前没有任何测试守着，先钉住。
 */
class CoalescerFlushTest {

    private fun coalescer(out: MutableList<String>, frameMs: Long = 10_000L): Pair<StreamDeltaCoalescer, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        return StreamDeltaCoalescer(scope, onFlush = { out.add(it) }, frameMs = frameMs) to scope
    }

    @Test
    fun flushNow_emits_everything_buffered_in_one_go() {
        // 攒帧器按帧放字：append 之后内容只是进了缓冲，还没交给消息模型。
        val out = mutableListOf<String>()
        val (c, scope) = coalescer(out)
        try {
            c.append("第一段")
            c.append("第二段")
            assertTrue("append 之后不应立刻放字（否则就不是攒帧了）", out.isEmpty())
            // 落盘前的这一次 flushNow 必须把两段一次放完——少放一段就等于丢了正文。
            c.flushNow()
            assertEquals(listOf("第一段第二段"), out)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun flushNow_on_empty_buffer_emits_nothing() {
        // 落盘是每 400 毫秒一次的常态动作：缓冲已经空了就不该产生任何「放帧」副作用。
        val out = mutableListOf<String>()
        val (c, scope) = coalescer(out)
        try {
            c.flushNow()
            assertTrue(out.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun discard_drops_buffer_without_emitting() {
        // 点「停止」时用 discard：残余字不该再冒出来，也不该写进消息模型。
        val out = mutableListOf<String>()
        val (c, scope) = coalescer(out)
        try {
            c.append("不要再显示的残余")
            c.discard()
            assertTrue(out.isEmpty())
            // discard 之后 flushNow 也不该再放出旧内容。
            c.flushNow()
            assertTrue(out.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
