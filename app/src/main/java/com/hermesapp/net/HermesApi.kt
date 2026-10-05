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
        client.newCall(base("/health").get().build()).execute().use { it.isSuccessful }
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
    ): Call {
        val b = base("/v1/runs/" + runId + "/events")
            .header("Accept", "text/event-stream")
            .get()
        if (lastSeq >= 0) b.header("Last-Event-ID", lastSeq.toString())
        val call = client.newCall(b.build())
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
