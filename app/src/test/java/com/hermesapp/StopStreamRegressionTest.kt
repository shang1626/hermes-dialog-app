package com.hermesapp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Mechanism tests: real runtime and production callback gate, not Android VM/network/player. */
@OptIn(ExperimentalCoroutinesApi::class)
class StopStreamRegressionTest {
    private fun running() = SessionRuntime("stop-regression").apply {
        sendGen = 1
        streamGen = 1
        runId = "run-A"
        busy.value = true
    }

    @Test
    fun queued_old_completion_cannot_finish_next_send_before_its_stream_opens() = runTest {
        val r = running()
        val stream = r.streamGen
        val send = r.sendGen
        // Like streamRun: a received event waits for the Main dispatcher before its guard runs.
        val completion = launch {
            if (r.acceptsStreamCallback(stream, send)) {
                r.finished = true
                r.busy.value = false
            }
        }
        r.invalidateTurnForStop()
        r.finished = true
        r.stopRequested = true
        r.runId = ""
        r.sendGen++ // B begins upload/startRun; no new SSE stream yet.
        r.finished = false
        r.stopRequested = false
        r.busy.value = true
        completion.join()
        println("late completion: busy=${r.busy.value} finished=${r.finished}")
        assertTrue("Late A completion must not end B", r.busy.value)
        assertFalse(r.finished)
    }

    @Test
    fun stop_invalidates_both_generations_before_call_cancellation() {
        val r = running()
        r.invalidateTurnForStop()
        println("stop generations: send=${r.sendGen} stream=${r.streamGen}")
        assertEquals(2, r.sendGen)
        assertEquals(2, r.streamGen)
        assertFalse(r.acceptsStreamCallback(1, 1))
    }

    @Test
    fun new_send_rejects_old_callbacks_even_without_stream_restart() {
        val r = running()
        r.sendGen++
        assertFalse(r.acceptsStreamCallback(1, 1))
    }

    @Test
    fun normal_completion_keeps_audio_tail_callbacks_eligible() {
        val r = running()
        r.finished = true
        r.busy.value = false
        r.runId = "" // doneOk clears runId; audio still belongs to the captured stream.
        for (event in listOf("audio.start", "audio.delta", "audio.end")) {
            assertTrue("$event after completion must remain eligible", r.acceptsStreamCallback(1, 1))
        }
    }

    @Test
    fun replacement_stream_rejects_old_callbacks_in_same_send() {
        val r = running()
        r.streamGen++
        assertFalse(r.acceptsStreamCallback(1, 1))
        assertTrue(r.acceptsStreamCallback(2, 1))
    }
}
