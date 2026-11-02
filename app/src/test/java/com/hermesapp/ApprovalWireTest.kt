package com.hermesapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批/澄清载荷归一化单测（2026-11-03 实测：有通知没卡片）。
 *
 * 现场：服务端换 canonical session-authority 通道后，`approval.request` 只带 `prompt_id`，
 * App 只读 `request_id` → 取到空串 → 卡片不挂。这里把「两种通道的字段名与回执体形状」
 * 都钉住，任何一种退化都会让测试立刻变红。
 */
class ApprovalWireTest {

    private fun canonicalApproval() = JSONObject(
        """{"kind":"approval","prompt_id":"abc123def456","execution_generation":31,
           "command":"rm -rf /tmp/x","description":"recursive delete",
           "choices":["once","deny","session","always"]}"""
    )

    private fun legacyApproval() = JSONObject(
        """{"request_id":"0bf3e49c5716","command":"kill -9 1","description":"system kill",
           "choices":["once","deny"]}"""
    )

    @Test
    fun canonical_prompt_id_is_read_as_request_id() {
        assertEquals("abc123def456", ApprovalWire.promptId(canonicalApproval()))
    }

    @Test
    fun legacy_request_id_still_wins() {
        assertEquals("0bf3e49c5716", ApprovalWire.promptId(legacyApproval()))
    }

    @Test
    fun missing_id_is_empty_not_garbage() {
        assertEquals("", ApprovalWire.promptId(JSONObject("""{"command":"ls"}""")))
    }

    @Test
    fun generation_is_read_and_defaults_to_minus_one() {
        assertEquals(31, ApprovalWire.generation(canonicalApproval()))
        assertEquals(-1, ApprovalWire.generation(legacyApproval()))
    }

    @Test
    fun canonical_body_has_exactly_three_keys() {
        val b = ApprovalWire.approvalBody("abc", "once", 31, resolveAll = true)
        assertEquals(setOf("request_id", "execution_generation", "choice"), b.keys().asSequence().toSet())
        assertEquals(31, b.getInt("execution_generation"))
        // resolveAll 在 canonical 下**不许**透传成 all：服务端是集合相等校验，多一个键就 400。
        assertFalse(b.has("all"))
    }

    @Test
    fun legacy_body_keeps_all_flag() {
        val b = ApprovalWire.approvalBody("abc", "once", -1, resolveAll = true)
        assertEquals("once", b.getString("choice"))
        assertEquals("abc", b.getString("request_id"))
        assertTrue(b.getBoolean("all"))
        assertFalse(b.has("execution_generation"))
    }

    @Test
    fun clarify_body_follows_the_same_split() {
        val c = ApprovalWire.clarifyBody("q1", "A", 7)
        assertEquals(setOf("request_id", "execution_generation", "answer"), c.keys().asSequence().toSet())
        assertEquals("A", c.getString("answer"))
        val l = ApprovalWire.clarifyBody("q1", "A", -1)
        assertEquals(setOf("clarify_id", "response"), l.keys().asSequence().toSet())
        assertEquals("q1", l.getString("clarify_id"))
    }

    @Test
    fun clarify_id_prefers_clarify_id_then_prompt_id() {
        assertEquals("q-legacy", ApprovalWire.clarifyId(JSONObject("""{"clarify_id":"q-legacy"}""")))
        assertEquals("q-canon", ApprovalWire.clarifyId(JSONObject("""{"prompt_id":"q-canon"}""")))
    }
}
