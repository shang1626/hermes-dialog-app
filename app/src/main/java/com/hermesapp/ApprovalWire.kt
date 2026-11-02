package com.hermesapp

import org.json.JSONObject

/**
 * 审批/澄清载荷的归一化读取与回执体构造（纯逻辑，可单测）。
 *
 * 为什么需要这一层（2026-11-03 实测）：
 * 服务端 run 事件流换成 canonical session-authority 通道后，`approval.request` 的载荷字段
 * 从 `request_id` 变成了 `prompt_id`，另外多一个 `execution_generation`。App 原来只认
 * `request_id`，取到空串就在 `placeApproval` 顶部 `return` —— 通知弹了、卡片没挂，
 * 用户打开 App 就是「有提示消息但没有审批操作」。
 *
 * 回执侧同样变了：canonical 通道的 `respond_run` 要求 body **恰好**是
 * `{request_id, execution_generation, choice}`（澄清是 `answer`），多一个键（比如老的
 * `all`）或少 `execution_generation` 都判 invalid_params(400)。老通道只认
 * `choice`/`request_id`/`all` 与 `clarify_id`/`response`。
 *
 * 所以两边都发不保险（两边都做集合校验）→ 回执体按「有没有 generation」分流，
 * 发出去若被 400 再换另一种形状重试一次（见 HermesApi.respondApproval）。
 */
object ApprovalWire {

    /** 审批请求 id：canonical 用 `prompt_id`，老通道用 `request_id`，两个都试。 */
    fun promptId(data: JSONObject): String {
        val legacy = data.optString("request_id", "")
        if (legacy.isNotEmpty()) return legacy
        return data.optString("prompt_id", "")
    }

    /** 澄清请求 id：同上（canonical 的 clarify 也用 `prompt_id`）。 */
    fun clarifyId(data: JSONObject): String {
        val legacy = data.optString("clarify_id", "")
        if (legacy.isNotEmpty()) return legacy
        return data.optString("prompt_id", "")
    }

    /** 执行代号；老通道没有这个字段，返回 -1 表示「只有老形状可用」。 */
    fun generation(data: JSONObject): Int = data.optInt("execution_generation", -1)

    /**
     * 审批回执体。
     *
     * canonical（generation >= 0）**只能**发三个键，服务端用集合相等校验，多一个 `all`
     * 就 400。此时「全部批准」的批量效果由 App 本地武装（autoApproveRun）实现。
     */
    fun approvalBody(requestId: String, choice: String, generation: Int, resolveAll: Boolean): JSONObject {
        val body = JSONObject().put("choice", choice)
        if (generation >= 0) {
            body.put("request_id", requestId).put("execution_generation", generation)
            return body
        }
        if (requestId.isNotEmpty()) body.put("request_id", requestId)
        if (resolveAll) body.put("all", true)
        return body
    }

    /** 澄清回执体：canonical 要求 `{request_id, execution_generation, answer}`。 */
    fun clarifyBody(clarifyId: String, response: String, generation: Int): JSONObject {
        val body = JSONObject()
        if (generation >= 0) {
            body.put("request_id", clarifyId).put("execution_generation", generation)
                .put("answer", response)
            return body
        }
        body.put("clarify_id", clarifyId).put("response", response)
        return body
    }
}
