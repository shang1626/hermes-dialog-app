package com.hermesapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 审批说明中文化的单测：词表完整性 + 组合句 + 未收录原样返回。 */
class ApprovalZhTest {

    @Test
    fun 已知的英文说明被翻成中文() {
        assertEquals("递归删除", approvalDescZh("recursive delete"))
        assertEquals("递归删除家目录", approvalDescZh("recursive delete of home directory"))
        assertEquals("git 强制推送（会改写远端历史）", approvalDescZh("git force push (rewrites remote history)"))
        assertEquals("关机或重启系统", approvalDescZh("system shutdown/reboot"))
    }

    @Test
    fun 前后空白不影响命中() {
        assertEquals("递归删除", approvalDescZh("  recursive delete "))
    }

    @Test
    fun 未收录的说明原样返回() {
        assertEquals("Some plugin自定义 rule", approvalDescZh("Some plugin自定义 rule"))
        assertEquals("", approvalDescZh(""))
    }

    @Test
    fun 组合句保留动态部分() {
        val ssh = approvalDescZh(
            "Write to SSH client config file(s): /root/.ssh/config, /home/a/.ssh/config. " +
                "The SSH config can carry ProxyCommand / Match exec directives that run commands, " +
                "so writes require your approval.")
        assertTrue(ssh, ssh.startsWith("写入 SSH 客户端配置文件：/root/.ssh/config, /home/a/.ssh/config"))
        assertTrue(ssh, ssh.contains("需要你批准"))

        val instr = approvalDescZh(
            "Write to protected agent-instruction file(s): /root/AGENTS.md. These files steer future " +
                "agent behavior; approval is always required (not bypassed by auto-approve).")
        assertTrue(instr, instr.startsWith("写入受保护的 agent 指令文件：/root/AGENTS.md"))
        assertTrue(instr, instr.contains("每次都必须批准"))

        val cu = approvalDescZh("Allow computer_use to perform `left_click`?")
        assertEquals("允许 computer_use 执行「left_click」？", cu)
    }

    @Test
    fun 词表本身没坏() {
        // 上游加模式、或有人改词表时，这两条断言能兜住「漏译/空译/整表被截断」。
        assertTrue("词表条数=" + APPROVAL_DESC_ZH.size, APPROVAL_DESC_ZH.size >= 110)
        for ((en, zh) in APPROVAL_DESC_ZH) {
            assertTrue("空译文：" + en, zh.isNotBlank())
            assertTrue("疑似漏译：" + en, zh != en)
            assertTrue("键有空白：" + en, en == en.trim())
        }
    }
}
