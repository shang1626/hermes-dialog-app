package com.hermesapp.net

import android.content.Context
import com.hermesapp.AppLog
import com.hermesapp.Keys
import com.hermesapp.UpdateInfo
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns

data class SseEvent(val id: Int?, val event: String?, val data: JSONObject)

/**
 * 事件流订阅拿到 404：服务端已经没有这条 run 的 SSE 缓冲了。
 *
 * 2026-10-08：服务端 `_run_streams` 的 TTL(300s) 短于状态记录 TTL(3600s)，且清理器原来
 * 不管任务死活就删缓冲 —— 于是出现「GET /v1/runs/{id} 说 running，但 /events 立刻 404」
 * 的自相矛盾。客户端把 404 当普通断流去退避重连，就是「连接中断，N 秒后重试」反复刷、
 * 停不下来的直接来源（实测一条 run 连撞 24 次）。
 *
 * 单独一个异常类型，是为了让上层能把「流没了」和「网络抖了」分开处理：前者不再重起流，
 * 直接转去翻服务端会话记录等答案落盘。服务端清理器已同时修掉（task 活着不删缓冲）。
 */
class RunStreamGoneException(val runId: String) : IOException("HTTP 404: run stream gone")

/**
 * 只把 IPv4 地址交给 OkHttp 的连接层。
 *
 * 2026-10-08 实测（App 运行日志）：34 次 `ConnectException: Failed to connect to
 * your-gateway.example.com/[2001:db8:...]:443` **全部**打在 IPv6 上，而同一时刻 IPv4 一路通畅
 * （curl -4 与 -6 都能 200，但用户所在移动网络到 EdgeOne 的 IPv6 路由是黑洞：SYN 发出去
 * 没有任何回应）。内核按 TCP SYN 重传退避死等 —— 1+2+4+8≈15s、再加 16≈31s、再加 32≈63s ——
 * 这正是日志里普通请求「200 但要 14s / 28s / 67s」的来源：先撞 IPv6 撞满一整个超时才换地址。
 *
 * 后果分两种：主 client 开着 `retryOnConnectionFailure`，撞穿了会换地址重连，所以只是慢；
 * `probeClient` 关着重试，一撞上就立刻抛错 —— 而「右上角在线/离线」和「连接中断重连」全由它驱动，
 * 于是界面全程报离线、一直重连。
 *
 * 取 IPv4 优先（而不是彻底禁 IPv6）：IPv6 可用时仍会作为后备，只是不再排在前面被黑洞吃掉。
 */
private val IPv4FirstDns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        val v6 = all.filterNot { it is Inet4Address }
        return if (v4.isEmpty()) all else v4 + v6
    }
}

class HermesApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val prefix: String = "",
) {
    private val client = OkHttpClient.Builder()
        .dns(IPv4FirstDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * 在线探针专用 client：带硬性总超时。
     * 不能复用上面的 [client]——它 readTimeout=0（无限长，SSE 流式对话必须），
     * 连接半开（TCP 半开：连接还在、对面不回包）时 /health 会永久挂起，pingLoop 卡死在那一行，
     * 在线状态冻结在最后一次结果（表现为「掉线了还显示在线」）。
     */
    private val probeClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns)
        // 2026-10-08：超时放宽到 15s 只是治标（仍远小于 SSE 的 30s 读超时）。
        // 真正的病根是「连着就走 IPv6 黑洞」+ 这里关着重试，见 IPv4FirstDns 的注释。
        // 重试必须打开：主 client 一直开着它才没报错（只是慢），探针关掉就等于把
        // 一次 IPv6 撞墙直接判成「离线 / 断线」。
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * SSE 事件流专用 client：读取超时设成硬阈值，用来识别「链路假死」。
     *
     * 为什么不能用 [client]：它 readTimeout=0，连接半死（连接在、不回包）时
     * 那条流会永久挂着，界面表现是「发出去一直转圈、没有反应」，且永远不触发重连。
     *
     * 为什么阈值安全：服务端在两次事件之间每 10 秒必发一个 `: keepalive` 注释帧
     * （gateway/platforms/api_server.py: CHAT_COMPLETIONS_SSE_KEEPALIVE_SECONDS = 10），
     * 无论任务跑多久都有字节回来。取 30 秒 = 3 个心跳周期，正常空闲绝不误杀；
     * 真假死时 30 秒抛 SocketTimeoutException，交给上层走重连。
     */
    private val streamClient = OkHttpClient.Builder()
        .dns(IPv4FirstDns)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun full(path: String) = baseUrl.trimEnd('/') + prefix + path

    private fun base(path: String) = Request.Builder()
        .url(full(path))
        .header("Authorization", "Bearer " + apiKey)

    private fun sync(req: Request): JSONObject {
        val t0 = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val ms = System.currentTimeMillis() - t0
            if (!resp.isSuccessful) {
                AppLog.err("http", req.method + " " + req.url.encodedPath + " -> " + resp.code +
                    " " + ms + "ms " + text.take(120))
                throw IOException("HTTP " + resp.code + ": " + text.take(300))
            }
            AppLog.log("http", req.method + " " + req.url.encodedPath + " -> " + resp.code +
                " " + ms + "ms len=" + text.length)
            try {
                return JSONObject(text)
            } catch (e: Exception) {
                AppLog.err("http", req.method + " " + req.url.encodedPath + " 响应非 JSON len=" + text.length, e)
                throw e
            }
        }
    }

    /**
     * 发起一轮对话。
     *
     * [idempotencyKey] 非空时带上 Idempotency-Key 头：服务端把 (作用域, 键) 写进 SQLite
     * 记账，24 小时内同一个键只会真正执行一次。重复提交不会重跑，而是把**原来那轮的
     * run_id** 原样还回来，并在响应头 Idempotency-Replayed 标 true（正文里也有
     * replayed 字段）。因此重发是安全的：不会变成发两遍，还能直接接上原来那轮。
     *
     * 请求体不同、键相同 → 409 idempotency_key_conflict（指纹对不上，服务端拒绝）。
     * 这就是为什么重发必须复用同一份附件 id：body 变了指纹就变了。
     */
    fun startRun(
        input: String,
        sessionId: String?,
        images: List<String> = emptyList(),
        idempotencyKey: String = "",
    ): JSONObject {
        val body = JSONObject().put("input", input)
        if (!sessionId.isNullOrEmpty()) body.put("session_id", sessionId)
        if (images.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (id in images) arr.put(id)
            body.put("images", arr)
        }
        val rb = base("/v1/runs").post(body.toString().toRequestBody(jsonType))
        if (idempotencyKey.isNotEmpty()) rb.header("Idempotency-Key", idempotencyKey)
        return sync(rb.build())
    }

    /** 上传一张图片到 artifact 通道，返回 artifact_id（一次性、绑定本 profile 密钥作用域）。 */
    fun uploadImage(bytes: ByteArray, filename: String, mime: String): String {
        val mt = (mime.ifBlank { "image/jpeg" }).toMediaType()
        val req = base("/v1/artifacts/upload")
            .header("X-Artifact-Filename", filename)
            .post(bytes.toRequestBody(mt))
            .build()
        val t0 = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            val ms = System.currentTimeMillis() - t0
            if (!resp.isSuccessful) {
                AppLog.err("http", "POST /v1/artifacts/upload " + filename + " -> " + resp.code +
                    " " + ms + "ms " + text.take(120))
                throw IOException("HTTP " + resp.code + ": " + text.take(200))
            }
            AppLog.log("http", "POST /v1/artifacts/upload " + filename + " -> " + resp.code +
                " " + ms + "ms " + bytes.size + "B")
            return JSONObject(text).optString("artifact_id", "")
        }
    }

    /**
     * 澄清回执：POST /v1/runs/{id}/clarify，response 是用户选的选项（或自由文本）。
     * 返回是否被服务端接受（HTTP 2xx）。调用方据此给用户明确反馈——原来 runCatching
     * 把返回整个吞了，回执没送到时界面照样把卡片标成「已选择」，用户以为送达了其实没有
     * （与 steer 同一类缺陷）。
     */
    fun respondClarify(runId: String, clarifyId: String, response: String): Boolean = runCatching {
        val body = JSONObject().put("clarify_id", clarifyId).put("response", response)
        val t0 = System.currentTimeMillis()
        client.newCall(
            base("/v1/runs/" + runId + "/clarify").post(body.toString().toRequestBody(jsonType)).build()
        ).execute().use { resp ->
            AppLog.log("receipt", "澄清回执 run=" + runId.take(12) + " choice=" + response.take(40) +
                " -> " + resp.code + " " + (System.currentTimeMillis() - t0) + "ms")
            resp.isSuccessful
        }
    }.getOrDefault(false)

    /** 按需下载网关托管的媒体文件（大附件走这条路，不塞进消息体）。 */
    fun downloadMedia(token: String): ByteArray {
        val req = base("/v1/media/" + token).get().build()
        val t0 = System.currentTimeMillis()
        client.newCall(req).execute().use { resp ->
            val ms = System.currentTimeMillis() - t0
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                AppLog.err("http", "GET /v1/media/... -> " + resp.code + " " + ms + "ms " + text.take(120))
                throw IOException("HTTP " + resp.code + ": " + text.take(200))
            }
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            AppLog.log("http", "GET /v1/media/... -> " + resp.code + " " + ms + "ms " + bytes.size + "B")
            return bytes
        }
    }

    /** 服务端能力探测：features.supports_vision 决定图片走原生还是先转文字。 */
    fun capabilities(): JSONObject = sync(base("/v1/capabilities").get().build())

    fun getRun(runId: String): JSONObject =
        sync(base("/v1/runs/" + runId).get().build())

    /**
     * 运行状态探测结果——重连判据的核心。
     *
     * 原来的写法是 `try { getRun(rid).optString("status","") } catch { "" }`，
     * 失败时拿到空串，而空串不在「还在跑」的集合里 → 被当成「任务已结束」，
     * 于是第一次探测失败就彻底放弃重连（退避 8 次形同虚设）。必须把
     * 「服务端明确回答」和「探不出来（不知道）」分开。
     */
    sealed class RunStatus {
        /** 服务端明确回答了状态（如 running / completed）。 */
        data class Known(val status: String, val payload: JSONObject? = null) : RunStatus()

        /** 服务端明确说没有这个 run（HTTP 404）——可以判结束。 */
        object Missing : RunStatus()

        /** 探不出来：网络没通、超时、5xx、body 异常。不知道 ≠ 已结束。 */
        object Unknown : RunStatus()
    }

    /**
     * 探测 run 状态，走带硬超时的 [probeClient]。
     *
     * 不能用 [getRun]：它走 [client]（readTimeout=0，SSE 流式必须），
     * 连接半死时会永久挂起；而且它把 404 和网络异常都抛成同一个
     * IOException，上层分不清「任务真没了」和「网还没通」。
     */
    fun probeRun(runId: String): RunStatus = try {
        val t0 = System.currentTimeMillis()
        probeClient.newCall(base("/v1/runs/" + runId).get().build()).execute().use { resp ->
            val ms = System.currentTimeMillis() - t0
            when {
                resp.code == 404 -> {
                    AppLog.log("http", "GET /v1/runs/" + runId.take(12) + " -> 404 " + ms + "ms")
                    RunStatus.Missing
                }
                !resp.isSuccessful -> {
                    AppLog.err("http", "GET /v1/runs/" + runId.take(12) + " -> " + resp.code + " " + ms + "ms")
                    RunStatus.Unknown
                }
                else -> {
                    val text = resp.body?.string().orEmpty()
                    if (text.isEmpty()) RunStatus.Unknown
                    else {
                        val o = JSONObject(text)
                        // payload 一并带回：等待审批/澄清时里面挂着卡片载荷（request_id /
                        // clarify_id / question / choices 等），重开 App 靠它把待办卡片
                        // 重新挂回去。老写法只取 status 字符串，载荷被丢掉，重启后那张
                        // 等你点的卡片就没了。
                        RunStatus.Known(o.optString("status", ""), o)
                    }
                }
            }
        }
    } catch (e: Exception) {
        AppLog.err("http", "GET /v1/runs/" + runId.take(12) + " 探测异常", e)
        RunStatus.Unknown
    }

    /** 拉取服务端某会话的消息列表（重开 App 时补回后台任务产出）。 */
    fun sessionMessages(sessionId: String): JSONObject =
        sync(base("/api/sessions/" + sessionId + "/messages").get().build())

    /**
     * 会话最近 N 条消息（order=latest 取末尾）。子任务进度面板用：
     * 只取末尾一小段，避免把子代理读过的整份文件内容都拉回来。
     */
    fun sessionMessagesTail(sessionId: String, limit: Int = 40): JSONObject =
        sync(base("/api/sessions/" + sessionId + "/messages?limit=" + limit + "&order=latest").get().build())

    /**
     * 单个会话详情：含 tool_call_count / message_count / ended_at。
     * 子任务实时进度用它——比拉消息轻得多，一眼能看出「跑了几步、收工没有」。
     */
    fun sessionDetail(sessionId: String): JSONObject =
        sync(base("/api/sessions/" + sessionId).get().build())

    /**
     * 拉取服务端会话列表（每行带模型生成的 title）。
     * 用途：把服务端的正式标题同步回本地会话索引——本地只会生成「新对话」和首句截断，
     * 服务端由小模型生成 3~7 词的正式标题，质量更好，且历史会话也有。
     */
    fun listSessions(limit: Int = 200): JSONObject =
        sync(base("/api/sessions?limit=" + limit).get().build())

    fun stopRun(runId: String) {
        runCatching {
            client.newCall(base("/v1/runs/" + runId + "/stop").post("{}".toRequestBody(jsonType)).build())
                .execute().use { resp ->
                    AppLog.log("stop", "停止请求 run=" + runId.take(12) + " -> " + resp.code)
                }
        }
    }

    /**
     * 停止一个正在跑的子任务：POST /api/subagents/{id}/stop。
     *
     * 为什么不能复用 stopRun：子任务是后台子代理，可能比父轮次活得久，父 run 的
     * /v1/runs/{id}/stop 管不到它。服务端对子代理对象直接发协作式中断，返回
     * {"ok":true,"found":bool}；found=false 表示那个子任务已经不在跑了（不算失败）。
     */
    fun stopSubagent(subagentId: String): JSONObject =
        sync(base("/api/subagents/" + subagentId + "/stop").post("{}".toRequestBody(jsonType)).build())

    /**
     * 中途插话：把这句话注入本轮。返回是否被服务端接受（HTTP 2xx）。
     * 服务端只在 run 状态为 running 且 agent 支持 steer 时接受（409 = 本轮已收尾/不接受）；
     * 调用方据此给用户明确反馈，不再静默吞掉结果。
     */
    fun steer(runId: String, text: String): Boolean = runCatching {
        val body = JSONObject().put("input", text)
        client.newCall(base("/v1/runs/" + runId + "/steer").post(body.toString().toRequestBody(jsonType)).build())
            .execute().use { resp ->
                AppLog.log("steer", "插话 run=" + runId.take(12) + " -> " + resp.code + " len=" + text.length)
                resp.isSuccessful
            }
    }.getOrDefault(false)

    /**
     * 审批回执：POST /v1/runs/{id}/approval，choice ∈ once/session/always/deny。
     * 返回是否被服务端接受（HTTP 2xx）；失败时调用方给「回执没送到」提示，不静默。
     */
    fun respondApproval(runId: String, requestId: String, choice: String): Boolean = runCatching {
        val body = JSONObject().put("choice", choice)
        if (requestId.isNotEmpty()) body.put("request_id", requestId)
        val t0 = System.currentTimeMillis()
        client.newCall(
            base("/v1/runs/" + runId + "/approval").post(body.toString().toRequestBody(jsonType)).build()
        ).execute().use { resp ->
            AppLog.log("receipt", "审批回执 run=" + runId.take(12) + " choice=" + choice +
                " -> " + resp.code + " " + (System.currentTimeMillis() - t0) + "ms")
            resp.isSuccessful
        }
    }.getOrDefault(false)

    // ---------- 定时任务（服务端 /api/jobs） ----------

    /** 定时任务列表；includeDisabled=true 时连已停用的一起返回。 */
    fun listJobs(includeDisabled: Boolean = false): JSONObject =
        sync(base("/api/jobs?include_disabled=" + includeDisabled).get().build())

    private fun postJob(path: String) {
        val req = base(path).post("{}".toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                AppLog.err("job", "POST " + path + " -> " + resp.code + " " + text.take(120))
                throw IOException("HTTP " + resp.code + ": " + text.take(200))
            }
            AppLog.log("job", "POST " + path + " -> " + resp.code)
        }
    }

    fun pauseJob(jobId: String) = postJob("/api/jobs/" + jobId + "/pause")

    fun resumeJob(jobId: String) = postJob("/api/jobs/" + jobId + "/resume")

    fun runJob(jobId: String) = postJob("/api/jobs/" + jobId + "/run")

    /**
     * App 收件箱：定时任务的产出。
     * 为什么要它：App 走 api_server 通道，而那条通道不支持推送（服务端
     * supports_async_delivery=False），定时任务结果没法主动推过来。服务端投递时把产出留档
     * （cron/app_inbox.py），App 来拉，再调 ackInbox 确认已读。
     */
    fun inbox(limit: Int = 50, unackedOnly: Boolean = false): JSONObject =
        sync(
            base(
                "/api/inbox?limit=" + limit + (if (unackedOnly) "&unacked=true" else "")
            ).get().build()
        )

    /** 标记收件箱已读：ids 非空按 id 标；all=true 整箱标（返回改动条数）。 */
    fun ackInbox(ids: List<String> = emptyList(), all: Boolean = false): JSONObject {
        val body = JSONObject()
        if (all) body.put("all", true)
        if (ids.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (i in ids) arr.put(i)
            body.put("ids", arr)
        }
        return sync(base("/api/inbox/ack").post(body.toString().toRequestBody(jsonType)).build())
    }

    /** 删除收件箱条目：ids 非空按 id 删；all=true 整箱清空（返回删除条数）。 */
    fun deleteInbox(ids: List<String> = emptyList(), all: Boolean = false): JSONObject {
        val body = JSONObject()
        if (all) body.put("all", true)
        if (ids.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (i in ids) arr.put(i)
            body.put("ids", arr)
        }
        return sync(base("/api/inbox/delete").post(body.toString().toRequestBody(jsonType)).build())
    }

    /**
     * 诊断上报：把运行日志（gzip+base64）与一份紧凑状态快照推给服务端。
     * 为什么反过来推：App 在手机上、没有对外入口，agent 连不进来，出问题只能用户手动发日志/截图。
     * 走既有网关通道 + API key，不新开端口、不暴露手机。正文原样上报（用户 2026-10-08 定）。
     */
    fun uploadAppLog(
        log: String, snapshot: JSONObject?, version: String, device: String, reason: String,
        crash: String = "", fault: String = "",
    ): JSONObject {
        val compressed = gzipBase64(log)
        val body = JSONObject()
            .put("log", compressed)
            .put("encoding", "gzip+base64")
            .put("version", version)
            .put("device", device)
            .put("reason", reason)
        if (snapshot != null) body.put("snapshot", snapshot)
        // 闪退/服务故障单独带：闪退时 run.log 可能没落盘，堆栈只在这两份里。
        if (crash.isNotEmpty()) body.put("crash", crash)
        if (fault.isNotEmpty()) body.put("fault", fault)
        return sync(base("/api/applog").post(body.toString().toRequestBody(jsonType)).build())
    }

    /** 轮询服务端是否请求过「请上报」：requested=true 时 App 自动传一份。 */
    fun applogPending(): JSONObject = sync(base("/api/applog/pending").get().build())

    /** gzip 压缩 + base64：日志纯文本压缩比高（1MB→约 100KB），省流量。失败退回空串。 */
    private fun gzipBase64(text: String): String = runCatching {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrDefault("")

    fun sysinfo(): JSONObject = sync(base("/health/sysinfo").get().build())

    fun healthDetailed(): JSONObject = sync(base("/health/detailed").get().build())

    fun ping(): Boolean = runCatching {
        probeClient.newCall(base("/health").get().build()).execute().use { resp ->
            if (!resp.isSuccessful) AppLog.err("http", "GET /health -> " + resp.code)
            resp.isSuccessful
        }
    }.getOrDefault(false)

    fun checkUpdate(): UpdateInfo? {
        // 带时间戳绕开 EdgeOne 边缘缓存：否则刚发的新版本可能被旧缓存糊弄。
        val sep = if (Keys.UPDATE_URL.contains("?")) "&" else "?"
        val req = Request.Builder()
            .url(Keys.UPDATE_URL + sep + "t=" + System.currentTimeMillis())
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.err("update", "检查更新 HTTP " + resp.code)
                return null
            }
            val o = JSONObject(resp.body?.string().orEmpty())
            return UpdateInfo(
                o.optInt("versionCode", 0),
                o.optString("versionName", ""),
                o.optString("url", ""),
                // 发布文件里版本说明的键名历史上两种都出现过（notes / changelog），
                // 只认一种就会让弹窗空白——两个都认，notes 优先。
                o.optString("notes", "").ifEmpty { o.optString("changelog", "") },
                o.optLong("size", 0L),
                o.optString("md5", "")
            )
        }
    }

    /**
     * 下载 APK，onProgress(downloaded, total)；total <= 0 表示服务器未给长度。
     *
     * 两条纪律（修「内置下载完提示已安装相同版本」）：
     * 1. 文件名带版本号、先写 .part 再改名、交付前清掉目录里其它旧包——
     *    原来固定写 hermes-update.apk 且装完不删，某次下载写失败后安装器打开的还是旧包。
     * 2. [expectMd5] 非空时下完核对指纹，对不上直接丢弃，绝不把残包交给安装器。
     */
    fun downloadApk(
        url: String,
        ctx: Context,
        versionName: String,
        expectMd5: String,
        onProgress: (Long, Long) -> Unit,
    ): File? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                AppLog.log("update", "下载失败 HTTP " + resp.code)
                return null
            }
            val body = resp.body ?: return null
            val total = body.contentLength()
            val dir = File(ctx.getExternalFilesDir(null), "apk")
            dir.mkdirs()
            val safeVer = versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
            val part = File(dir, "hermes-" + safeVer + ".apk.part")
            val f = File(dir, "hermes-" + safeVer + ".apk")
            runCatching { f.delete() }
            runCatching { part.delete() }
            AppLog.log("update", "开始下载 " + f.name + " 期望大小=" + total + " 期望md5=" + expectMd5)
            var done = 0L
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            // 长度对不上 = 残包（链路中途被掐），丢弃。
            if (total > 0 && done != total) {
                AppLog.log("update", "大小不符，丢弃 done=" + done + " total=" + total)
                runCatching { part.delete() }
                return null
            }
            if (expectMd5.isNotEmpty()) {
                val got = md5(part)
                if (!got.equals(expectMd5, ignoreCase = true)) {
                    AppLog.log("update", "md5 不符，丢弃 got=" + got + " 期望=" + expectMd5)
                    runCatching { part.delete() }
                    return null
                }
            } else {
                AppLog.log("update", "version.json 未给 md5，只核对了大小")
            }
            if (!part.renameTo(f)) {
                AppLog.log("update", "重命名失败 " + part.name)
                runCatching { part.delete() }
                return null
            }
            // 清掉目录里其它旧包：安装器扫到旧包正是「已安装相同版本」的来源。
            runCatching { dir.listFiles()?.forEach { if (it.name != f.name) it.delete() } }
            AppLog.log("update", "下载完成 " + f.name + " size=" + done)
            return f
        }
    }

    /** 文件 md5（小写十六进制）；出错返回空串。 */
    private fun md5(f: File): String = try {
        val md = java.security.MessageDigest.getInstance("MD5")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        ""
    }

    /** 按 run_id 取长期留档的完成语音；服务端没有（未开 TTS/已淘汰）返回 null。 */
    fun downloadVoice(runId: String): ByteArray? {
        if (runId.isEmpty()) return null
        val req = base("/v1/voice/" + runId).get().build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    AppLog.log("voice", "取留档语音 HTTP " + resp.code + " run=" + runId.take(12))
                    null
                } else {
                    resp.body?.bytes()?.takeIf { it.isNotEmpty() }
                }
            }
        } catch (e: Exception) {
            AppLog.err("voice", "取留档语音失败 run=" + runId.take(12), e)
            null
        }
    }

    fun streamEvents(
        runId: String,
        lastSeq: Int,
        onEvent: (SseEvent) -> Unit,
        onClosed: () -> Unit,
        onError: (Throwable) -> Unit,
        /**
         * 每读到一行（含 `: keepalive` 心跳注释帧）就回调一次，用来刷新「流还活着」的时间戳。
         *
         * 为什么必须单列这个回调：服务端在两次事件之间每 10 秒必发一个 `: keepalive`
         * 注释帧，但它既不是 `id:`/`event:`/`data:` 行、也不是空行，解析器原先直接跳过，
         * 不触发 onEvent。于是任务长时间只跑工具（如终端命令 180 秒）时，上层只看到
         * 心跳、lastEventAt 长时间不刷新，被「25 秒没事件即假死」的看门狗误判成断流，
         * 主动掐掉一条本来健康的流去重连——表现就是「一直在重连」。
         */
        onActivity: () -> Unit = {},
    ): Call {
        val b = base("/v1/runs/" + runId + "/events")
            .header("Accept", "text/event-stream")
            .get()
        if (lastSeq >= 0) b.header("Last-Event-ID", lastSeq.toString())
        // 走 streamClient（readTimeout=30s）：链路假死时能抛超时断开，交给上层重连。
        val call = streamClient.newCall(b.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = onError(e)
            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (resp.code == 404) { onError(RunStreamGoneException(runId)); return }
                    if (!resp.isSuccessful) { onError(IOException("HTTP " + resp.code)); return }
                    val src = resp.body?.source()
                    if (src == null) { onError(IOException("empty body")); return }
                    var id: Int? = null
                    var event: String? = null
                    val data = StringBuilder()
                    try {
                        while (!src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            // 每读到一行（含 `: keepalive` 注释帧）就算一次「流还活着」。
                            // 必须放在 when 之外：心跳行不以 id:/event:/data: 开头、也不是空行，
                            // 落不到任何分支；若只在分支里回调，心跳永远刷不到活跃时间，
                            // 长工具执行期间（只有心跳、没有真实事件）会被看门狗误判成假死。
                            onActivity()
                            when {
                                line.startsWith("id:") -> id = line.substring(3).trim().toIntOrNull()
                                line.startsWith("event:") -> event = line.substring(6).trim()
                                line.startsWith("data:") -> data.append(line.substring(5).trim())
                                line.isEmpty() -> {
                                    if (data.isNotEmpty()) {
                                        runCatching { onEvent(SseEvent(id, event, JSONObject(data.toString()))) }
                                    }
                                    id = null; event = null; data.setLength(0)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                    onClosed()
                }
            }
        })
        return call
    }
}
