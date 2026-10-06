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
import java.util.concurrent.TimeUnit

data class SseEvent(val id: Int?, val event: String?, val data: JSONObject)

class HermesApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val prefix: String = "",
) {
    private val client = OkHttpClient.Builder()
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
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
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
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + ": " + text.take(300))
            return JSONObject(text)
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
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + ": " + text.take(200))
            return JSONObject(text).optString("artifact_id", "")
        }
    }

    /** 澄清回执：POST /v1/runs/{id}/clarify，response 是用户选的选项（或自由文本）。 */
    fun respondClarify(runId: String, clarifyId: String, response: String) {
        runCatching {
            val body = JSONObject().put("clarify_id", clarifyId).put("response", response)
            client.newCall(
                base("/v1/runs/" + runId + "/clarify").post(body.toString().toRequestBody(jsonType)).build()
            ).execute().use { it.body?.string() }
        }
    }

    /** 按需下载网关托管的媒体文件（大附件走这条路，不塞进消息体）。 */
    fun downloadMedia(token: String): ByteArray {
        val req = base("/v1/media/" + token).get().build()
        client.newCall(req).execute().use { resp ->
            val text = if (resp.isSuccessful) "" else resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + ": " + text.take(200))
            return resp.body?.bytes() ?: ByteArray(0)
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
        probeClient.newCall(base("/v1/runs/" + runId).get().build()).execute().use { resp ->
            when {
                resp.code == 404 -> RunStatus.Missing
                !resp.isSuccessful -> RunStatus.Unknown
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
    } catch (_: Exception) {
        RunStatus.Unknown
    }

    /** 拉取服务端某会话的消息列表（重开 App 时补回后台任务产出）。 */
    fun sessionMessages(sessionId: String): JSONObject =
        sync(base("/api/sessions/" + sessionId + "/messages").get().build())

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
                .execute().use { it.body?.string() }
        }
    }

    /**
     * 中途插话：把这句话注入本轮。返回是否被服务端接受（HTTP 2xx）。
     * 服务端只在 run 状态为 running 且 agent 支持 steer 时接受（409 = 本轮已收尾/不接受）；
     * 调用方据此给用户明确反馈，不再静默吞掉结果。
     */
    fun steer(runId: String, text: String): Boolean = runCatching {
        val body = JSONObject().put("input", text)
        client.newCall(base("/v1/runs/" + runId + "/steer").post(body.toString().toRequestBody(jsonType)).build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** 审批回执：POST /v1/runs/{id}/approval，choice ∈ once/session/always/deny。 */
    fun respondApproval(runId: String, requestId: String, choice: String) {
        runCatching {
            val body = JSONObject().put("choice", choice)
            if (requestId.isNotEmpty()) body.put("request_id", requestId)
            client.newCall(
                base("/v1/runs/" + runId + "/approval").post(body.toString().toRequestBody(jsonType)).build()
            ).execute().use { it.body?.string() }
        }
    }

    // ---------- 定时任务（服务端 /api/jobs） ----------

    /** 定时任务列表；includeDisabled=true 时连已停用的一起返回。 */
    fun listJobs(includeDisabled: Boolean = false): JSONObject =
        sync(base("/api/jobs?include_disabled=" + includeDisabled).get().build())

    private fun postJob(path: String) {
        val req = base(path).post("{}".toRequestBody(jsonType)).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP " + resp.code + ": " + text.take(200))
        }
    }

    fun pauseJob(jobId: String) = postJob("/api/jobs/" + jobId + "/pause")

    fun resumeJob(jobId: String) = postJob("/api/jobs/" + jobId + "/resume")

    fun runJob(jobId: String) = postJob("/api/jobs/" + jobId + "/run")

    fun sysinfo(): JSONObject = sync(base("/health/sysinfo").get().build())

    fun healthDetailed(): JSONObject = sync(base("/health/detailed").get().build())

    fun ping(): Boolean = runCatching {
        probeClient.newCall(base("/health").get().build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun checkUpdate(): UpdateInfo? {
        // 带时间戳绕开 EdgeOne 边缘缓存：否则刚发的新版本可能被旧缓存糊弄。
        val sep = if (Keys.UPDATE_URL.contains("?")) "&" else "?"
        val req = Request.Builder()
            .url(Keys.UPDATE_URL + sep + "t=" + System.currentTimeMillis())
            .get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            return UpdateInfo(
                o.optInt("versionCode", 0),
                o.optString("versionName", ""),
                o.optString("url", ""),
                o.optString("notes", ""),
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
