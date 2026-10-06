package com.hermesapp.net

import android.content.Context
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
     * 隧道半开（连接不断也不回包）时 /health 会永久挂起，pingLoop 卡死在那一行，
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
     * SSE 事件流专用 client：读取超时设成硬阈值，用来识别「隧道假死」。
     *
     * 为什么不能用 [client]：它 readTimeout=0，隧道半死（连接在、不回包）时
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

    fun startRun(input: String, sessionId: String?, images: List<String> = emptyList()): JSONObject {
        val body = JSONObject().put("input", input)
        if (!sessionId.isNullOrEmpty()) body.put("session_id", sessionId)
        if (images.isNotEmpty()) {
            val arr = org.json.JSONArray()
            for (id in images) arr.put(id)
            body.put("images", arr)
        }
        return sync(base("/v1/runs").post(body.toString().toRequestBody(jsonType)).build())
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
        data class Known(val status: String) : RunStatus()

        /** 服务端明确说没有这个 run（HTTP 404）——可以判结束。 */
        object Missing : RunStatus()

        /** 探不出来：网络没通、超时、5xx、body 异常。不知道 ≠ 已结束。 */
        object Unknown : RunStatus()
    }

    /**
     * 探测 run 状态，走带硬超时的 [probeClient]。
     *
     * 不能用 [getRun]：它走 [client]（readTimeout=0，SSE 流式必须），
     * 隧道半死时会永久挂起；而且它把 404 和网络异常都抛成同一个
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
                    else RunStatus.Known(JSONObject(text).optString("status", ""))
                }
            }
        }
    } catch (_: Exception) {
        RunStatus.Unknown
    }

    /** 拉取服务端某会话的消息列表（重开 App 时补回后台任务产出）。 */
    fun sessionMessages(sessionId: String): JSONObject =
        sync(base("/api/sessions/" + sessionId + "/messages").get().build())

    fun stopRun(runId: String) {
        runCatching {
            client.newCall(base("/v1/runs/" + runId + "/stop").post("{}".toRequestBody(jsonType)).build())
                .execute().use { it.body?.string() }
        }
    }

    fun steer(runId: String, text: String) {
        runCatching {
            val body = JSONObject().put("input", text)
            client.newCall(base("/v1/runs/" + runId + "/steer").post(body.toString().toRequestBody(jsonType)).build())
                .execute().use { it.body?.string() }
        }
    }

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

    fun sysinfo(): JSONObject = sync(base("/health/sysinfo").get().build())

    fun healthDetailed(): JSONObject = sync(base("/health/detailed").get().build())

    fun ping(): Boolean = runCatching {
        probeClient.newCall(base("/health").get().build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    fun checkUpdate(): UpdateInfo? {
        val req = Request.Builder().url(Keys.UPDATE_URL).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val o = JSONObject(resp.body?.string().orEmpty())
            return UpdateInfo(
                o.optInt("versionCode", 0),
                o.optString("versionName", ""),
                o.optString("url", ""),
                o.optString("notes", ""),
                o.optLong("size", 0L)
            )
        }
    }

    /** 下载 APK，onProgress(downloaded, total)；total <= 0 表示服务器未给长度。 */
    fun downloadApk(url: String, ctx: Context, onProgress: (Long, Long) -> Unit): File? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val total = body.contentLength()
            val dir = File(ctx.getExternalFilesDir(null), "apk")
            dir.mkdirs()
            val f = File(dir, "hermes-update.apk")
            var done = 0L
            body.byteStream().use { input ->
                f.outputStream().use { out ->
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
            return f
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
        // 走 streamClient（readTimeout=30s）：隧道假死时能抛超时断开，交给上层重连。
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
