package com.hermesapp

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hermesapp.net.HermesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.Call
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Msg(
    val role: String,
    val text: String,
    var pending: Boolean = false,
    val ts: Long = 0L,
    /** 本条消息附带的本地图片路径（用户发的图用于气泡回显缩略图）。 */
    val images: List<String> = emptyList(),
    /** 本条消息附带的非图片附件名（气泡里回显成文件卡片）。 */
    val files: List<String> = emptyList(),
    /** 过程轨迹（工具调用等）。与正文分开存，界面上默认折叠，不占屏幕。 */
    val trace: String = "",
    /** 本轮 token 用量（run.completed 的 usage），仅助手消息有。 */
    val usage: Usage? = null,
    /** 审批卡片：服务端在等一个 choice 回执；空表示无待审批。 */
    val approval: ApprovalCard? = null,
    /** 澄清卡片：服务端在等我问你选项的回执。 */
    val clarify: ClarifyCard? = null,
    /** 子任务进度（delegate_task 派出的子代理），按开始顺序排列。 */
    val subagents: List<SubagentLine> = emptyList(),
    /** 稳定唯一 id：给 LazyColumn 做 key，避免滑动时整列重组。 */
    val id: Long = nextMsgId(),
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextMsgId(): Long = counter.incrementAndGet()
    }
}

/** 审批卡片：服务端 approval.request 事件下发，用户选一个 choice 回执后置 resolved。 */
data class ApprovalCard(
    val requestId: String,
    val command: String,
    val description: String,
    val choices: List<String>,
    val resolved: String = "",
)

/** 澄清卡片：服务端 clarify.request 事件下发（我问你「选 A 还是 B」），点选项回执。 */
data class ClarifyCard(
    val clarifyId: String,
    val question: String,
    val choices: List<String>,
    val multiSelect: Boolean = false,
    val resolved: String = "",
)

/** 一轮对话的 token 用量（run.completed 的 usage 字段）。 */
data class Usage(
    val input: Int,
    val output: Int,
    val total: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
    /** 本轮耗时（毫秒），用来算每秒出多少 token；0 表示没测到。 */
    val durationMs: Long = 0L,
)

/** 一条子任务进度（subagent.start / subagent.complete）。 */
data class SubagentLine(
    val id: String,
    val goal: String,
    val status: String,
    val summary: String = "",
)

/** 服务端会话记录的一行（翻历史兜底时用来认锚点、认答案）。 */
private data class HistRow(val role: String, val text: String, val id: String)

/** 待发送的附件：uri 用于展示（图片缩略图），file 是拷进沙盒后的真实文件。 */
data class PendingImage(
    val id: Long,
    val uri: String,
    val file: java.io.File,
    /** 是否图片：决定待发区显示缩略图还是文件卡片。 */
    val isImage: Boolean = true,
)

/** 状态页的一行：标签 + 值。value 为空则该行不显示。 */
data class StatusItem(val label: String, val value: String)

/** 状态页的一个分组：标题 + 若干行。 */
data class StatusSection(val title: String, val items: List<StatusItem>)

/** SSE 断流后的最大自动重连次数（退避等待，见 ChatViewModel.backoffDelayMs）。 */
private const val MAX_RECONNECT_ATTEMPTS = 8

/** 断线丢事件时补进正文的提示行。 */
private const val TRUNCATED_NOTICE = "\n[提示] 断线期间有内容未收到，已从服务端补拉最新结果\n"

/**
 * 断流翻历史的盯梢窗口：重连退避用尽后，改为轮询服务端会话记录等答案落盘。
 * 首次等 5 秒，逐次翻倍到 30 秒封顶，最多盯 30 分钟。
 */
private const val HISTORY_RECOVERY_WINDOW_MS = 30L * 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private var store = SessionStore(app, prefs.profile)

    /**
     * 一个会话的运行态。多会话并行：每个会话各自持有消息、忙闲、当前 run，
     * 切换会话只换「正在看的」id，后台会话的流照常接收、写进它自己的缓冲。
     */
    private class SessionRuntime(val id: String) {
        val messages = MutableStateFlow<List<Msg>>(emptyList())
        val busy = MutableStateFlow(false)
        val retryNote = MutableStateFlow("")
        var runId: String = ""
        var call: Call? = null
        var lastSeq: Int = -1
        var autoContinue: Int = 0
        var finished: Boolean = false
        var startedAt: Long = 0L
        var loaded: Boolean = false
        var saveJob: Job? = null
        /** 最近一次收到事件的墙钟时间，用于「回到前台」判断流是否已假死。 */
        var lastEventAt: Long = 0L
        /** 流式攒帧器：把碎字按帧放送，避免一大块一大块地跳。 */
        var coalescer: StreamDeltaCoalescer? = null
        /** 本轮发送前该会话已有多少条用户消息：断流翻历史时的位置锚点。 */
        var priorUserCount: Int = 0
        /** 本轮待发正文（trim 过）：锚点的内容校验用。 */
        var pendingSendText: String = ""
        /** 断流翻历史的轮询任务；收到正常事件或任务结束时取消。 */
        var recoveryJob: Job? = null
    }

    private val runtimes = ConcurrentHashMap<String, SessionRuntime>()
    private fun rt(id: String): SessionRuntime = runtimes.computeIfAbsent(id) { SessionRuntime(it) }

    private val _currentId = MutableStateFlow("")
    val currentId = _currentId.asStateFlow()

    /** 当前会话的消息/忙闲/重连提示：随 _currentId 切换，后台会话互不影响。 */
    val messages: StateFlow<List<Msg>> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(emptyList<Msg>()) else rt(id).messages }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val busy: StateFlow<Boolean> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(false) else rt(id).busy }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val retryNote: StateFlow<String> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf("") else rt(id).retryNote }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _sessions = MutableStateFlow<List<SessionMeta>>(emptyList())
    val sessions = _sessions.asStateFlow()

    private val _online = MutableStateFlow(false)
    val online = _online.asStateFlow()

    private val _statusSections = MutableStateFlow<List<StatusSection>>(emptyList())
    val statusSections = _statusSections.asStateFlow()

    private val _statusErr = MutableStateFlow("")
    val statusErr = _statusErr.asStateFlow()

    private val _updateNote = MutableStateFlow("")
    val updateNote = _updateNote.asStateFlow()

    /** 有待确认的新版本时为非空。 */
    private val _pendingUpdate = MutableStateFlow<UpdateInfo?>(null)
    val pendingUpdate = _pendingUpdate.asStateFlow()

    /** 服务端存在比本机更新的版本时为 true：抽屉里「设置」右上角显示绿点。 */
    private val _updateBadge = MutableStateFlow(false)
    val updateBadge = _updateBadge.asStateFlow()

    /** 正在跑任务的会话 id 集合：会话列表里给它们显示「执行中」标识。 */
    private val _runningIds = MutableStateFlow<Set<String>>(emptySet())
    val runningIds = _runningIds.asStateFlow()

    /** -1 未下载；0..100 下载中百分比。 */
    private val _downloadPct = MutableStateFlow(-1)
    val downloadPct = _downloadPct.asStateFlow()

    private val _downloadText = MutableStateFlow("")
    val downloadText = _downloadText.asStateFlow()

    /** 待发送图片（选好未发送）。 */
    private val _pendingImages = MutableStateFlow<List<PendingImage>>(emptyList())
    val pendingImages = _pendingImages.asStateFlow()

    /** 模型是否支持原生图片（/v1/capabilities features.supports_vision）；未知按 true。 */
    private val _supportsVision = MutableStateFlow(true)
    val supportsVision = _supportsVision.asStateFlow()

    /** 图片上传进度文案（空表示无进行中上传）。 */
    private val _imageNote = MutableStateFlow("")
    val imageNote = _imageNote.asStateFlow()

    private var api: HermesApi? = null
    private var pingStarted = false

    /** 本机版本号：静默检查更新时用来比较（免去每次从界面传进来）。 */
    private val myVersionCode: Int = runCatching {
        val app = getApplication<Application>()
        val pi = app.packageManager.getPackageInfo(app.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            pi.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION") pi.versionCode
        }
    }.getOrDefault(1)

    init {
        // 通知栏直接回复：先取落盘的（App 被杀过），再收运行中的广播
        drainPendingReply()
        viewModelScope.launch {
            com.hermesapp.PendingReply.flow.collect { drainPendingReply() }
        }
    }

    /** 取出通知栏回复并作为用户消息发出（连接没建好或该会话正忙时留着，下次再取）。 */
    private fun drainPendingReply() {
        val raw = prefs.pendingReply
        if (raw.isEmpty()) return
        val sid = raw.substringBefore('\u0000')
        val text = raw.substringAfter('\u0000')
        if (text.isBlank()) { prefs.pendingReply = ""; return }
        if (api == null) return
        val target = if (sid.isNotEmpty()) sid else _currentId.value
        if (target.isEmpty()) return
        if (rt(target).busy.value) return
        prefs.pendingReply = ""
        if (target != _currentId.value) switchSession(target)
        send(text)
    }

    // ---------- 会话与本地持久化 ----------

    private val maxHistory = 300

    private fun stamp(): Long = System.currentTimeMillis()

    private fun setMsgs(r: SessionRuntime, list: List<Msg>) {
        r.messages.value = list
        scheduleSave(r)
    }

    private fun scheduleSave(r: SessionRuntime) {
        r.saveJob?.cancel()
        r.saveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            saveRuntime(r)
        }
    }

    private fun saveRuntime(r: SessionRuntime) {
        store.saveMessages(r.id, r.messages.value, maxHistory)
    }

    /** 保存当前会话的消息与索引（切换/新建前调用）。 */
    private fun saveCurrent() {
        val id = _currentId.value
        if (id.isNotEmpty()) runtimes[id]?.let { saveRuntime(it) }
        store.saveIndex(_sessions.value)
    }

    private fun ensureLoaded(id: String) {
        val r = rt(id)
        if (!r.loaded) {
            r.messages.value = store.loadMessages(id)
            r.loaded = true
        }
    }

    private fun refreshSessions() {
        _sessions.value = store.loadIndex().sortedByDescending { it.updatedAt }
    }

    /** 切到某个会话（网关 session_id 同步指过去）。不再停止任何正在跑的任务。 */
    fun switchSession(id: String) {
        if (id == _currentId.value) return
        saveCurrent()
        _currentId.value = id
        prefs.sessionId = id
        ensureLoaded(id)
        refreshSessions()
    }

    fun newConversation() {
        saveCurrent()
        val id = UUID.randomUUID().toString()
        val meta = SessionMeta(id, "新对话", stamp(), false)
        _sessions.value = _sessions.value + meta
        store.saveIndex(_sessions.value)
        _currentId.value = id
        prefs.sessionId = id
        rt(id).loaded = true
        refreshSessions()
    }

    fun archiveSession(id: String, archived: Boolean) {
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(archived = archived)
        _sessions.value = list
        store.saveIndex(list)
        if (id == _currentId.value && archived) selectNextOrEmpty()
    }

    fun deleteSession(id: String) {
        // 若该会话有正在跑的任务，先停掉（服务端一并停），再删本地记录。
        stopSession(id)
        store.deleteMessages(id)
        runtimes.remove(id)
        val list = _sessions.value.filter { it.id != id }.toMutableList()
        _sessions.value = list
        store.saveIndex(list)
        if (id == _currentId.value) selectNextOrEmpty()
    }

    /** 当前会话被删/归档后：优先切到下一个未归档会话；没有则清空进入空态（输入即新建）。 */
    private fun selectNextOrEmpty() {
        val next = _sessions.value.firstOrNull { !it.archived }
        if (next != null) {
            _currentId.value = next.id
            prefs.sessionId = next.id
            ensureLoaded(next.id)
        } else {
            _currentId.value = ""
            prefs.sessionId = null
        }
    }

    private fun touchSession(firstUserText: String?) {
        val id = _currentId.value
        if (id.isEmpty()) return
        val list = _sessions.value.toMutableList()
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        val old = list[i]
        val title = if (old.title == "新对话" && !firstUserText.isNullOrBlank()) {
            val t = firstUserText.replace(Regex("\\s+"), " ").trim()
            if (t.length > 20) t.take(20) + "…" else t
        } else old.title
        list[i] = old.copy(title = title, updatedAt = stamp())
        _sessions.value = list
    }

    private fun bootstrapSessions(profileSessionId: String?) {
        store = SessionStore(getApplication(), prefs.profile)
        if (store.loadIndex().isEmpty()) {
            val migrated = store.migrateLegacy(profileSessionId ?: "")
            if (migrated == null) {
                // 无历史会话：进入空态，首次输入再建会话
                _currentId.value = ""
                prefs.sessionId = null
                _sessions.value = emptyList()
                return
            }
            _currentId.value = migrated.id
            prefs.sessionId = migrated.id
            _sessions.value = listOf(migrated)
            ensureLoaded(migrated.id)
            return
        }
        val list = store.loadIndex().sortedByDescending { it.updatedAt }
        // 优先恢复上次停留的会话（prefs.sessionId）；找不到才回退到最近更新的未归档会话。
        val preferred = profileSessionId?.takeIf { it.isNotEmpty() }
            ?.let { pid -> list.firstOrNull { it.id == pid && !it.archived } }
        val target = preferred ?: list.firstOrNull { !it.archived } ?: list.first()
        _currentId.value = target.id
        prefs.sessionId = target.id
        _sessions.value = list
        ensureLoaded(target.id)
    }

    // ---------- 连接 ----------

    fun onProfileChanged(p: Prefs) {
        val key = if (p.profile == "default") Keys.DEFAULT_KEY else Keys.FRIEND_KEY
        val prefix = if (p.profile == "default") "" else "/p/friend"
        api = HermesApi(p.serverUrl, key, prefix)
        // 网关托管媒体：把带鉴权的取文件函数挂给 Markdown 附件卡片
        val a0 = api
        MediaFetch.handler = { token -> a0?.downloadMedia(token) }
        bootstrapSessions(p.sessionId)
        pingLoop()
        refreshStatus()
        refreshFromServer()
        fetchCapabilities()
        resumeActiveRun()
        drainPendingReply()
        checkUpdateSilently()
    }

    /**
     * 静默检查更新：只更新「设置」角标，不弹框（启动 / 切身份时调用）。
     * 用户主动点「检查更新」仍走 checkUpdate()，那时才弹确认框。
     */
    fun checkUpdateSilently() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate() ?: return@launch
                _updateBadge.value = info.versionCode > myVersionCode
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 重开 App 时恢复「正在跑的任务」：本地按会话存了 run_id，逐个查状态——
     * 还在跑的就恢复「停止」按钮并继续接流（多会话可同时恢复），已结束的清标记。
     */
    fun resumeActiveRun() {
        val a = api ?: return
        val map = prefs.activeRunsMap()
        if (map.isEmpty()) return
        for ((sid, rid) in map) {
            viewModelScope.launch(Dispatchers.IO) {
                val st = try {
                    a.getRun(rid).optString("status", "")
                } catch (_: Exception) {
                    ""
                }
                val running = st in setOf("started", "running", "waiting_for_approval", "queued")
                if (running) {
                    val r = rt(sid)
                    ensureLoaded(sid)
                    r.runId = rid
                    r.finished = false
                    r.lastSeq = -1
                    r.busy.value = true
                    updateRunService()
                    streamRun(a, sid)
                } else {
                    prefs.removeActiveRun(sid)
                    if (sid == _currentId.value) refreshFromServer()
                }
            }
        }
    }

    /**
     * 回到前台时的即时体检：对每个仍在跑、且超过 25 秒没收到任何事件（含心跳帧）的会话，
     * 判定为「流已被隧道假死卡住」，主动断开并走一次重连续接。
     *
     * 阈值 25 秒的由来：服务端每 10 秒必发一个 keepalive，25 秒 ≈ 连丢两拍，
     * 正常空闲绝不会误判；不这样做的话，息屏期间假死的流要等 30 秒读超时才断开。
     */
    fun onAppForeground() {
        val now = System.currentTimeMillis()
        for (r in runtimes.values) {
            if (!r.busy.value || r.finished) continue
            val last = r.lastEventAt
            if (last > 0 && now - last > 25_000L) {
                val c = r.call
                if (c != null && !c.isCanceled()) {
                    if (r.retryNote.value.isEmpty()) r.retryNote.value = "回到前台，正在重连…"
                    // 主动掐掉假死连接：onError 回调会走一次退避续接，这里不重复调，
                    // 避免同一次断流把重试计数加两回。
                    c.cancel()
                } else {
                    maybeContinue(r.id)
                }
            }
        }
        // 顺带刷一次在线状态，别让角标停在离线
        viewModelScope.launch(Dispatchers.IO) { refreshStatus() }
    }

    /** 拉一次服务端能力：是否支持原生图片（决定图片是原生附图还是先转文字）。 */
    fun fetchCapabilities() {
        val a = api ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val caps = a.capabilities()
                val f = caps.optJSONObject("features")
                if (f != null) _supportsVision.value = f.optBoolean("supports_vision", true)
            } catch (_: Exception) {
            }
        }
    }

    /** 重开 App 时从服务端拉当前会话消息：后台跑完的任务产出据此补回。 */
    fun refreshFromServer() = refreshFromServerFor(_currentId.value)

    /** 指定会话的服务端消息拉取与合并（翻历史兜底也复用）。 */
    private fun refreshFromServerFor(id: String) {
        val a = api ?: return
        if (id.isEmpty()) return
        val r = rt(id)
        if (r.busy.value) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.sessionMessages(id)
                val arr = resp.optJSONArray("data") ?: return@launch
                val list = mutableListOf<Msg>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role", "assistant")
                    if (role != "user" && role != "assistant") continue
                    val content = o.optString("content", "")
                    if (content.isEmpty()) continue
                    list.add(Msg(role, content, pending = false, ts = parseTs(o.optString("timestamp", ""))))
                }
                // 守卫：该会话此刻已在跑任务则不覆盖（切换会话不影响——只写它自己的缓冲）。
                if (r.busy.value) return@launch
                if (list.isEmpty()) return@launch
                // 合并而不是覆盖：本地消息正文里带内联图片（data URL），而服务端存的是
                // 原始 MEDIA: 路径——直接覆盖会把图片弄丢（用户报「更新后图片不见了」）。
                // 规则：本地该条已有内容就保留本地（更完整、含图）；本地是空占位而服务端
                // 有内容才用服务端；本地没有的尾部（后台任务产出）按服务端补上。
                val local = r.messages.value
                val merged = mutableListOf<Msg>()
                val n = maxOf(local.size, list.size)
                for (i in 0 until n) {
                    val l = local.getOrNull(i)
                    val s = list.getOrNull(i)
                    when {
                        l == null -> if (s != null) merged.add(s)
                        s == null -> merged.add(l)
                        l.pending && l.text.isEmpty() && l.trace.isEmpty() && s.text.isNotEmpty() ->
                            merged.add(l.copy(text = s.text, pending = false))
                        else -> merged.add(l)
                    }
                }
                r.messages.value = merged
                r.loaded = true
                store.saveMessages(id, merged, maxHistory)
            } catch (_: Exception) {
                // 服务端无此会话或网络异常：保留本地内容
            }
        }
    }

    private fun parseTs(s: String): Long = runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun pingLoop() {
        if (pingStarted) return
        pingStarted = true
        viewModelScope.launch(Dispatchers.IO) {
            var fails = 0
            var tick = 0
            while (true) {
                val ok = api?.ping() ?: false
                if (ok) {
                    fails = 0
                    _online.value = true   // 恢复立刻生效
                } else {
                    // 连续 2 次失败才翻「离线」，避免单次抖动闪红。
                    fails++
                    if (fails >= 2) _online.value = false
                }
                // 每 30 秒静默查一次更新：发新版后角标自动亮起，不必等下次启动。
                tick++
                if (tick % 6 == 0) checkUpdateSilently()
                delay(5000)
            }
        }
    }

    fun refreshStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val h = api?.sysinfo() ?: return@launch
                _statusSections.value = buildStatus(h)
                _statusErr.value = ""
            } catch (e: Exception) {
                _statusErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    /** 把 /health/sysinfo 的扁平字段整理成「分组 → 行」结构，供状态页排版渲染。 */
    private fun buildStatus(h: JSONObject): List<StatusSection> {
        val out = mutableListOf<StatusSection>()

        fun sec(title: String, items: List<StatusItem>) {
            val keep = items.filter { it.value.isNotEmpty() }
            if (keep.isNotEmpty()) out.add(StatusSection(title, keep))
        }
        fun it(label: String, v: String?): StatusItem =
            StatusItem(label, v.orEmpty())

        val gw = mutableListOf<StatusItem>()
        gw.add(it("运行状态", if (h.optString("status") == "ok") "正常" else h.optString("status", "?")))
        val pid = h.optInt("pid", 0)
        if (pid > 0) gw.add(it("进程 PID", pid.toString()))
        gw.add(it("操作系统", h.optString("platform", "")))
        gw.add(it("Python", h.optString("python", "")))
        sec("网关", gw)

        val cpu = mutableListOf<StatusItem>()
        cpu.add(it("型号", h.optString("cpu_model", "")))
        val cpuPct = h.optDouble("cpu_percent", -1.0)
        if (cpuPct >= 0) cpu.add(it("使用率", String.format("%.1f%%", cpuPct)))
        val cores = h.optInt("cpu_count", 0)
        if (cores > 0) cpu.add(it("核心数", cores.toString() + " 核"))
        val freq = h.optInt("cpu_freq_mhz", 0)
        if (freq > 0) cpu.add(it("主频", freq.toString() + " MHz"))
        sec("CPU", cpu)

        val mem = mutableListOf<StatusItem>()
        val memPct = h.optDouble("memory_percent", -1.0)
        if (memPct >= 0) mem.add(it("使用率", String.format("%.1f%%", memPct)))
        val mUsed = h.optInt("memory_used_mb", 0)
        val mTotal = h.optInt("memory_total_mb", 0)
        if (mTotal > 0) mem.add(it("已用/总量", mUsed.toString() + " MB / " + mTotal.toString() + " MB"))
        val pmem = h.optInt("proc_memory_mb", 0)
        if (pmem > 0) mem.add(it("网关进程", pmem.toString() + " MB"))
        sec("内存", mem)

        val disk = mutableListOf<StatusItem>()
        val dTotal = h.optDouble("disk_total_gb", -1.0)
        if (dTotal >= 0) disk.add(it("总大小", String.format("%.1f GB", dTotal)))
        val dUsed = h.optDouble("disk_used_gb", -1.0)
        if (dUsed >= 0) disk.add(it("已用", String.format("%.1f GB", dUsed)))
        val dFree = h.optDouble("disk_free_gb", -1.0)
        if (dFree >= 0) disk.add(it("可用", String.format("%.1f GB", dFree)))
        val dPct = h.optDouble("disk_percent", -1.0)
        if (dPct >= 0) disk.add(it("使用率", String.format("%.1f%%", dPct)))
        sec("磁盘", disk)

        val run = mutableListOf<StatusItem>()
        val la = h.optJSONArray("load_avg")
        if (la != null && la.length() >= 3) {
            run.add(it("负载 1/5/15", String.format("%.2f / %.2f / %.2f",
                la.optDouble(0, 0.0), la.optDouble(1, 0.0), la.optDouble(2, 0.0))))
        }
        val up = h.optLong("uptime_seconds", -1)
        if (up >= 0) run.add(it("已运行", (up / 86400).toString() + " 天 " + ((up % 86400) / 3600).toString() + " 小时 " + ((up % 3600) / 60).toString() + " 分"))
        sec("运行", run)

        val apiSec = mutableListOf<StatusItem>()
        apiSec.add(it("当前模型", h.optString("model", "").ifEmpty { "未知" }))
        val mt = h.optJSONObject("metrics_today")
        if (mt != null) {
            apiSec.add(it("今日请求", mt.optInt("requests", 0).toString()))
            apiSec.add(it("今日消息", mt.optInt("messages", 0).toString()))
        }
        apiSec.add(it("活跃任务", h.optInt("active_runs", 0).toString()))
        apiSec.add(it("子任务", h.optInt("active_delegations", 0).toString()))
        apiSec.add(it("队列深度", h.optInt("process_queue_depth", 0).toString()))
        val hb = h.optString("last_heartbeat", "")
        if (hb.isNotEmpty()) apiSec.add(it("最后心跳", TimeFmt.isoToBj(hb) + "（北京）"))
        sec("API 与任务", apiSec)

        return out
    }

    // ---------- 发送与流式接收 ----------

    fun send(text: String) {
        val a = api ?: return
        val imgs = _pendingImages.value
        if (text.isBlank() && imgs.isEmpty()) return
        // 空态（无选中会话）：直接输入即新建对话
        if (_currentId.value.isEmpty()) {
            val id = UUID.randomUUID().toString()
            _currentId.value = id
            prefs.sessionId = id
            _sessions.value = _sessions.value + SessionMeta(id, "新对话", stamp(), false)
            store.saveIndex(_sessions.value)
            rt(id).loaded = true
        }
        val sid = _currentId.value
        val r = rt(sid)
        if (r.busy.value) return   // 同一会话正在跑才拦；其它会话照发
        ensureLoaded(sid)
        val wasEmpty = r.messages.value.none { it.role == "user" }
        // 记下发送前的用户消息条数（翻历史时的位置锚点）与本轮正文（内容校验）。
        r.priorUserCount = r.messages.value.count { it.role == "user" }
        r.pendingSendText = text.trim()
        r.recoveryJob?.cancel()
        setMsgs(
            r,
            r.messages.value + Msg(
                "user", text, ts = stamp(),
                images = imgs.filter { it.isImage }.map { it.uri },
                files = imgs.filter { !it.isImage }.map { it.file.name },
            )
        )
        touchSession(if (wasEmpty) text else null)
        _pendingImages.value = emptyList()
        startRunWith(a, sid, text, imgs.map { it.file })
    }

    /** 把选中的图片拷进 App 沙盒，加入待发列表。 */
    fun addImage(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 20L * 1024 * 1024) {
                    _imageNote.value = "图片超过 20MB：" + name
                    return@launch
                }
                val dir = File(app.filesDir, "outbox").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "jpg").lowercase().let {
                    if (it.length in 1..5) it else "jpg"
                }
                val dst = File(dir, "img_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    _imageNote.value = "读取图片失败"
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    dst.delete()
                    return@launch
                }
                _imageNote.value = ""
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = true)
            } catch (e: Exception) {
                _imageNote.value = "读取图片失败：" + (e.message ?: "?")
            }
        }
    }

    /** 把任意文件拷进 App 沙盒，加入待发列表（非图片走文件卡片展示）。 */
    fun addFile(ctx: Context, uri: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, size) = queryNameSize(app, uri)
                if (size > 50L * 1024 * 1024) {
                    _imageNote.value = "文件超过 50MB：" + name
                    return@launch
                }
                val dir = File(app.filesDir, "outbox").apply { mkdirs() }
                val ext = name.substringAfterLast('.', "").lowercase().let {
                    if (it.length in 1..5) it else "bin"
                }
                val dst = File(dir, "file_${System.currentTimeMillis()}_${(0..9999).random()}.$ext")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                } ?: run {
                    _imageNote.value = "读取文件失败"
                    return@launch
                }
                if (_pendingImages.value.size >= 10) {
                    _imageNote.value = "最多 10 个附件"
                    dst.delete()
                    return@launch
                }
                val isImg = ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
                _imageNote.value = ""
                _pendingImages.value = _pendingImages.value + PendingImage(Msg.nextMsgId(), uri.toString(), dst, isImage = isImg)
            } catch (e: Exception) {
                _imageNote.value = "读取文件失败：" + (e.message ?: "?")
            }
        }
    }

    fun removeImage(id: Long) {
        val list = _pendingImages.value
        list.firstOrNull { it.id == id }?.file?.delete()
        _pendingImages.value = list.filter { it.id != id }
    }

    private fun queryNameSize(ctx: Context, uri: Uri): Pair<String, Long> {
        var name = "image.jpg"
        var size = 0L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return name to size
    }

    // 按真实扩展名给 MIME：图片走 image 类，其余按常见类型给，未知一律 octet-stream。
    private fun mimeOf(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "html", "htm" -> "text/html"
        "txt", "log" -> "text/plain"
        "md" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        "xml" -> "application/xml"
        "pdf" -> "application/pdf"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }

    private fun startRunWith(a: HermesApi, sid: String, text: String, files: List<File> = emptyList()) {
        val r = rt(sid)
        setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp()))
        r.busy.value = true
        r.finished = false
        r.lastSeq = -1
        r.autoContinue = 0
        r.startedAt = System.currentTimeMillis()
        updateRunService()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ids = mutableListOf<String>()
                for ((i, f) in files.withIndex()) {
                    _imageNote.value = "上传附件 ${i + 1}/${files.size}…"
                    ids.add(a.uploadImage(f.readBytes(), f.name, mimeOf(f)))
                }
                _imageNote.value = ""
                val run = a.startRun(text, sid, ids)
                r.runId = run.optString("run_id", run.optString("id", ""))
                r.startedAt = System.currentTimeMillis()
                prefs.putActiveRun(sid, r.runId)
                streamRun(a, sid)
            } catch (e: Exception) {
                _imageNote.value = ""
                appendDelta(r, "\n[请求失败] " + (e.message ?: "?"))
                failPending(sid)
            }
        }
    }

    /** 澄清请求：挂到该会话当前助手气泡上，等用户选选项回执。 */
    private fun attachClarify(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        val cid = ev.data.optString("clarify_id", "")
        val q = ev.data.optString("question", "")
        val chs = mutableListOf<String>()
        ev.data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        if (cid.isEmpty() || q.isEmpty()) return
        val card = ClarifyCard(cid, q, chs, ev.data.optBoolean("multi_select", false))
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(clarify = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), clarify = card))
        setMsgs(r, list)
    }

    /** 用户点了澄清选项：回执给服务端，并把卡片置为已选。 */
    fun respondClarify(msgId: Long, choice: String) {
        val a = api ?: return
        val r = rt(_currentId.value)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].clarify ?: return
        if (card.resolved.isNotEmpty()) return
        list[i] = list[i].copy(clarify = card.copy(resolved = choice))
        setMsgs(r, list)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { a.respondClarify(rid, card.clarifyId, choice) }
        }
    }

    /** 审批请求：挂到该会话当前助手气泡上，等用户点按钮回执。 */
    private fun attachApproval(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        val rid = ev.data.optString("request_id", "")
        val cmd = ev.data.optString("command", "")
        val desc = ev.data.optString("description", "")
        val chs = mutableListOf<String>()
        ev.data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        if (rid.isEmpty() || chs.isEmpty()) return
        val card = ApprovalCard(rid, cmd, desc, chs)
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(approval = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), approval = card))
        setMsgs(r, list)
    }

    /** 用户点了审批按钮：回执给服务端，并把卡片置为已选。 */
    fun respondApproval(msgId: Long, choice: String) {
        val a = api ?: return
        val r = rt(_currentId.value)
        val rid = r.runId
        if (rid.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val card = list[i].approval ?: return
        if (card.resolved.isNotEmpty()) return
        list[i] = list[i].copy(approval = card.copy(resolved = choice))
        setMsgs(r, list)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { a.respondApproval(rid, card.requestId, choice) }
        }
    }

    /** 轮末 token 用量：挂到该会话最后一条助手消息上。 */
    private fun attachUsage(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        val u = ev.data.optJSONObject("usage") ?: return
        val usage = Usage(
            input = u.optInt("input_tokens", 0),
            output = u.optInt("output_tokens", 0),
            total = u.optInt("total_tokens", 0),
            cacheRead = u.optInt("cache_read_tokens", 0),
            cacheWrite = u.optInt("cache_write_tokens", 0),
            durationMs = if (r.startedAt > 0) System.currentTimeMillis() - r.startedAt else 0L,
        )
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(usage = usage)
        setMsgs(r, list)
    }

    /** 子任务开始/结束：按 subagent_id 或 goal 归并成一行进度。 */
    private fun upsertSubagent(r: SessionRuntime, ev: com.hermesapp.net.SseEvent, running: Boolean) {
        val id = ev.data.optString("subagent_id", "").ifEmpty { ev.data.optString("delegation_id", "") }
        val goal = ev.data.optString("goal", "")
        val summary = ev.data.optString("summary", "")
        val statusRaw = ev.data.optString("status", "")
        val key = id.ifEmpty { goal }
        if (key.isEmpty()) return
        val status = when {
            running -> "running"
            statusRaw.isNotEmpty() -> statusRaw
            else -> "completed"
        }
        val list = r.messages.value.toMutableList()
        val mi = list.indexOfLast { it.role == "assistant" && it.pending }
        if (mi < 0) return
        val old = list[mi].subagents
        val idx = old.indexOfFirst { it.id == key }
        val line = SubagentLine(key, goal.ifEmpty { old.getOrNull(idx)?.goal ?: "" }, status, summary)
        val next = if (idx >= 0) old.toMutableList().also { it[idx] = line } else old + line
        list[mi] = list[mi].copy(subagents = next)
        setMsgs(r, list)
    }

    private fun toolLine(ev: com.hermesapp.net.SseEvent, failed: Boolean): String {
        val name = ev.data.optString("tool", "")
        if (name.isEmpty() || name.startsWith("_")) return ""
        val sb = StringBuilder("\n· ")
        sb.append(if (failed) "✗ " else "✓ ").append(name)
        val dur = ev.data.optDouble("duration", -1.0)
        if (dur >= 0) sb.append("  ").append(String.format("%.1f", dur)).append("s")
        val pv = ev.data.optString("preview", "").replace(Regex("\\s+"), " ").trim()
        if (pv.isNotEmpty()) {
            sb.append("\n   ").append(if (pv.length > 120) pv.take(120) + "…" else pv)
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun streamRun(a: HermesApi, sid: String) {
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        r.lastEventAt = System.currentTimeMillis()   // 重新起流即重置活跃时间，避免刚连上就被判假死
        // 每轮流一个攒帧器：碎字按帧放送。重起流时丢弃上一轮的残余。
        r.coalescer?.discard()
        r.coalescer = StreamDeltaCoalescer(viewModelScope, onFlush = { s -> appendDelta(r, s) })
        r.call = a.streamEvents(
            runId = rid,
            lastSeq = r.lastSeq,
            onEvent = { ev ->
                if (ev.id != null) r.lastSeq = ev.id
                r.lastEventAt = System.currentTimeMillis()
                val name = ev.event ?: ev.data.optString("event", "")
                // 收到任何真实事件即视为连接已恢复正常：清掉重试提示与计数。
                if (name != "replay.truncated" && r.autoContinue != 0) {
                    r.autoContinue = 0
                    r.retryNote.value = ""
                }
                // 流已恢复：翻历史的盯梢任务作废。
                if (name != "replay.truncated") r.recoveryJob?.cancel()
                when (name) {
                    // 服务端明确告知：断线期间的事件已超出保留窗口、拿不回来了。
                    // 不能静默——在气泡里标一行，并拉一次服务端消息兜底。
                    "replay.truncated" -> {
                        appendDelta(r, TRUNCATED_NOTICE)
                        val s = sid
                        viewModelScope.launch(Dispatchers.IO) {
                            delay(600)
                            if (s == _currentId.value) refreshFromServer()
                        }
                    }
                    // 入攒帧缓冲，由 StreamDeltaCoalescer 按帧放送（避免一大块一大块地跳）。
                    "message.delta" -> r.coalescer?.append(ev.data.optString("delta", ""))
                    "message.interim" -> {}
                    "tool.started" -> {}
                    "tool.completed" -> {
                        r.coalescer?.flushNow()
                        val line = toolLine(ev, ev.data.optBoolean("error", false))
                        if (line.isNotEmpty()) appendTrace(r, line)
                    }
                    "tool.failed" -> {
                        r.coalescer?.flushNow()
                        val line = toolLine(ev, true)
                        if (line.isNotEmpty()) appendTrace(r, line)
                    }
                    "approval.request" -> attachApproval(r, ev)
                    "clarify.request" -> attachClarify(r, ev)
                    "subagent.start" -> upsertSubagent(r, ev, running = true)
                    "subagent.complete" -> upsertSubagent(r, ev, running = false)
                    "run.completed" -> {
                        r.coalescer?.flushNow()
                        r.finished = true
                        val out = ev.data.optString("output", "")
                        if (out.isNotEmpty()) setPendingText(r, out) else finishPending(r)
                        attachUsage(r, ev)
                        doneOk(sid)
                        notifyIfBackground(sid, out)
                    }
                    "run.failed" -> {
                        r.coalescer?.flushNow()
                        r.finished = true
                        appendDelta(r, "\n[失败] " + ev.data.optString("error", "未知错误"))
                        finishPending(r)
                        maybeContinue(sid)
                    }
                    "run.cancelled", "run.interrupted" -> {
                        r.coalescer?.flushNow()
                        r.finished = true
                        appendDelta(r, "\n[已中断]")
                        doneOk(sid)
                    }
                }
            },
            onClosed = {
                r.coalescer?.flushNow()
                if (r.busy.value && !r.finished) maybeContinue(sid)
            },
            onError = { e ->
                // 不再往正文塞「[连接断开]」——断流期间的提示统一走 retryNote（气泡上方一行），
                // 正文只保留任务真实产出，避免一次抖动就在会话里留一条错行。
                r.coalescer?.flushNow()
                if (r.busy.value && !r.finished) {
                    if (r.retryNote.value.isEmpty()) r.retryNote.value = "连接中断：" + (e.message ?: "未知")
                    maybeContinue(sid)
                }
            }
        )
    }

    /**
     * SSE 流提前断开（没收到 run.completed）时的续接：
     * 绝不伪造用户消息——只查同一 run 的状态，仍在跑就续接它的事件流；
     * 已结束就从服务端拉回结果。仅作用于该 run 所属的会话。
     *
     * 重试用退避而非固定间隔：网络抖动（地铁、切基站、隧道重连）往往几秒内恢复，
     * 原来的「固定 1.2 秒 × 3 次」会在 4 秒内烧完次数然后彻底放弃；现在
     * 1→2→4→8→16→30 秒封顶、最多 8 次，覆盖约 1 分钟的窗口，且首次仍只等 1 秒。
     */
    private fun maybeContinue(sid: String) {
        val r = rt(sid)
        if (r.finished) return
        val a = api ?: return
        val rid = r.runId
        if (rid.isEmpty()) { failPending(sid); return }
        if (r.autoContinue >= MAX_RECONNECT_ATTEMPTS) {
            // 重连退避用尽：不判死，改去翻服务端会话记录等答案落盘。
            startHistoryRecovery(sid, rid)
            return
        }
        val attempt = r.autoContinue + 1
        r.autoContinue = attempt
        val waitMs = backoffDelayMs(attempt)
        r.retryNote.value = "连接中断，${waitMs / 1000} 秒后重试（$attempt/$MAX_RECONNECT_ATTEMPTS）"
        viewModelScope.launch(Dispatchers.IO) {
            delay(waitMs)
            if (!r.busy.value || r.finished) return@launch
            val st = try { a.getRun(rid).optString("status", "") } catch (_: Exception) { "" }
            if (st in setOf("started", "running", "waiting_for_approval", "queued", "stopping")) {
                r.retryNote.value = ""
                streamRun(a, sid)   // 续接同一 run，不新增任何用户消息
            } else {
                r.retryNote.value = ""
                r.finished = true
                finishPending(r)
                r.busy.value = false
                prefs.removeActiveRun(sid)
                updateRunService()
                if (sid == _currentId.value) refreshFromServer()  // 任务已结束：拉回服务端产出
            }
        }
    }

    /** 退避等待：第 n 次重试 2^(n-1) 秒（首次 1 秒），封顶 30 秒。 */
    private fun backoffDelayMs(attempt: Int): Long {
        val base = 1000L shl (attempt - 1).coerceIn(0, 5)   // 1,2,4,8,16,32…
        return base.coerceAtMost(30_000L)
    }

    /**
     * 断流翻历史兜底。
     *
     * 重连退避用尽后不把回合判死：手机 SSE 常被系统掐死，而服务端其实还在跑，
     * 跑完会把答案写进会话记录。这里改为轮询服务端历史，等答案落盘后再合并回来。
     *
     * 认哪一条是本次的答案，靠「位置」不靠「文字」：发送前记下的用户消息条数 N，
     * 历史里第 N+1 条用户消息就是本次发送（再用正文做二次校验，防历史被编辑/分叉）。
     * 它之后最后一条非空助手消息即答案，且必须连续两次读到一致才算定下来
     * （签名里带记录总条数，服务端还在追加工具记录时签名会变，就继续等）。
     * 认不出锚点就放弃——宁可报错，也不认错答案。
     */
    private fun startHistoryRecovery(sid: String, rid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.finished) return
        if (r.recoveryJob?.isActive == true) return
        val pending = r.pendingSendText
        val prior = r.priorUserCount
        if (pending.isEmpty()) {
            r.retryNote.value = "连接中断，已重试 $MAX_RECONNECT_ATTEMPTS 次仍未完成"
            failPending(sid)
            return
        }
        r.retryNote.value = "连接中断，正在从服务端取回结果…"
        r.recoveryJob = viewModelScope.launch(Dispatchers.IO) {
            var delayMs = 5_000L
            var elapsed = 0L
            var lastSig: String? = null
            var unanchored = 0
            var failStreak = 0
            while (elapsed < HISTORY_RECOVERY_WINDOW_MS) {
                delay(delayMs)
                elapsed += delayMs
                delayMs = (delayMs * 2).coerceAtMost(30_000L)
                if (!r.busy.value || r.finished) return@launch
                val rows = try {
                    historyRows(a, sid)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    failStreak++
                    if (failStreak >= 3) {
                        giveUpRecovery(sid, "服务端会话记录读取失败，可稍后再看")
                        return@launch
                    }
                    continue
                }
                failStreak = 0
                if (rows.isEmpty()) continue
                val anchor = resolveAnchor(rows, pending, prior)
                if (anchor < 0) {
                    lastSig = null
                    if (++unanchored >= 2) {
                        giveUpRecovery(sid, "本次发送未落到服务端，可重发")
                        return@launch
                    }
                    continue
                }
                unanchored = 0
                val sig = answerSignature(rows, anchor)
                if (sig != null && sig == lastSig) {
                    adoptRecovered(sid)
                    return@launch
                }
                lastSig = sig
            }
            giveUpRecovery(sid, "等 30 分钟仍未取回结果，可稍后再看")
        }
    }

    /** 服务端会话记录解析成位置锚点所需的最小行。 */
    private fun historyRows(a: HermesApi, sid: String): List<HistRow> {
        val resp = a.sessionMessages(sid)
        val arr = resp.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<HistRow>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "")
            if (role != "user" && role != "assistant") continue
            out.add(HistRow(role, o.optString("content", ""), o.optString("id", i.toString())))
        }
        return out
    }

    /** 位置锚点：第 prior+1 条用户消息的下标；对不上返回 -1（不硬认）。 */
    private fun resolveAnchor(rows: List<HistRow>, pendingText: String, prior: Int): Int {
        val userIdx = rows.indices.filter { rows[it].role == "user" }
        if (userIdx.size <= prior) return -1
        val pos = userIdx[prior]
        if (rows[pos].text.trim() != pendingText) return -1
        return pos
    }

    /** 稳定性签名：锚点之后最后一条非空助手消息；带总条数，服务端还在追加时会变。 */
    private fun answerSignature(rows: List<HistRow>, anchor: Int): String? {
        val ans = rows.drop(anchor + 1)
            .lastOrNull { it.role == "assistant" && it.text.isNotBlank() } ?: return null
        return "${rows.size}|${ans.id}|${ans.text.length}"
    }

    /** 答案已落盘：结束本轮并按服务端记录合并回来。 */
    private fun adoptRecovered(sid: String) {
        val r = rt(sid)
        r.finished = true
        r.retryNote.value = ""
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        refreshFromServerFor(sid)
    }

    /** 翻历史也没捞到：明确收尾，不留一个永远转圈的空气泡。 */
    private fun giveUpRecovery(sid: String, why: String) {
        rt(sid).retryNote.value = why
        failPending(sid)
    }

    /** 停止「当前会话」正在跑的任务。 */
    fun stop() = stopSession(_currentId.value)

    private fun stopSession(sid: String) {
        if (sid.isEmpty()) return
        val r = rt(sid)
        if (!r.busy.value && r.runId.isEmpty()) return
        val a = api
        val rid = r.runId
        r.finished = true
        r.recoveryJob?.cancel()
        r.coalescer?.discard()
        if (a != null && rid.isNotEmpty()) viewModelScope.launch(Dispatchers.IO) { a.stopRun(rid) }
        r.call?.cancel()
        appendDelta(r, "\n[已请求停止]")
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
    }

    /** 工具轨迹追加到该会话当前 assistant 消息的 trace（界面默认折叠，不进正文）。 */
    private fun appendTrace(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(trace = list[i].trace + d)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), trace = d))
        setMsgs(r, list)
    }

    private fun appendDelta(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = list[i].text + d)
        else list.add(Msg("assistant", d, pending = true, ts = stamp()))
        setMsgs(r, list)
    }

    private fun setPendingText(r: SessionRuntime, t: String) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = t, pending = false)
        setMsgs(r, list)
    }

    private fun finishPending(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(pending = false)
        setMsgs(r, list)
    }

    private fun failPending(sid: String) {
        val r = rt(sid)
        r.recoveryJob?.cancel()
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
    }

    private fun doneOk(sid: String) {
        val r = rt(sid)
        r.autoContinue = 0
        r.recoveryJob?.cancel()
        r.retryNote.value = ""
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        if (sid == _currentId.value) drainPendingReply()
    }

    /** 只要有任意会话在跑任务就保持前台服务；全部结束才停（通知随之消失）。 */
    private fun updateRunService() {
        val running = runtimes.filterValues { it.busy.value }.keys.toSet()
        _runningIds.value = running
        if (running.isNotEmpty()) {
            if (prefs.keepAlive) RunService.start(getApplication())
        } else {
            RunService.stop(getApplication())
        }
    }

    /** App 不在前台时，任务完成弹系统通知（提示音+震动）。前台则静默，界面自己会更新。 */
    private fun notifyIfBackground(sid: String, output: String) {
        if (AppForeground.isForeground || !prefs.keepAlive) return
        val app = getApplication<Application>()
        val body = output.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "任务已完成" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyMessage(app, "Hermes 回复", body, sid)
    }

    /** 缓存占用文案（待发图片 + 安装包 + 图片缓存），供设置页显示。 */
    private val _cacheText = MutableStateFlow("")
    val cacheText = _cacheText.asStateFlow()

    fun refreshCache() {
        val app = getApplication<Application>()
        _cacheText.value = fmtSize(CacheUtil.total(app))
    }

    /** 清理临时缓存；会话记录与草稿不动。 */
    fun clearCache() {
        val app = getApplication<Application>()
        CacheUtil.clear(app)
        refreshCache()
    }

    /** 设置页切换「后台运行」时调用：关掉立即停掉前台服务，常驻通知随之消失。 */
    fun setKeepAlive(on: Boolean) {
        prefs.keepAlive = on
        if (!on) RunService.stop(getApplication()) else updateRunService()
    }

    // ---------- 自更新 ----------

    /** 只检查，不下载。有新版本时挂到 pendingUpdate，由界面弹确认框。 */
    fun checkUpdate(currentVersionCode: Int, ctx: Context) {
        val a = api ?: return
        _updateNote.value = "检查中…"
        _pendingUpdate.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = a.checkUpdate()
                if (info == null) {
                    _updateNote.value = "检查失败：无法获取版本信息"
                    return@launch
                }
                if (info.versionCode <= currentVersionCode) {
                    _updateNote.value = "已是最新版本 " + info.versionName
                    _updateBadge.value = false
                    return@launch
                }
                _updateNote.value = ""
                _updateBadge.value = true
                _pendingUpdate.value = info
            } catch (e: Exception) {
                _updateNote.value = "检查失败：" + (e.message ?: "?")
            }
        }
    }

    fun dismissUpdate() {
        _pendingUpdate.value = null
    }

    /** 用户点「下载并安装」后调用：带进度下载，完成后拉起安装。 */
    fun confirmUpdate(ctx: Context) {
        val a = api ?: return
        val info = _pendingUpdate.value ?: return
        _pendingUpdate.value = null
        _updateNote.value = "下载中…"
        _downloadPct.value = 0
        _downloadText.value = "0%"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = a.downloadApk(info.url, ctx) { done, total ->
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        _downloadPct.value = pct
                        _downloadText.value = pct.toString() + "%  " + fmtSize(done) + "/" + fmtSize(total)
                    } else {
                        _downloadText.value = fmtSize(done)
                    }
                }
                if (f == null) {
                    _updateNote.value = "下载失败"
                    _downloadPct.value = -1
                    return@launch
                }
                _downloadPct.value = 100
                _downloadText.value = "100%"
                _updateNote.value = "下载完成，请在弹出的提示中安装"
                launch(Dispatchers.Main) { installApk(ctx, f) }
            } catch (e: Exception) {
                _updateNote.value = "下载失败：" + (e.message ?: "?")
                _downloadPct.value = -1
            }
        }
    }

    private fun fmtSize(b: Long): String = when {
        b >= 1024L * 1024 -> String.format("%.1f MB", b / 1024.0 / 1024.0)
        b >= 1024L -> String.format("%.0f KB", b / 1024.0)
        else -> b.toString() + " B"
    }

    private fun installApk(ctx: Context, f: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            _updateNote.value = "安装失败：" + (e.message ?: "?")
        }
    }
}
