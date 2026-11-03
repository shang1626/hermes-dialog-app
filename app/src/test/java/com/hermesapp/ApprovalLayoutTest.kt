package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批卡片按钮排版的纯逻辑口径（见 Screens.kt 里审批卡片的注释）。
 *
 * 背景：旧版把 once/session/always/deny + 全部批准 + 忽略 六个胶囊塞进一个 Row，
 * 手机上折行后变成「允许一次拒绝本次会话允许 始终允 许」。现在按决策强度分三行，
 * 这几条断言把「分组不丢项、不重复、标签单行能显示得下」钉住。
 */
class ApprovalLayoutTest {

    private val serverChoices = listOf("once", "session", "always", "deny")

    @Test
    fun `分组覆盖服务端下发的全部选项`() {
        val grouped = APPROVAL_PRIMARY + APPROVAL_RELAX
        assertEquals(serverChoices.toSet(), grouped)
    }

    @Test
    fun `两组不重叠`() {
        assertTrue((APPROVAL_PRIMARY intersect APPROVAL_RELAX).isEmpty())
    }

    @Test
    fun `每个选项都有中文标签且不是原样回显`() {
        for (ch in serverChoices) {
            val label = choiceLabel(ch)
            assertTrue("choice=$ch 没有中文标签", label.isNotBlank())
            assertTrue("choice=$ch 仍在显示英文键", label != ch)
        }
    }

    @Test
    fun `最长标签能在半屏宽度内单行显示`() {
        // 手机 360dp 宽 - 卡片左右内边距(12*2) - 气泡内边距 ≈ 300dp；两列各约 147dp。
        // 13sp 中文约 13dp/字，最长标签 6 字 ≈ 78dp + 左右内边距 12dp + 边框，远小于列宽。
        val longest = serverChoices.maxOf { choiceLabel(it).length }
        assertTrue("最长标签字数 $longest 超出两列可容纳范围", longest <= 6)
    }
}
