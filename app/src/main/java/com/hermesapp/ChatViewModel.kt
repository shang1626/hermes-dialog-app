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
import kotlinx.coroutines.withContext
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
    /** 本气泡开始等回复的墙钟时间：进行中时界面实时显示耗时。0 表示不显示。 */
    val startedAt: Long = 0L,
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
    /** 投递状态：只有用户消息有；老消息/服务端拉回的消息为 null。 */
    val receipt: Receipt? = null,
    /** 引用回复：被引用的上一条消息正文片段（空表示不是引用发送）。 */
    val quote: String = "",
    /**
     * 本条的幂等键：发送时生成一次，重发时复用。服务端凭它保证同一句话只执行一次，
     * 重发不再有「可能发两遍」的风险，还能拿回原来那轮的 run_id 直接接上。
     */
    val idemKey: String = "",
    /** 插话气泡：用户在本轮运行中追加的一句，界面按用户气泡显示并标「插话」。 */
    val steer: Boolean = false,
) {
    companion object {
        private val counter = java.util.concurrent.atomic.AtomicLong(0)
        fun nextMsgId(): Long = counter.incrementAndGet()
    }
}

/**
 * 一条用户消息的投递状态——「服务端到底收下这条没有」。
 *
 *   sending    POST 已发出，回执还没回来（气泡角标转灰点）
 *   accepted   服务端已收下并给了 run_id（角标打勾），本轮才能正常订阅流
 *   uncertain  POST 中途断了，收没收不知道（黄问号）——要用户点一下确认或重发
 *   failed     服务端明确拒绝（HTTP 4xx/5xx，有回执），或确认后发现记录里没有（红叹号）
 *
 * rawText / priorUserCount 是给「确认送达」「重新发送」复用的：
 * 拿原文与本条之前的用户消息条数去服务端记录里认领这条消息。
 */
data class Receipt(
    val status: String,
    val runId: String = "",
    val note: String = "",
    val rawText: String = "",
    val priorUserCount: Int = -1,
    /**
     * 本条发送时用的幂等键，重发必须原样复用：服务端凭它认出「这是同一条」，
     * 只执行一次并把原来那轮的 run_id 还回来。
     */
    val idemKey: String = "",
    /**
     * 首次发送时上传附件拿到的 artifact id。重发必须复用这一份：服务端算指纹时
     * 把请求体一起算进去，附件 id 变了指纹就变了，会被判成「键相同、内容不同」而 409。
     */
    val artifactIds: List<String> = emptyList(),
) {
    companion object {
        const val SENDING = "sending"
        const val ACCEPTED = "accepted"
        const val UNCERTAIN = "uncertain"
        const val FAILED = "failed"
        /** 排队中：本会话还在跑上一轮，这条等它结束自动发（不是发送中，别转圈）。 */
        const val QUEUED = "queued"
        /** 用户已确认忽略「不确定」：收掉角标与提示，不再反复提醒，也不自动重发。 */
        const val ACKED = "acked"
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

/**
 * 排队待发的一条消息（本会话正在跑任务时用户又发的那条）。
 * 用户消息此刻已经进了气泡，只是 POST 还没发出去；等本轮结束自动发。
 * text 是发往服务端的完整正文（含引用片段），files 是已拷进沙盒的附件。
 */
data class QueuedSend(
    val text: String,
    val files: List<java.io.File>,
    /** 对应的用户消息 id：发送时按它更新投递回执。 */
    val msgId: Long,
    /** 入队时该会话已有的用户消息条数：翻历史认锚点要用。 */
    val priorUserCount: Int,
    /** 入队时生成的幂等键：真正发出去时用它，重发也复用，保证只执行一次。 */
    val idemKey: String = "",
)

/** 状态页的一行：标签 + 值。value 为空则该行不显示。 */
data class StatusItem(val label: String, val value: String)

/** 状态页的一个分组：标题 + 若干行。 */
data class StatusSection(val title: String, val items: List<StatusItem>)

/** 定时任务页的一条（来自服务端 /api/jobs）。中文名/说明由本地映射表翻译。 */
data class JobItem(
    val id: String,
    val name: String,
    /** 中文名；不认识的返回空，界面回落显示原始名。 */
    val zhName: String,
    /** 这个任务是干什么的中文说明；不认识的为空。 */
    val note: String,
    val schedule: String,
    val enabled: Boolean,
    val state: String,
    val lastStatus: String,
    /** 上次运行是否成功（用于配色，不受翻译影响）。 */
    val lastOk: Boolean,
    val lastRun: String,
    val nextRun: String,
)

/** SSE 断流后的最大自动重连次数（退避等待，见 ChatViewModel.backoffDelayMs）。 */
private const val MAX_RECONNECT_ATTEMPTS = 8

/**
 * 「服务端还在跑」的状态集合：探测到这些状态就续接事件流，不判结束。
 * 注意必须与 api_server 的 run 状态机一致（含 stopping——已请求停止但还没收尾）。
 */
private val RUNNING_STATES = setOf(
    "started", "running", "waiting_for_approval", "waiting_for_clarify", "queued", "stopping",
)

/** 重开 App 恢复活跃任务时，探测状态的尝试次数（开机网络未就绪时多试几次）。 */
private const val RESUME_PROBE_TRIES = 3

/**
 * 合并服务端与本地记录时，认定「同一条用户消息」的时间窗口。
 * 服务端压缩会重发行 id、改写老行，但保留原时间戳；手机与服务端钟差 + 发送延迟
 * 一般远小于这个窗口，超过即不认（避免把两句同文本的重复提问错配成一条）。
 */
private const val REWRITTEN_ROW_WINDOW_MS = 120_000L

/**
 * 历史域名 → 当前域名。App 把服务器地址存在 prefs 里（登录时填的那次），
 * 就地升级不会改；域名一换（如 2026-10-06 从 .example-old.com 换到 .example.com），
 * 老用户的地址就成了死链，表现正是「一直重连连不上」。
 * 命中即静默改写成新地址，用户不用重新登录。
 */
private val LEGACY_HOSTS = mapOf(
    "your-gateway.example.com" to "your-gateway.example.com",
)

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
        /**
         * 退避探测单飞位：同一会话同时只允许一条退避链在等。
         * 多口子（onClosed / onError / 回前台体检）并发进来时，只有一个能排上重试。
         */
        val probing = java.util.concurrent.atomic.AtomicBoolean(false)
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
        /** 最近一条用户消息的 id：投递回执按它认领。 */
        var lastUserMsgId: Long = 0L
        /** 「确认送达」进行中要回写的用户消息 id；0 表示没有。 */
        var confirmingMsgId: Long = 0L
        /** 断流翻历史的轮询任务；收到正常事件或任务结束时取消。 */
        var recoveryJob: Job? = null
        /** 本会话排队待发的消息（跑着任务时用户又发的那些），按先后顺序，本轮结束依次发。 */
        val queue = mutableListOf<QueuedSend>()
        /** 队列长度：输入栏显示「排队 N 条」。 */
        val queued = MutableStateFlow(0)
        /** 用户按过停止后置真：待发队列暂停，等「继续」再走。 */
        val queuePaused = MutableStateFlow(false)
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

    /** 当前会话排队待发的条数：输入栏显示「排队 N 条」。 */
    val queuedCount: StateFlow<Int> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(0) else rt(id).queued }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 当前会话的待发队列是否被「停止」按住（界面显示「继续」按钮）。 */
    val queuedPaused: StateFlow<Boolean> = _currentId
        .flatMapLatest { id -> if (id.isEmpty()) flowOf(false) else rt(id).queuePaused }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    // ---------- 引用回复 ----------

    /** 当前待引用的一条消息（长按气泡选「引用」后非空，输入栏上方显示引用条）。 */
    private val _quoteTarget = MutableStateFlow<Msg?>(null)
    val quoteTarget = _quoteTarget.asStateFlow()

    /** 长按某条消息选「引用」：记下它，输入栏显示引用条，发送时把片段带上。 */
    fun setQuote(m: Msg) {
        _quoteTarget.value = m
    }

    /** 取消引用。 */
    fun clearQuote() {
        _quoteTarget.value = null
    }

    // ---------- 排队消息编辑回填 ----------

    /** 编辑排队消息时把正文回填到输入框；非空即取走。 */
    private val _queuedEdit = MutableStateFlow<String?>(null)
    val queuedEdit = _queuedEdit.asStateFlow()

    /** 被引正文压缩成一行片段（最多 80 字），服务端与气泡共用。 */
    private fun quoteSnippet(m: Msg): String {
        val one = m.text.replace(Regex("\\s+"), " ").trim()
        val who = if (m.role == "user") "我" else "助手"
        val body = if (one.isEmpty()) "[图片或附件]" else if (one.length > 80) one.take(80) + "…" else one
        return who + "：" + body
    }

    // ---------- 富卡片动作 ----------

    /**
     * 卡片按钮点击分发：
     *   open_url      用浏览器打开 value（不动会话）
     *   send_text     把 value 当一条消息发出去（默认）
     *   slash_command 同 send_text，但原样带上斜杠命令（不走引用拼装）
     */
    fun dispatchCardAction(a: CardAction, ctx: android.content.Context) {
        when (a.action) {
            "open_url" -> {
                if (a.value.isEmpty()) return
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(a.value)
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            "slash_command" -> {
                if (a.value.isEmpty()) return
                send(a.value)
            }
            else -> {
                if (a.value.isEmpty()) return
                send(a.value)
            }
        }
    }

    /** 模型是否支持原生图片（/v1/capabilities features.supports_vision）；未知按 true。 */
    private val _supportsVision = MutableStateFlow(true)
    val supportsVision = _supportsVision.asStateFlow()

    /** 图片上传进度文案（空表示无进行中上传）。 */
    private val _imageNote = MutableStateFlow("")
    val imageNote = _imageNote.asStateFlow()

    // ---------- 会话内搜索（只搜当前会话的本地消息） ----------

    /** 搜索栏是否展开。 */
    private val _searchActive = MutableStateFlow(false)
    val searchActive = _searchActive.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()

    /** 命中的消息 id，按会话顺序。 */
    private val _searchIds = MutableStateFlow<List<Long>>(emptyList())
    val searchIds = _searchIds.asStateFlow()

    /** 当前跳到第几条（0 基）；无命中为 -1。 */
    private val _searchIdx = MutableStateFlow(-1)
    val searchIdx = _searchIdx.asStateFlow()

    private var searchJob: Job? = null

    /** 打开/收起搜索栏。收起时清掉全部搜索态。 */
    fun toggleSearch() {
        if (_searchActive.value) clearSearch() else {
            _searchActive.value = true
            _searchQuery.value = ""
            _searchIds.value = emptyList()
            _searchIdx.value = -1
        }
    }

    /**
     * 输入即搜：去抖 150 毫秒（快速连打只算一次扫描），匹配放后台线程算，不卡界面。
     * 只搜当前会话的本地消息（最多 300 条）。
     */
    fun setSearchQuery(q: String) {
        _searchQuery.value = q
        searchJob?.cancel()
        val sid = _currentId.value
        if (q.isBlank()) {
            _searchIds.value = emptyList()
            _searchIdx.value = -1
            return
        }
        searchJob = viewModelScope.launch {
            delay(150)
            val msgs = rt(sid).messages.value
            val ids = withContext(Dispatchers.Default) { ChatSearch.matchIds(msgs, q) }
            // 结果回来时用户可能已改词或切走会话：过期的结果不落地。
            if (_currentId.value != sid || _searchQuery.value != q) return@launch
            _searchIds.value = ids
            _searchIdx.value = if (ids.isEmpty()) -1 else 0
        }
    }

    /** 上下跳转：dir 为 +1 / -1，到头绕回。 */
    fun searchNavigate(dir: Int) {
        val n = _searchIds.value.size
        if (n == 0) return
        val cur = _searchIdx.value
        _searchIdx.value = (((cur + dir) % n) + n) % n
    }

    fun clearSearch() {
        searchJob?.cancel()
        _searchActive.value = false
        _searchQuery.value = ""
        _searchIds.value = emptyList()
        _searchIdx.value = -1
    }

    // ---------- 跨会话搜索（扫本地全部会话） ----------

    /** 全局搜索面板是否展开（抽屉里）。 */
    private val _globalActive = MutableStateFlow(false)
    val globalActive = _globalActive.asStateFlow()

    private val _globalQuery = MutableStateFlow("")
    val globalQuery = _globalQuery.asStateFlow()

    private val _globalHits = MutableStateFlow<List<GlobalHit>>(emptyList())
    val globalHits = _globalHits.asStateFlow()

    private var globalJob: Job? = null

    fun toggleGlobalSearch() {
        if (_globalActive.value) clearGlobalSearch() else {
            _globalActive.value = true
            _globalQuery.value = ""
            _globalHits.value = emptyList()
        }
    }

    fun clearGlobalSearch() {
        globalJob?.cancel()
        _globalActive.value = false
        _globalQuery.value = ""
        _globalHits.value = emptyList()
    }

    /** 输入即搜（去抖 200 毫秒），扫本地全部会话文件，放后台线程算。 */
    fun setGlobalQuery(q: String) {
        _globalQuery.value = q
        globalJob?.cancel()
        if (q.isBlank()) {
            _globalHits.value = emptyList()
            return
        }
        globalJob = viewModelScope.launch {
            delay(200)
            val hits = withContext(Dispatchers.IO) { store.searchAll(q) }
            if (_globalQuery.value != q) return@launch
            _globalHits.value = hits
        }
    }

    /**
     * 点一条跨会话命中：切到那个会话，并让本会话内的搜索栏定位到这条消息。
     * 命中靠正文认（本地消息 id 不落盘），取该会话里第一条正文一致的消息。
     */
    fun openGlobalHit(hit: GlobalHit) {
        clearGlobalSearch()
        if (hit.sessionId != _currentId.value) switchSession(hit.sessionId)
        ensureLoaded(hit.sessionId)
        viewModelScope.launch {
            delay(80)   // 等会话消息装载完
            val msgs = rt(hit.sessionId).messages.value
            val idx = msgs.indexOfFirst { it.text == hit.text }
            if (idx < 0) return@launch
            _searchActive.value = true
            _searchQuery.value = hit.text
            _searchIds.value = listOf(msgs[idx].id)
            _searchIdx.value = 0
        }
    }

    private var api: HermesApi? = null
    /**
     * 探测循环只允许起一个。
     *
     * 原来用普通 Boolean 做防重入，但 onProfileChanged 可能被并发调用两次
     * （登录回调 + MainScaffold 的 LaunchedEffect），两个线程都读到 false，
     * 各自把自己当成第一个 → 起了多个 5 秒轮询循环。症状：同一条「恢复在线」
     * 一次打好几遍、离线判定挤在同一毫秒、探测请求成倍（白耗流量与电）。
     * compareAndSet 是原子操作，只有一个线程能赢。
     */
    private val pingStarted = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /** 本轮计时起点：优先会话级的（整轮唯一、不会被气泡重建冲掉），没有才取当下。 */
    private fun turnStart(r: SessionRuntime): Long = if (r.startedAt > 0) r.startedAt else stamp()

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
        // 续接序号与消息一起落盘，保证两者永远一致：重开 App 时按它做
        // Last-Event-ID，只补断线之后的事件（消息也正好停在那一刻）——
        // 既不会重放已存过的工具轨迹（表现是「过程重复显示」），也不会漏事件。
        if (r.runId.isNotEmpty()) prefs.putLastSeq(r.id, r.lastSeq)
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

    /** 上次从服务端同步标题的时间：节流用，避免每个轮末都打一次接口。 */
    private var lastTitleSyncAt = 0L

    /** 只列「用户真正聊过」的来源：App(api_server)、微信(weixin)、CLI(cli)。
     *  定时任务(cron)、子智能体(subagent)、一次性(oneshot)等机器会话不进历史列表。 */
    private val userFacingSources = setOf("api_server", "weixin", "cli")

    /** 「从服务端拉取」的反馈文字：拉完显示几秒再清掉。 */
    private val _pullNote = MutableStateFlow("")
    val pullNote: StateFlow<String> = _pullNote.asStateFlow()

    /** 自动回填门槛：只补最近这些天、且消息数达到这么多的会话。 */
    private val AUTO_BACKFILL_DAYS = 7L
    private val AUTO_BACKFILL_MIN_MSGS = 6
    /** 手动拉取门槛：不限时间，但要有实际对话（滤掉一问一答的探针会话）。 */
    private val MANUAL_BACKFILL_MIN_MSGS = 4

    /**
     * 从服务端拉会话列表，合并进本地索引。
     *
     * 为什么需要：本地标题只有一条生成路径（touchSession：首条用户文本截 20 字），重开 App
     * 先拉回服务端消息、排队发送、别的端建的会话都会错过，标题永远停在「新对话」；服务端
     * 一直用小模型生成 3~7 词正式标题，取来覆盖即可。
     *
     * 关于「补行」的边界（2026-10-06 收紧）：此前把服务端全部会话都倒灌进本地列表，一次灌进
     * 六十多条测试会话与几十天前的老会话，用户当场反馈「历史对话召回太多了」。现在改成：
     * - 自动路径：**只在本地列表为空时才补**（索引被清 / 重装 / 换机），且只补最近 7 天、
     *   消息数 ≥6 的；平时一条都不灌。
     * - 手动路径（[pullFromServer]）：用户点「拉取」才补，不限时间，但要有实际对话。
     * - 两种路径都只补用户来源（App / 微信 / CLI），定时任务、子智能体不进列表。
     * - 只增不删（相对用户数据）：本地已有行（含尚未落服务端的新对话）一律保留；已有的只更新标题，不动排序。
     * - 顺手清理：把「服务端有、本地却没聊天记录、又不达标」的空壳行删掉——正是上一版灌进来的那些。
     */
    fun syncFromServer(throttleMs: Long = 0L, force: Boolean = false) {
        val a = api ?: return
        val now = System.currentTimeMillis()
        if (throttleMs > 0 && now - lastTitleSyncAt < throttleMs) return
        lastTitleSyncAt = now
        // 只有「列表为空」或用户手动拉取时，才允许往列表里补行；其余情况仅同步标题。
        val mayAdd = force || _sessions.value.isEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = a.listSessions()
                val arr = resp.optJSONArray("data") ?: return@launch
                val list = _sessions.value.toMutableList()
                val serverIds = HashSet<String>()
                val keepIds = HashSet<String>()
                val maxAgeMs = AUTO_BACKFILL_DAYS * 86_400_000L
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty()) continue
                    serverIds.add(id)
                    val isChild = o.optBoolean("is_internal_child", false)
                    val src = o.optString("source", "")
                    val archived = o.optBoolean("archived", false)
                    val hidden = o.optBoolean("hidden", false)
                    val msgs = o.optInt("message_count", 0)
                    val ts = (o.optDouble("last_active", 0.0) * 1000.0).toLong()
                    // 达标集合：手动路径不限时间，自动路径限最近 N 天。
                    if (!isChild && src in userFacingSources && !archived && !hidden &&
                        msgs >= (if (force) MANUAL_BACKFILL_MIN_MSGS else AUTO_BACKFILL_MIN_MSGS) &&
                        (force || (ts > 0 && now - ts <= maxAgeMs))) {
                        keepIds.add(id)
                    }
                }
                var changed = false
                var added = 0
                var pruned = 0
                // 1) 已有行同步标题；服务端独有且达标的行按需补进来。
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isEmpty() || o.optBoolean("is_internal_child", false)) continue
                    val title = o.optString("title", "").trim()
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx >= 0) {
                        if (title.isNotEmpty() && list[idx].title != title) {
                            list[idx] = list[idx].copy(title = title); changed = true
                        }
                        continue
                    }
                    if (!mayAdd || id !in keepIds) continue
                    val ts = (o.optDouble("last_active", 0.0) * 1000.0).toLong()
                    list.add(SessionMeta(id, title.ifEmpty { "对话" }, if (ts > 0) ts else stamp(), false))
                    changed = true; added++
                }
                // 2) 清理上一版灌进来的空壳行：服务端有、本地没聊天记录、又不达标的。
                if (mayAdd) {
                    val kept = list.filter { meta ->
                        if (store.hasMessages(meta.id)) true          // 本地真有聊天记录：永久保留
                        else if (meta.id !in serverIds) true          // 本地新建、还没落服务端：保留
                        else meta.id in keepIds                       // 服务端会话：只留达标的
                    }
                    if (kept.size != list.size) {
                        pruned = list.size - kept.size
                        list.clear(); list.addAll(kept); changed = true
                    }
                }
                if (!changed) {
                    if (force) _pullNote.value = "拉取完成：无新会话"
                    return@launch
                }
                val sorted = list.sortedByDescending { it.updatedAt }
                _sessions.value = sorted
                store.saveIndex(sorted)
                // 空态自愈：列表原本是空的（索引丢失），补进来后落到最近一个会话。
                if (_currentId.value.isEmpty() && sorted.isNotEmpty()) {
                    val target = sorted.firstOrNull { !it.archived } ?: sorted.first()
                    withContext(Dispatchers.Main) {
                        _currentId.value = target.id
                        prefs.sessionId = target.id
                        ensureLoaded(target.id)
                    }
                }
                if (force) _pullNote.value = "拉取完成：补回 " + added + " 个、清理 " + pruned + " 个"
                if (added > 0 || pruned > 0) {
                    AppLog.log("session-sync", "补回 " + added + " 个、清理 " + pruned + " 个会话")
                }
            } catch (_: Exception) {
                // 离线 / 接口异常：保留本地列表，不影响使用
                if (force) _pullNote.value = "拉取失败：网络或接口异常"
            }
        }
    }

    /** 列表顶部「拉取」按钮：手动从服务端合并一次会话（不限时间，但要有实际对话）。 */
    fun pullFromServer() {
        _pullNote.value = "拉取中…"
        syncFromServer(force = true)
        viewModelScope.launch { delay(4000); _pullNote.value = "" }
    }

    /** 切到某个会话（网关 session_id 同步指过去）。不再停止任何正在跑的任务。 */
    fun switchSession(id: String) {
        if (id == _currentId.value) return
        clearSearch()   // 搜索只作用于当前会话：切走即收起
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

    /**
     * 把一个会话导出成 Markdown 文件并调系统分享面板发出去（发同事/存网盘）。
     *
     * 落在 App 私有目录 exports/（FileProvider 已声明），只读本地记录，不碰服务端。
     * 失败只在日志里记一行，不弹错——导出是附加功能，坏了不该打扰正常对话。
     */
    fun exportSession(ctx: Context, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val meta = _sessions.value.firstOrNull { it.id == id }
                val title = meta?.title ?: "对话"
                val md = store.exportMarkdown(id, title)
                if (md.isBlank()) {
                    AppLog.log("export", "会话为空，跳过导出 id=" + id.take(8))
                    return@launch
                }
                val dir = File(ctx.filesDir, "exports").apply { mkdirs() }
                val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40).ifBlank { "对话" }
                val f = File(dir, safe + "_" + TimeFmt.mdhm(System.currentTimeMillis())
                    .replace(Regex("[^0-9]"), "") + ".md")
                f.writeText(md)
                AppLog.log("export", "已导出 " + f.name + " " + md.length + " 字")
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(ctx, "com.hermesapp.fileprovider", f)
                    val it = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(Intent.createChooser(it, "导出对话").apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    })
                }
            } catch (e: Exception) {
                AppLog.err("export", "导出失败", e)
            }
        }
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
        migrateLegacyHost(p)
        val key = if (p.profile == "default") Keys.DEFAULT_KEY else Keys.FRIEND_KEY
        val prefix = if (p.profile == "default") "" else "/p/friend"
        api = HermesApi(p.serverUrl, key, prefix)
        // 网关托管媒体：把带鉴权的取文件函数挂给 Markdown 附件卡片
        val a0 = api
        MediaFetch.handler = { token -> a0?.downloadMedia(token) }
        bootstrapSessions(p.sessionId)
        syncFromServer()
        pingLoop()
        refreshStatus()
        refreshFromServer()
        fetchCapabilities()
        resumeActiveRun()
        drainPendingReply()
        checkUpdateSilently()
    }

    /**
     * 老域名静默迁移：把 prefs 里存的历史域名换成当前域名。
     *
     * 为什么必须做：服务器地址是登录时写进 prefs 的，就地升级（不重新登录）
     * 时不会更新。域名一旦迁移（2026-10-06 .example-old.com → .example.com），
     * 老用户的地址就指向死链，表现就是「一直重连连不上、怎么都连不上」。
     * 这里只做主机名替换，路径/端口/协议原样保留。
     */
    private fun migrateLegacyHost(p: Prefs) {
        val cur = p.serverUrl
        if (cur.isEmpty()) return
        val hit = LEGACY_HOSTS.entries.firstOrNull { cur.contains(it.key) } ?: return
        val fixed = cur.replace(hit.key, hit.value)
        if (fixed != cur) p.serverUrl = fixed
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
                // 开机时网络往往还没就绪，探测失败不能当成「任务已结束」——
                // 那样会把活跃标记删掉，这条任务就永远回不来了。重试几次，
                // 实在探不出来就保留标记，等下次进前台/切身份再试。
                var last: HermesApi.RunStatus = HermesApi.RunStatus.Unknown
                for (i in 1..RESUME_PROBE_TRIES) {
                    last = a.probeRun(rid)
                    if (last !is HermesApi.RunStatus.Unknown) break
                    delay(if (i == 1) 1_000L else 3_000L)
                }
                when (last) {
                    is HermesApi.RunStatus.Known -> {
                        AppLog.log("resume", "恢复探测 sid=" + sid.take(8) + " 状态=" + last.status)
                        if (last.status in RUNNING_STATES) {
                            val r = rt(sid)
                            ensureLoaded(sid)
                            r.runId = rid
                            r.finished = false
                            // 续接序号从落盘恢复：只补断线之后的事件，避免从 0 全量重放
                            // 把已经存过的工具轨迹再追加一遍（用户报「过程重复显示」）。
                            r.lastSeq = prefs.lastSeq(sid)
                            // 服务端还在等审批/澄清：把那张卡片重新挂回去（续接流只补
                            // lastSeq 之后的事件，早于断点的 clarify.request 不会重放）。
                            restorePendingCard(r, last.payload)
                            // 本地落盘的消息读回来时 pending 一律是 false，而服务端这条 run
                            // 还没结束。若断线前已收到过事件（lastSeq>=0），本地就有这一轮的
                            // 气泡，把它恢复成「进行中」，续接来的增量才追加到同一条上，不会
                            // 再起一条（否则同一轮正文/轨迹会显示两次）；若还没收到过任何事件，
                            // 本地只有上一轮的回复，绝不能标它 pending（新内容会挂到旧回复上），
                            // 这时新起一个空气泡接续。
                            val lastAssistant = r.messages.value.indexOfLast { it.role == "assistant" }
                            val hasLocalTurnBubble = lastAssistant >= 0 &&
                                lastAssistant == r.messages.value.lastIndex && r.lastSeq >= 0
                            if (hasLocalTurnBubble) {
                                val m = r.messages.value.toMutableList()
                                m[lastAssistant] = m[lastAssistant].copy(
                                    pending = true,
                                    startedAt = if (m[lastAssistant].startedAt > 0) m[lastAssistant].startedAt
                                    else if (r.startedAt > 0) r.startedAt else System.currentTimeMillis()
                                )
                                r.messages.value = m
                            } else {
                                r.messages.value = r.messages.value +
                                    Msg("assistant", "", pending = true, ts = System.currentTimeMillis(), startedAt = turnStart(r))
                            }
                            r.busy.value = true
                            updateRunService()
                            streamRun(a, sid)
                        } else {
                            // run 已结束：清掉落盘读回的过期待办卡片，再拉记录。
                            ensureLoaded(sid)
                            clearStaleCards(rt(sid))
                            prefs.removeActiveRun(sid)
                            if (sid == _currentId.value) refreshFromServer()
                        }
                    }
                    // 服务端明确说没这个 run：标记失效，清掉并拉一次记录。
                    HermesApi.RunStatus.Missing -> {
                        ensureLoaded(sid)
                        clearStaleCards(rt(sid))
                        prefs.removeActiveRun(sid)
                        if (sid == _currentId.value) refreshFromServer()
                    }
                    // 探不出来：保留标记（不清），只拉一次服务端记录兜底。
                    // 下次 onProfileChanged / onAppForeground 还会再来试。
                    HermesApi.RunStatus.Unknown -> {
                        if (sid == _currentId.value) refreshFromServer()
                    }
                }
            }
        }
    }

    /**
     * 回到前台时的即时体检：对每个仍在跑、且超过 25 秒没收到任何事件（含心跳帧）的会话，
     * 判定为「流已被链路假死卡住」，主动断开并走一次重连续接。
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
        // 回前台补齐前台服务：后台期间被 Android 12+ 限制挡下的启动在这里补上，
        // 保证继续挂着的任务不再被系统冻结。
        updateRunService()
        // 顺带刷一次在线状态，别让角标停在离线
        viewModelScope.launch(Dispatchers.IO) { refreshStatus() }
        // 回前台顺带同步一次服务端标题（节流 30 秒，避免频繁切前后台狂打接口）
        syncFromServer(30_000L)
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
                // 对齐方式不能用数组下标：服务端会滤掉「内容为空的助手行」与工具行，
                // 本地却保留带工具轨迹的空助手行，两边长度天然不等（实测某会话差 236 行），
                // 按下标对齐会从第一处差异起整体错位，把服务端回复贴到错误的气泡上。
                // 改用两边都完整保留、且有序的「用户消息」做锚点，见 mergeByUserAnchor。
                val local = r.messages.value
                val merged = mergeByUserAnchor(local, list)
                r.messages.value = merged
                r.loaded = true
                store.saveMessages(id, merged, maxHistory)
            } catch (_: Exception) {
                // 服务端无此会话或网络异常：保留本地内容
            }
        }
    }

    /**
     * 服务端会话记录与本地消息的合并——按「用户消息内容」对齐，不按数组下标。
     *
     * 为什么不能按下标：/api/sessions/{id}/messages 会滤掉空内容的助手行（本地保留，
     * 它们带工具轨迹 trace），且**长会话被服务端压缩时老行会被删/合并**——两边用户消息
     * 条数天然不等，按「第几条」对齐会从第一处差异起整体错位（把服务端回复贴到错误的
     * 气泡上、用户消息重复出现）。用户消息正文两侧基本原样保留，所以拿「正文归一 + 时间
     * 接近」做顺序匹配：服务端被压缩删掉的那条匹配不上就跳过，不影响其后各块的对齐。
     */
    private fun mergeByUserAnchor(local: List<Msg>, srv: List<Msg>): List<Msg> {
        fun isUser(m: Msg) = m.role == "user"
        fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

        // 各自切块：head = 第一条用户消息之前的行；随后每个用户消息带一个 tail
        //（其后、下一条用户消息之前的助手/系统行）。
        fun blocks(ms: List<Msg>): Triple<MutableList<Msg>, MutableList<Msg>, MutableList<MutableList<Msg>>> {
            val head = mutableListOf<Msg>()
            val users = mutableListOf<Msg>()
            val tails = mutableListOf<MutableList<Msg>>()
            var started = false
            for (m in ms) {
                if (isUser(m)) { started = true; users.add(m); tails.add(mutableListOf()) }
                else if (started) tails.last().add(m) else head.add(m)
            }
            return Triple(head, users, tails)
        }
        val (lHead, lUsers, lTails) = blocks(local)
        val (sHead, sUsers, sTails) = blocks(srv)

        // 用户消息顺序匹配：正文归一相同（时间做二次确认，避免同文本误配）才认成同一条。
        // 服务端压缩删掉的老用户消息匹配不上 → 跳过，不影响其后各块。
        val l2s = IntArray(lUsers.size) { -1 }
        var sj = 0
        for (li in lUsers.indices) {
            val a = lUsers[li]
            val na = norm(a.text)
            var k = sj
            while (k < sUsers.size) {
                val b = sUsers[k]
                val sameText = na.isNotEmpty() && na == norm(b.text)
                val tsOk = a.ts <= 0 || b.ts <= 0 || kotlin.math.abs(a.ts - b.ts) <= REWRITTEN_ROW_WINDOW_MS
                if (sameText && tsOk) { l2s[li] = k; sj = k + 1; break }
                k++
            }
        }
        val srvMatched = BooleanArray(sUsers.size)
        for (m in l2s) if (m >= 0) srvMatched[m] = true

        fun mergeTail(lt: List<Msg>, st: List<Msg>): List<Msg> {
            if (lt.isEmpty()) return st
            if (st.isEmpty()) return lt
            val seg = lt.toMutableList()
            val li = seg.indexOfLast { it.role == "assistant" }
            val sText = st.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text
            if (li >= 0 && seg[li].text.isEmpty() && !sText.isNullOrEmpty()) {
                seg[li] = seg[li].copy(text = sText, pending = false)
            }
            return seg
        }

        val out = mutableListOf<Msg>()
        out.addAll(if (lHead.isNotEmpty()) lHead else sHead)
        // 服务端独有的用户块（被压缩改写 / 别的端发的轮次）按其原顺序插回对应位置：
        // 用「下一个本地已匹配块的 srv 索引」当边界，把边界之前未匹配的服务端块补进去。
        var sIdx = 0
        for (li in lUsers.indices) {
            val target = l2s[li]
            if (target >= 0) {
                while (sIdx < target) {
                    if (!srvMatched[sIdx]) { out.add(sUsers[sIdx]); out.addAll(sTails[sIdx]) }
                    sIdx++
                }
                sIdx = target + 1
            }
            out.add(lUsers[li])
            out.addAll(mergeTail(lTails[li], if (target >= 0) sTails[target] else emptyList()))
        }
        // 本地所有块之后，剩余未匹配的服务端用户块照原顺序补上。
        while (sIdx < sUsers.size) {
            if (!srvMatched[sIdx]) { out.add(sUsers[sIdx]); out.addAll(sTails[sIdx]) }
            sIdx++
        }
        return out
    }

    private fun parseTs(s: String): Long = runCatching {
        java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private fun pingLoop() {
        if (!pingStarted.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            var fails = 0
            var tick = 0
            while (true) {
                val ok = api?.ping() ?: false
                if (ok) {
                    if (!_online.value) AppLog.log("net", "恢复在线")
                    fails = 0
                    _online.value = true   // 恢复立刻生效
                } else {
                    // 连续 2 次失败才翻「离线」，避免单次抖动闪红。
                    fails++
                    if (fails >= 2) {
                        if (_online.value) AppLog.log("net", "连续 " + fails + " 次探测失败，标记离线")
                        _online.value = false
                    }
                }
                // 每 30 秒静默查一次更新：发新版后角标自动亮起，不必等下次启动。
                tick++
                if (tick % 6 == 0) checkUpdateSilently()
                delay(5000)
            }
        }
    }

    // ---------- 定时任务（服务端 /api/jobs） ----------

    private val _jobs = MutableStateFlow<List<JobItem>>(emptyList())
    val jobs = _jobs.asStateFlow()

    private val _jobsErr = MutableStateFlow("")
    val jobsErr = _jobsErr.asStateFlow()

    private val _jobsNote = MutableStateFlow("")
    val jobsNote = _jobsNote.asStateFlow()

    /** 上次列表用的过滤口径：动作完成后按同一口径重拉，避免刚暂停的任务凭空消失。 */
    private var jobsIncludeDisabled = false

    /** 已知定时任务的中文名；不认识的返回空串，界面回落显示原始名。 */
    private fun jobZhName(name: String): String {
        when (name) {
            "nightly-memory-refactor" -> return "夜间记忆整理"
            "browser-idle-reaper" -> return "浏览器空闲回收"
            "mem0-watchdog" -> return "记忆库看门狗"
            "boot-verify-report" -> return "开机自检报告"
            "fix-dup-unit-report" -> return "重复服务修复报告"
            "boot-verify2-report" -> return "开机自检报告（二）"
        }
        // 精确表认不出时按关键词兜底：别的档案（friend）和以后新加的任务都能自动出中文，
        // 不用每加一个任务改一次代码。顺序有讲究：watchdog 必须排在 watch 前面。
        val n = name.lowercase()
        return when {
            n.contains("memory-refactor") || n.contains("memory_refactor") -> "记忆整理"
            n.contains("watchdog") -> "看门狗"
            n.contains("watch") -> "上游巡检"
            n.contains("reaper") -> "空闲回收"
            n.contains("backup") -> "备份"
            n.contains("report") -> "结果报告"
            n.contains("verify") || n.contains("check") -> "自检"
            else -> ""
        }
    }

    /** 这个任务是干什么的；不认识的返回空串。 */
    private fun jobZhNote(name: String): String {
        when (name) {
            "nightly-memory-refactor" ->
                return "每天凌晨自动整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
            "browser-idle-reaper" ->
                return "每 15 分钟收掉闲置的浏览器进程，回收内存；没有闲置进程时静默。"
            "mem0-watchdog" ->
                return "每 15 分钟检查记忆库：服务掉了就拉起，网关记忆后端初始化失败就重启网关。健康时静默。"
            "boot-verify-report" ->
                return "一次性任务：容器重启后把开机自检结果发给你，跑完自动删。"
            "fix-dup-unit-report" ->
                return "一次性任务：修复重复网关服务后把结果发给你，跑完自动删。"
            "boot-verify2-report" ->
                return "一次性任务：容器重启后的自检（含重启次数与重复服务检查），跑完自动删。"
        }
        val n = name.lowercase()
        return when {
            n.contains("memory-refactor") || n.contains("memory_refactor") ->
                "定时整理记忆：做容量体检，把待落盘的内容并进记忆文件，超限就压缩。"
            n.contains("watchdog") ->
                "定期检查记忆库：服务掉了就拉起，网关记忆后端初始化失败就重启网关。健康时静默。"
            n.contains("watch") ->
                "定期巡检上游页面的变化，有变化才出报告；无变化时静默。"
            n.contains("reaper") ->
                "定期收掉闲置的进程，回收内存；没有闲置时静默。"
            n.contains("backup") ->
                "定时备份数据。"
            n.contains("report") ->
                "一次性任务：把结果发给你，跑完自动删。"
            n.contains("verify") || n.contains("check") ->
                "定时自检，结果有异常才提醒。"
            else -> ""
        }
    }

    /** 运行状态翻译。 */
    private fun jobZhState(s: String): String = when (s) {
        "scheduled" -> "已排期"
        "running" -> "执行中"
        "completed" -> "已完成"
        "paused" -> "已暂停"
        "failed" -> "失败"
        else -> s
    }

    /** 上次运行结果翻译。 */
    private fun jobZhStatus(s: String): String = when (s) {
        "ok" -> "正常"
        "failed", "error" -> "失败"
        else -> s
    }

    /** 排期翻译：interval / cron / once 三类分别转中文。 */
    private fun jobZhSchedule(sch: JSONObject?): String {
        if (sch == null) return ""
        return when (sch.optString("kind", "")) {
            "interval" -> {
                val m = sch.optInt("minutes", 0)
                when {
                    m <= 0 -> "定时"
                    m % 60 == 0 -> "每 " + (m / 60) + " 小时"
                    else -> "每 " + m + " 分钟"
                }
            }
            // 优先按 cron 表达式翻（服务端 display 常是「every day at 9am」这种英文，
            // 靠它翻不出来）；表达式翻不出再回落 display。
            "cron" -> cronZh(sch.optString("expr", "")).ifEmpty { sch.optString("display", "") }
            "once" -> "一次性"
            else -> sch.optString("display", "")
        }
    }

    /** 5 段 cron 转中文；认不出就原样返回。 */
    private fun cronZh(expr: String): String {
        val p = expr.trim().split(Regex("\\s+"))
        if (p.size != 5) return expr
        val mi = p[0]
        val ho = p[1]
        val dom = p[2]
        val mo = p[3]
        val dow = p[4]
        val everyMin = Regex("^\\*/(\\d+)$").find(mi)
        if (everyMin != null && ho == "*" && dom == "*" && mo == "*" && dow == "*") {
            return "每 " + everyMin.groupValues[1] + " 分钟"
        }
        if (mi == "*" && ho == "*") return "每分钟"
        val m = mi.toIntOrNull()
        val h = ho.toIntOrNull()
        if (m != null && h != null) {
            val hm = String.format("%02d:%02d", h, m)
            return when {
                dom == "*" && mo == "*" && dow == "*" -> "每天 " + hm
                dom == "*" && mo == "*" && dow == "1-5" -> "工作日 " + hm
                dom == "*" && mo == "*" -> "每周 " + hm
                else -> "每月 " + dom + " 日 " + hm
            }
        }
        return expr
    }

    fun refreshJobs(includeDisabled: Boolean = jobsIncludeDisabled) {
        jobsIncludeDisabled = includeDisabled
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api?.listJobs(includeDisabled) ?: return@launch
                val arr = resp.optJSONArray("jobs") ?: org.json.JSONArray()
                val out = mutableListOf<JobItem>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val sch = o.optJSONObject("schedule")
                    val rawName = o.optString("name", "(未命名)")
                    val rawLast = o.optString("last_status", "")
                    out.add(
                        JobItem(
                            id = o.optString("id", ""),
                            name = rawName,
                            zhName = jobZhName(rawName),
                            note = jobZhNote(rawName),
                            schedule = jobZhSchedule(sch),
                            enabled = o.optBoolean("enabled", true),
                            state = jobZhState(o.optString("state", "")),
                            lastStatus = jobZhStatus(rawLast),
                            lastOk = rawLast == "ok",
                            lastRun = TimeFmt.isoToBj(o.optString("last_run_at", "")),
                            nextRun = TimeFmt.isoToBj(o.optString("next_run_at", "")),
                        )
                    )
                }
                _jobs.value = out
                _jobsErr.value = ""
            } catch (e: Exception) {
                _jobsErr.value = "获取失败：" + (e.message ?: "?")
            }
        }
    }

    /** 定时任务动作：pause / resume / run。成功后按原过滤口径重拉列表。 */
    fun jobAction(jobId: String, action: String) {
        if (jobId.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val label = when (action) {
                "pause" -> "已暂停"
                "resume" -> "已恢复"
                "run" -> "已触发执行"
                else -> "完成"
            }
            try {
                val a = api ?: return@launch
                when (action) {
                    "pause" -> a.pauseJob(jobId)
                    "resume" -> a.resumeJob(jobId)
                    "run" -> a.runJob(jobId)
                }
                _jobsNote.value = label
                _jobsErr.value = ""
            } catch (e: Exception) {
                _jobsNote.value = ""
                _jobsErr.value = "操作失败：" + (e.message ?: "?")
            }
            delay(400)
            refreshJobs()
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
        // 同一会话正在跑：不再直接拦下，改为排队（气泡先落下，本轮结束自动发）。
        // 其它会话照发，不受影响。
        val willQueue = r.busy.value
        ensureLoaded(sid)
        val wasEmpty = r.messages.value.none { it.role == "user" }
        // 引用回复：长按选中的那条，压成一行片段。发往服务端的正文前拼上「> 引用」，
        // 让模型知道在回应哪一句；气泡里只回显片段，不重复整段。
        val quoted = _quoteTarget.value
        val quoteSnip = if (quoted != null) quoteSnippet(quoted) else ""
        val sendText = if (quoteSnip.isNotEmpty()) "> " + quoteSnip + "\n" + text else text
        // 只选了附件、一个字没打时，正文是空的。服务端对 input 有非空校验，而且是先查
        // input、后处理附件——空正文直接被 400 挡回（Missing 'input' field），附件白选。
        // 所以发往服务端的正文补一个占位词；气泡里仍然一个字都不显示。
        // 引用/回执的位置锚点也用这一版，重发与「确认送达」才和服务端记录对得上。
        val wireText = sendText.ifBlank { attachmentLabel(imgs) }
        // 记下发送前的用户消息条数（翻历史时的位置锚点）与本轮正文（内容校验）。
        // 锚点用拼好引用的 sendText：服务端记录里存的就是这一版，才能对上。
        val prior = r.messages.value.count { it.role == "user" }
        r.priorUserCount = prior
        r.pendingSendText = wireText.trim()
        r.recoveryJob?.cancel()
        // 发出去的图先落进 App 私有「已发送」目录：相册给的 content:// 会被系统回收
        // （换机/清数据/授权到期），outbox 会被「清理缓存」清掉——两者都会让历史里的图变白框。
        // sent/ 不参与清理、又是私有文件，重开、清缓存后都还在。
        //
        // 关键：挪动之后，「上传」和「气泡回显」必须都认挪过去的新文件。老代码在这一步把
        // outbox 原文件删了，可后面发请求时还按老路径读 → 文件已不存在，附件必然发不出去。
        val durableImgs = mutableListOf<String>()
        val uploadFiles = mutableListOf<java.io.File>()
        for (p in imgs) {
            if (p.isImage) {
                val (f, uri) = persistOutgoing(p)
                durableImgs.add(uri)
                uploadFiles.add(f)
            } else {
                uploadFiles.add(p.file)
            }
        }
        // 投递状态挂在用户消息自己身上（按 msgId 认领，不靠位置）：POST 没回来前是 sending，
        // 拿到 run_id 才转 accepted，中途断了转 uncertain 等用户处置。
        // 幂等键在发送前一次性生成，写进回执并随请求发出：服务端凭它保证同一句话只执行
        // 一次，重发不会变成两遍。
        val idemKey = UUID.randomUUID().toString().replace("-", "")
        val userMsg = Msg(
            "user", text, ts = stamp(),
            images = durableImgs.toList(),
            files = imgs.filter { !it.isImage }.map { it.file.name },
            receipt = Receipt(
                status = if (willQueue) Receipt.QUEUED else Receipt.SENDING,
                rawText = wireText.trim(),
                priorUserCount = prior,
                idemKey = idemKey,
            ),
            quote = quoteSnip,
            idemKey = idemKey,
        )
        r.lastUserMsgId = userMsg.id
        setMsgs(r, r.messages.value + userMsg)
        touchSession(if (wasEmpty) text.ifBlank { attachmentLabel(imgs) } else null)
        _pendingImages.value = emptyList()
        _quoteTarget.value = null
        if (willQueue) {
            // 排队：气泡先落下（回执标「排队中」），本轮一结束由 drainQueue 自动发。
            r.queue.add(QueuedSend(wireText, uploadFiles.toList(), userMsg.id, prior, idemKey))
            r.queued.value = r.queue.size
            return
        }
        startRunWith(a, sid, wireText, uploadFiles.toList(), receiptMsgId = userMsg.id, idemKey = idemKey)
    }

    /**
     * 排空队列：本会话空闲且队列非空时，取出最早的一条发出去。
     * 在每处「本轮结束」（doneOk / failPending / stopSession / 翻历史收尾）后调用。
     */
    private fun drainQueue(sid: String) {
        val a = api ?: return
        val r = rt(sid)
        if (r.busy.value || r.queue.isEmpty() || r.queuePaused.value) return
        val next = r.queue.removeAt(0)
        r.queued.value = r.queue.size
        r.priorUserCount = next.priorUserCount
        r.pendingSendText = next.text.trim()
        r.recoveryJob?.cancel()
        advanceReceipt(sid, next.msgId, Receipt.SENDING, note = "")
        startRunWith(a, sid, next.text, next.files, receiptMsgId = next.msgId, idemKey = next.idemKey)
    }

    /** 用户点「继续」：解除「停止」对队列的按住，接着发。 */
    fun resumeQueue() {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        r.queuePaused.value = false
        drainQueue(sid)
    }

    /**
     * 撤回一条还没发出去的排队消息：从队列摘掉，连那条用户气泡一起收掉。
     * 排队中的消息本轮结束会自动发，此前没有任何入口能把它收回来。
     */
    fun cancelQueued(msgId: Long) {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val i = r.queue.indexOfFirst { it.msgId == msgId }
        if (i >= 0) r.queue.removeAt(i)
        r.queued.value = r.queue.size
        removeUserMsg(r, msgId)
    }

    /**
     * 编辑一条排队消息：从队列摘掉、收掉原气泡，正文回填到输入框由用户改完重发。
     * 附件不回填（待发区依赖 uri，还原不了），需要就重新选。
     */
    fun editQueued(msgId: Long) {
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val i = r.queue.indexOfFirst { it.msgId == msgId }
        if (i < 0) return
        val q = r.queue.removeAt(i)
        r.queued.value = r.queue.size
        _queuedEdit.value = q.text
        removeUserMsg(r, msgId)
    }

    /** 编辑回填：输入框取走后清空。 */
    fun clearQueuedEdit() { _queuedEdit.value = null }

    private fun removeUserMsg(r: SessionRuntime, msgId: Long) {
        val list = r.messages.value.toMutableList()
        val k = list.indexOfFirst { it.id == msgId }
        if (k >= 0) { list.removeAt(k); setMsgs(r, list) }
    }

    /**
     * 中途插话（steer）：把这句话注入当前正在跑的这一轮，服务端 agent 会读到。
     * 与「排队」不同——排队是下一轮才发，插话是立刻影响本轮方向。
     */
    fun steerCurrent(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val a = api ?: return
        val sid = _currentId.value
        if (sid.isEmpty()) return
        val r = rt(sid)
        val rid = r.runId
        if (rid.isEmpty()) return
        // 插话要在聊天界面看得见：直接插一条用户气泡（标「插话」），
        // 不再只往折叠的过程里塞一行——那样用户以为没发出去。
        run {
            val list = r.messages.value.toMutableList()
            list.add(Msg("user", t, pending = false, ts = stamp(), steer = true))
            setMsgs(r, list)
        }
        // 插话结果要可见：200 才算送进本轮；409/其它说明本轮已收尾、这句没赶上。
        // 原来 runCatching 把返回整个吞了，用户看不到任何反应，才觉得「插不进去」。
        viewModelScope.launch(Dispatchers.IO) {
            val ok = a.steer(rid, t)
            withContext(Dispatchers.Main) {
                r.retryNote.value = if (ok) "插话已送达，本轮会读到"
                else "插话没送达：本轮可能已收尾，这句话没赶上"
            }
        }
    }

    /** 按消息 id 改投递状态（找不到就忽略——消息可能已被「清理缓存」截掉）。 */
    private fun setReceipt(sid: String, msgId: Long, receipt: Receipt?) {
        if (msgId <= 0) return
        val r = rt(sid)
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        list[i] = list[i].copy(receipt = receipt)
        setMsgs(r, list)
    }

    /** 把某条用户消息的投递状态推到下一档，其余字段保留。 */
    private fun advanceReceipt(
        sid: String,
        msgId: Long,
        status: String,
        runId: String? = null,
        note: String = "",
        idemKey: String? = null,
        artifactIds: List<String>? = null,
    ) {
        if (msgId <= 0) return
        val r = rt(sid)
        val list = r.messages.value.toMutableList()
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val old = list[i].receipt
        list[i] = list[i].copy(
            receipt = Receipt(
                status = status,
                runId = runId ?: old?.runId.orEmpty(),
                note = note,
                rawText = old?.rawText ?: list[i].text.trim(),
                priorUserCount = old?.priorUserCount ?: -1,
                // 幂等键与附件 id 一旦定下就不再变：传入才覆盖，否则沿用旧的。
                idemKey = idemKey ?: old?.idemKey.orEmpty(),
                artifactIds = artifactIds ?: old?.artifactIds ?: emptyList(),
            )
        )
        setMsgs(r, list)
    }

    /** 发送明确失败时把那个空气泡收掉，别在界面留一个空壳。 */
    private fun dropEmptyPending(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) return
        val m = list[i]
        if (m.text.isEmpty() && m.trace.isEmpty() && m.approval == null && m.clarify == null &&
            m.usage == null && m.subagents.isEmpty()
        ) {
            list.removeAt(i)
            setMsgs(r, list)
        }
    }

    // ---------- 投递回执：服务端到底收下这条没有 ----------

    private val _receiptMenu = MutableStateFlow(0L)
    /** 当前展开处置按钮的用户消息 id；0 表示没有。 */
    val receiptMenu = _receiptMenu.asStateFlow()

    fun openReceiptMenu(msgId: Long) {
        _receiptMenu.value = if (_receiptMenu.value == msgId) 0L else msgId
    }

    fun closeReceiptMenu() {
        _receiptMenu.value = 0L
    }

    /**
     * 「知道了，不重发」：把「不确定」标成已确认忽略。
     *
     * 原来这条消息卡在黄问号时只有「点这里处理」，用户想收掉角标就只能选「重新发送」——
     * 等于拿一句话去赌会不会发两遍。现在确认收到即可，角标与提示一起收起，不动网络。
     */
    fun acknowledgeReceipt(msgId: Long) {
        val sid = _currentId.value
        _receiptMenu.value = 0L
        advanceReceipt(sid, msgId, Receipt.ACKED, note = "")
    }

    /**
     * 「确认送达」：去服务端会话记录里按位置锚点 + 正文核对这条消息在不在。
     * 在 → 标已送达并接着把答案等回来（复用断流翻历史那套轮询）；
     * 不在 → 标失败，提示可重发。认不出锚点就报失败，绝不硬认。
     */
    fun confirmReceipt(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        val list = r.messages.value
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val rc = list[i].receipt ?: return
        _receiptMenu.value = 0L
        viewModelScope.launch(Dispatchers.IO) {
            val rows = try { historyRows(a, sid) } catch (_: Exception) { emptyList() }
            val anchor = if (rows.isEmpty()) -1 else resolveAnchor(rows, rc.rawText, rc.priorUserCount)
            if (anchor < 0) {
                advanceReceipt(sid, msgId, Receipt.FAILED, note = "服务端记录里没有这条消息，可重新发送")
                return@launch
            }
            advanceReceipt(sid, msgId, Receipt.ACCEPTED, note = "")
            r.pendingSendText = rc.rawText
            r.priorUserCount = rc.priorUserCount
            r.confirmingMsgId = msgId
            r.busy.value = true
            r.finished = false
            r.retryNote.value = "已确认送达，正在取回结果…"
            setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r)))
            updateRunService()
            startHistoryRecovery(sid, "")
        }
    }

    /**
     * 「重新发送」：拿原正文再 POST 一次（图片附件能找回就一并带上）。
     *
     * 复用首次发送时的幂等键与附件 id：服务端凭键认出这是同一条，24 小时内只执行
     * 一次，重发不会变成发两遍；若首次其实已经收下，服务端会把**原来那轮的 run_id**
     * 还回来，这里直接接上那一轮取结果。这就是「不确定」不再需要用户赌一把的原因。
     */
    fun resendReceipt(msgId: Long) {
        val a = api ?: return
        val sid = _currentId.value
        val r = rt(sid)
        val list = r.messages.value
        val i = list.indexOfFirst { it.id == msgId }
        if (i < 0) return
        val m = list[i]
        val rc = m.receipt ?: return
        _receiptMenu.value = 0L
        if (r.busy.value) {
            r.retryNote.value = "本会话还在跑，等这轮结束再重发"
            return
        }
        r.priorUserCount = list.take(i).count { it.role == "user" }
        r.pendingSendText = rc.rawText
        r.recoveryJob?.cancel()
        val files = m.images.mapNotNull { u ->
            runCatching { File(Uri.parse(u).path ?: "") }.getOrNull()?.takeIf { it.exists() }
        }
        // 附件已有 artifact id 就复用，不再重传（重传会换 id、指纹不符会被判冲突）。
        val reuse = rc.artifactIds
        if (m.files.isNotEmpty() && reuse.isEmpty()) {
            r.retryNote.value = "这条带过附件，重发只带上图片，其他附件请重新选"
        }
        advanceReceipt(sid, msgId, Receipt.SENDING, note = "")
        startRunWith(
            a, sid, rc.rawText, files,
            receiptMsgId = msgId,
            idemKey = rc.idemKey,
            reuseArtifacts = reuse,
        )
    }

    /**
     * 只发附件、没打字时，发给服务端的占位正文（服务端不允许空 input）。
     * 纯占位，气泡里不显示；图片本身走原生多模态附件，模型照样看得到图。
     */
    private fun attachmentLabel(imgs: List<PendingImage>): String {
        val hasImg = imgs.any { it.isImage }
        val hasFile = imgs.any { !it.isImage }
        return when {
            hasImg && hasFile -> "（图片和文件）"
            hasImg -> "（图片）"
            else -> "（文件）"
        }
    }

    /**
     * 把待发图片从 outbox 挪进「已发送」目录，返回 (挪过去的文件, 气泡要存的 file:// 地址)。
     *
     * 为什么要把文件也返回来：挪动之后上传必须用新路径。老实现只返回地址、却把 outbox
     * 原文件删了，调用方随后仍拿 p.file 去读字节 → 文件不存在，附件发送必然失败。
     * 落盘失败时退回原文件与原地址（至少本次还能发、还能看），不阻断发送。
     */
    private fun persistOutgoing(p: PendingImage): Pair<File, String> {
        val app = getApplication<Application>()
        return try {
            val dir = File(app.filesDir, "sent").apply { mkdirs() }
            val dst = File(dir, p.file.name)
            if (!dst.exists()) p.file.copyTo(dst, overwrite = true)
            if (p.file.absolutePath != dst.absolutePath) p.file.delete()
            dst to android.net.Uri.fromFile(dst).toString()
        } catch (_: Exception) {
            p.file to p.uri
        }
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

    private fun startRunWith(
        a: HermesApi,
        sid: String,
        text: String,
        files: List<File> = emptyList(),
        receiptMsgId: Long = 0L,
        idemKey: String = "",
        reuseArtifacts: List<String> = emptyList(),
        autoRetryLeft: Int = 1,
    ) {
        val r = rt(sid)
        // 本轮计时起点先定好：气泡与最终耗时都锚在它上面，中途气泡被重建也不会漂。
        r.startedAt = stamp()
        setMsgs(r, r.messages.value + Msg("assistant", "", pending = true, ts = stamp(), startedAt = r.startedAt))
        r.busy.value = true
        r.finished = false
        r.lastSeq = -1
        r.autoContinue = 0
        updateRunService()
        val ids = mutableListOf<String>()
        var uploadsDone = reuseArtifacts.isNotEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 附件 id：重发必须复用首次那份（服务端算指纹含请求体，换了 id 会被判冲突）。
                if (reuseArtifacts.isNotEmpty()) {
                    ids.addAll(reuseArtifacts)
                } else {
                    for ((i, f) in files.withIndex()) {
                        _imageNote.value = "上传附件 ${i + 1}/${files.size}…"
                        ids.add(a.uploadImage(f.readBytes(), f.name, mimeOf(f)))
                    }
                    uploadsDone = true
                }
                _imageNote.value = ""
                val run = a.startRun(text, sid, ids, idemKey)
                r.runId = run.optString("run_id", run.optString("id", ""))
                AppLog.log("send", "已建 run=" + r.runId.take(12) + " 重放=" + run.optBoolean("replayed", false) + " 附件=" + ids.size)
                // 计时起点不在这里重设：上传附件+建 run 的往返也算本轮耗时，
                // 重设会把这一段抹掉，最终值比界面实时值小一截。
                prefs.putActiveRun(sid, r.runId)
                prefs.putLastSeq(sid, -1)   // 新 run 从 0 开始，清掉上一轮的续接序号
                // 拿到 run_id 才算「服务端已收下」，此时回执才转已送达。
                // 幂等键与附件 id 一并落进回执，供后续重发原样复用。
                advanceReceipt(sid, receiptMsgId, Receipt.ACCEPTED, runId = r.runId,
                    idemKey = idemKey, artifactIds = ids)
                streamRun(a, sid)
            } catch (e: Exception) {
                _imageNote.value = ""
                val msg = e.message ?: "?"
                val httpReject = msg.startsWith("HTTP ")
                AppLog.err("send", "发送失败 服务端拒绝=" + httpReject + " 内容=" + msg.take(200), e)
                // 网络中断（收不到回执）、带幂等键、且还没重试过 → 自动安全重试一次。
                // 为什么现在敢自动重试：服务端对同一个键只执行一次，重试要么接上原来那轮
                // （首次其实已收下，只是回包丢了），要么全新执行一次，绝不会变成发两遍。
                // 这正是加幂等键最大的收益——「不确定」不再需要用户赌一把。
                if (!httpReject && idemKey.isNotEmpty() && autoRetryLeft > 0) {
                    dropEmptyPending(r)
                    r.retryNote.value = "发送未确认，正在自动重试…"
                    delay(2000)
                    // 附件已传完就复用那份 id（保证指纹一致、能命中重放）；
                    // 上传阶段就断了则重新上传（此时服务端没记下任何键，不会冲突）。
                    startRunWith(
                        a, sid, text, files, receiptMsgId,
                        idemKey = idemKey,
                        reuseArtifacts = if (uploadsDone) ids.toList() else emptyList(),
                        autoRetryLeft = autoRetryLeft - 1,
                    )
                    return@launch
                }
                // 服务端有明确回执（HTTP 4xx/5xx）= 拒绝，标失败；
                // 重试用尽仍收不到回执 = 标「不确定」等用户处置（此时仍可手动重发，也安全）。
                dropEmptyPending(r)
                if (httpReject) {
                    advanceReceipt(sid, receiptMsgId, Receipt.FAILED, note = msg)
                } else {
                    r.retryNote.value = "发送结果不确定：网络中断，服务端可能已收下，可重发（不会重复）"
                    advanceReceipt(sid, receiptMsgId, Receipt.UNCERTAIN, note = msg)
                }
                failPending(sid)
            }
        }
    }

    /** 澄清请求：挂到该会话当前助手气泡上，等用户选选项回执。 */
    private fun attachClarify(r: SessionRuntime, ev: com.hermesapp.net.SseEvent) {
        notifyNeedAction(r.id, "需要你选一下", ev.data.optString("question", ""))
        placeClarify(r, ev.data)
    }

    /**
     * 把澄清卡片挂到该会话。挂载位置按「保守」原则：优先当前进行中的助手气泡
     * （pending），没有就挂最后一条助手消息（重开 App 后本地消息的 pending 已归
     * false），都没有才新建。同一 clarify_id 已挂且未作废的不重复挂。
     */
    private fun placeClarify(r: SessionRuntime, data: org.json.JSONObject) {
        val cid = data.optString("clarify_id", "")
        val q = data.optString("question", "")
        if (cid.isEmpty() || q.isEmpty()) return
        val chs = mutableListOf<String>()
        data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        val cur = r.messages.value
        if (cur.any { it.clarify?.clarifyId == cid && it.clarify?.resolved?.isEmpty() == true }) return
        val card = ClarifyCard(cid, q, chs, data.optBoolean("multi_select", false))
        val list = cur.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(clarify = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), clarify = card))
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
        notifyNeedAction(r.id, "需要你确认",
            ev.data.optString("description", "").ifEmpty { ev.data.optString("command", "") })
        placeApproval(r, ev.data)
    }

    /** 把审批卡片挂到该会话。位置与去重规则同 [placeClarify]。 */
    private fun placeApproval(r: SessionRuntime, data: org.json.JSONObject) {
        val rid = data.optString("request_id", "")
        val cmd = data.optString("command", "")
        val desc = data.optString("description", "")
        val chs = mutableListOf<String>()
        data.optJSONArray("choices")?.let { a ->
            for (i in 0 until a.length()) chs.add(a.optString(i))
        }
        if (rid.isEmpty() || chs.isEmpty()) return
        val cur = r.messages.value
        if (cur.any { it.approval?.requestId == rid && it.approval?.resolved?.isEmpty() == true }) return
        val card = ApprovalCard(rid, cmd, desc, chs)
        val list = cur.toMutableList()
        var i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i < 0) i = list.indexOfLast { it.role == "assistant" }
        if (i >= 0) list[i] = list[i].copy(approval = card)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), approval = card))
        setMsgs(r, list)
    }

    /**
     * 重开 App / 断流恢复：服务端 run 状态里若仍挂着待办卡片（waiting_for_clarify /
     * waiting_for_approval），把卡片重新挂回会话。
     *
     * 这就是「重启后等你点头的卡片消失」的修法：老代码探测 run 只读了 status
     * 字符串，把 run 状态里一并下发的 clarify/approval 载荷丢掉，卡片自然挂不上。
     */
    private fun restorePendingCard(r: SessionRuntime, payload: org.json.JSONObject?) {
        val p = payload ?: return
        when (p.optString("status", "")) {
            "waiting_for_clarify" -> placeClarify(r, p.optJSONObject("clarify") ?: return)
            "waiting_for_approval" -> placeApproval(r, p.optJSONObject("approval") ?: return)
        }
    }

    /**
     * run 已结束或不存在：清掉该会话里未作废的待办卡片（多为重启后从本地落盘读回的
     * 过期卡片）。已作废（用户点过）的保留，让「已选择：…」留痕。
     */
    private fun clearStaleCards(r: SessionRuntime) {
        val list = r.messages.value.toMutableList()
        var changed = false
        for (k in list.indices) {
            val m = list[k]
            if (m.approval?.resolved?.isEmpty() == true) {
                list[k] = m.copy(approval = null); changed = true
            } else if (m.clarify?.resolved?.isEmpty() == true) {
                list[k] = m.copy(clarify = null); changed = true
            }
        }
        if (changed) setMsgs(r, list)
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
        // 耗时起点与界面实时计时同源：优先用会话级的本轮起点 r.startedAt
        // （整轮唯一、不会被气泡重建冲掉），它没有才回落气泡自己的。
        val bubbleStart = r.messages.value.lastOrNull { it.role == "assistant" }?.startedAt ?: 0L
        // 优先用会话级的本轮起点（整轮唯一、不会被气泡重建冲掉）；它没有才回落气泡的。
        val baseStart = if (r.startedAt > 0) r.startedAt else bubbleStart
        val usage = Usage(
            input = u.optInt("input_tokens", 0),
            output = u.optInt("output_tokens", 0),
            total = u.optInt("total_tokens", 0),
            cacheRead = u.optInt("cache_read_tokens", 0),
            cacheWrite = u.optInt("cache_write_tokens", 0),
            durationMs = if (baseStart > 0) System.currentTimeMillis() - baseStart else 0L,
        )
        // 只有耗时（token 全 0）的轮次也要挂上：耗时本身就是用户要看的统计。
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0 && usage.durationMs <= 0) return
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
        AppLog.log("stream", "起流 run=" + rid.take(12) + " lastSeq=" + r.lastSeq + " 第" + r.autoContinue + "次续接")
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
                        AppLog.log("stream", "run 完成 run=" + rid.take(12) + " 正文长度=" + ev.data.optString("output", "").length)
                        r.finished = true
                        val out = ev.data.optString("output", "")
                        if (out.isNotEmpty()) setPendingText(r, out) else finishPending(r)
                        attachUsage(r, ev)
                        doneOk(sid)
                        // 插话没赶上本轮：服务端把未送达的插话文本随终态放在 pending_steer 里下发。
                        // 不静默丢——放回输入框（若为空）并提示，用户点发送即可重发。
                        // 放在 doneOk 之后，避免被它的 retryNote 清空覆盖。
                        val ps = ev.data.optString("pending_steer", "").trim()
                        if (ps.isNotEmpty()) {
                            if (prefs.draftInput.isEmpty()) prefs.draftInput = ps
                            r.retryNote.value = "上一句插话没赶上本轮，已放回输入框，点发送重发"
                        }
                        notifyCompletion(sid, out)
                        // 完成语音：服务端把整段回复合成音频随 output 下发，这里自动播一遍。
                        if (out.isNotEmpty()) {
                            VoicePlayer.playFromReply(getApplication(), out, prefs.playCompletionVoice)
                        }
                    }
                    "run.failed" -> {
                        r.coalescer?.flushNow()
                        AppLog.log("stream", "run 失败 run=" + rid.take(12) + " 错误=" + ev.data.optString("error", "未知").take(120))
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
                AppLog.log("stream", "流关闭 run=" + rid.take(12) + " busy=" + r.busy.value + " finished=" + r.finished + " lastSeq=" + r.lastSeq)
                if (r.busy.value && !r.finished) maybeContinue(sid)
            },
            onError = { e ->
                AppLog.err("stream", "流出错 run=" + rid.take(12) + " busy=" + r.busy.value + " finished=" + r.finished, e)
                // 不再往正文塞「[连接断开]」——断流期间的提示统一走 retryNote（气泡上方一行），
                // 正文只保留任务真实产出，避免一次抖动就在会话里留一条错行。
                r.coalescer?.flushNow()
                if (r.busy.value && !r.finished) {
                    if (r.retryNote.value.isEmpty()) r.retryNote.value = "连接中断：" + (e.message ?: "未知")
                    maybeContinue(sid)
                }
            },
            // 心跳等任何一行都刷新活跃时间：长工具执行期间只有心跳、没有真实事件，
            // 不刷就会让「回到前台」的 25 秒看门狗把健康流误判成假死（用户报的「一直在重连」）。
            onActivity = { r.lastEventAt = System.currentTimeMillis() }
        )
    }

    /**
     * SSE 流提前断开（没收到 run.completed）时的续接：
     * 绝不伪造用户消息——只查同一 run 的状态，仍在跑就续接它的事件流；
     * 已结束就从服务端拉回结果。仅作用于该 run 所属的会话。
     *
     * 重试用退避而非固定间隔：网络抖动（地铁、切基站、网络切换）往往几秒内恢复，
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
        // 同一次断流可能从多个口子进来（onClosed 与 onError 都会调、切回前台的
        // 体检也会间接触发），各起一条退避链就会重复探测、重复加计数，
        // 严重时一边判「还在跑」一边判「已结束」，接流与收尾互相打架。
        // 这里做单飞：同一会话同时只允许一条退避链在等。
        if (!r.probing.compareAndSet(false, true)) return
        val attempt = r.autoContinue + 1
        r.autoContinue = attempt
        val waitMs = backoffDelayMs(attempt)
        r.retryNote.value = "连接中断，${waitMs / 1000} 秒后重试（$attempt/$MAX_RECONNECT_ATTEMPTS）"
        AppLog.log("retry", "第 $attempt/$MAX_RECONNECT_ATTEMPTS 次重试，${waitMs / 1000}s 后探 run=" + rid.take(12))
        viewModelScope.launch(Dispatchers.IO) {
            delay(waitMs)
            // 退出前必须留一行：这条退避链可能已被别的路径（切回前台的体检、
            // 翻历史取回、手动停止）抢先收尾。静默 return 会让日志里只剩
            // 「第 N 次重试」却没有「探测结果」，排查时被误读成重试卡死。
            if (!r.busy.value || r.finished) {
                r.probing.set(false)
                AppLog.log("retry", "退避作废 run=" + rid.take(12) +
                    "（本轮已由其它路径收尾 busy=" + r.busy.value + " finished=" + r.finished + "）")
                return@launch
            }
            val st = a.probeRun(rid)
            r.probing.set(false)   // 探测出结果即释放单飞位，后续该重试还能再进
            AppLog.log("retry", "探测结果 " + when (st) {
                is HermesApi.RunStatus.Known -> "Known(" + st.status + ")"
                HermesApi.RunStatus.Missing -> "Missing(404)"
                HermesApi.RunStatus.Unknown -> "Unknown(探不出来)"
            })
            when (st) {
                // 服务端明确回答「还在跑」：续接同一 run，不新增任何用户消息。
                is HermesApi.RunStatus.Known -> {
                    if (st.status in RUNNING_STATES) {
                        r.retryNote.value = ""
                        // 断流期间服务端可能在等审批/澄清：续接前先把卡片挂回来。
                        restorePendingCard(r, st.payload)
                        streamRun(a, sid)
                    } else {
                        // 服务端明确回答「已结束」：收尾并拉回产出。
                        r.retryNote.value = ""
                        r.finished = true
                        clearStaleCards(r)
                        finishPending(r)
                        r.busy.value = false
                        prefs.removeActiveRun(sid)
                        updateRunService()
                        if (sid == _currentId.value) refreshFromServer()
                    }
                }
                // 服务端明确说没这个 run：判结束（极少见，run 记录被清）。
                HermesApi.RunStatus.Missing -> {
                    r.retryNote.value = ""
                    r.finished = true
                    clearStaleCards(r)
                    finishPending(r)
                    r.busy.value = false
                    prefs.removeActiveRun(sid)
                    updateRunService()
                    if (sid == _currentId.value) refreshFromServer()
                }
                // 探不出来（网还没通）：不知道 ≠ 已结束，继续退避重试。
                // 这是原实现最大的坑——把「不知道」当成了「已结束」，第一次探测
                // 失败就把任务判死，8 次退避等于摆设。
                HermesApi.RunStatus.Unknown -> {
                    if (r.autoContinue >= MAX_RECONNECT_ATTEMPTS) {
                        r.retryNote.value = ""
                        startHistoryRecovery(sid, rid)
                    } else {
                        maybeContinue(sid)
                    }
                }
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
        if (r.confirmingMsgId > 0L) {
            advanceReceipt(sid, r.confirmingMsgId, Receipt.ACCEPTED, note = "")
            r.confirmingMsgId = 0L
        }
        r.finished = true
        r.retryNote.value = ""
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        refreshFromServerFor(sid)
        drainQueue(sid)   // 翻历史取回结果后，队列接着走
    }

    /** 翻历史也没捞到：明确收尾，不留一个永远转圈的空气泡。 */
    private fun giveUpRecovery(sid: String, why: String) {
        val r = rt(sid)
        r.retryNote.value = why
        if (r.confirmingMsgId > 0L) {
            advanceReceipt(sid, r.confirmingMsgId, Receipt.FAILED, note = why)
            r.confirmingMsgId = 0L
        }
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
        // 停止时把待发队列一起按住：不自动发下一条，等用户点「继续」。
        if (r.queue.isNotEmpty()) r.queuePaused.value = true else drainQueue(sid)
    }

    /** 工具轨迹追加到该会话当前 assistant 消息的 trace（界面默认折叠，不进正文）。 */
    private fun appendTrace(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(trace = list[i].trace + d)
        else list.add(Msg("assistant", "", pending = true, ts = stamp(), startedAt = turnStart(r), trace = d))
        setMsgs(r, list)
    }

    private fun appendDelta(r: SessionRuntime, d: String) {
        if (d.isEmpty()) return
        val list = r.messages.value.toMutableList()
        val i = list.indexOfLast { it.role == "assistant" && it.pending }
        if (i >= 0) list[i] = list[i].copy(text = list[i].text + d)
        else list.add(Msg("assistant", d, pending = true, ts = stamp(), startedAt = turnStart(r)))
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
        clearStaleCards(r)
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        drainQueue(sid)   // 本轮失败也要把排队的下一条发出去，别把队列卡死
    }

    private fun doneOk(sid: String) {
        val r = rt(sid)
        r.autoContinue = 0
        r.recoveryJob?.cancel()
        r.retryNote.value = ""
        clearStaleCards(r)
        finishPending(r)
        r.busy.value = false
        r.runId = ""
        prefs.removeActiveRun(sid)
        updateRunService()
        if (sid == _currentId.value) drainPendingReply()
        drainQueue(sid)   // 本轮结束：把排队的下一条发出去
        // 本轮跑完：服务端此刻多半已生成了正式标题，同步一次（节流 5 秒）。
        syncFromServer(5_000L)
    }

    /**
     * 「后台运行」开着就保持前台服务与常驻通知，跟有没有任务在跑无关；关掉才停。
     * 原来判据是「有任务才起、跑完就停」，所以没任务时切后台，状态栏一条通知都不剩。
     */
    private fun updateRunService() {
        val running = runtimes.filterValues { it.busy.value }.keys.toSet()
        _runningIds.value = running
        if (!prefs.keepAlive) {
            RunService.stop(getApplication())
            return
        }
        // Android 12+ 禁止从后台启动前台服务：后台硬启会撞墙，反而触发
        // ForegroundServiceDidNotStartInTime 崩溃。切回前台时 onAppForeground
        // 会再调一次这里把服务补上。
        if (AppForeground.isForeground) RunService.start(getApplication())
    }

    /** 进主界面时调一次：开关开着但还没发过消息，也要把常驻通知挂上。 */
    fun ensureRunService() {
        updateRunService()
    }

    /**
     * 任务停下来等人点头（审批/澄清）且 App 不在前台时弹提醒。
     * 前台不弹——卡片就在屏幕上，再弹通知是骚扰。
     */
    private fun notifyNeedAction(sid: String, title: String, text: String) {
        if (AppForeground.isForeground) return
        // 落盘一份：App 若在用户点通知前被系统杀掉，冷启动的 Intent extra 可能丢，
        // 靠这份落盘仍能跳回那条待处理卡片。
        prefs.pendingOpenSession = sid
        val app = getApplication<Application>()
        val body = text.replace(Regex("\\s+"), " ").trim().let {
            if (it.isEmpty()) "有任务在等你处理" else if (it.length > 120) it.take(120) + "…" else it
        }
        Notifier.notifyAction(app, title, body, sid)
    }

    /**
     * 任务完成提醒。
     * 后台：一直提醒（原行为），前提是「后台运行」开着——否则根本没有连接能收到事件。
     * 前台：只在「别的会话跑完」且用户开了「其它会话完成也提醒」时弹；
     *       当前会话的内容就在屏幕上，再弹是骚扰。
     */
    private fun notifyCompletion(sid: String, output: String) {
        val isCurrent = sid == _currentId.value
        val should = if (!AppForeground.isForeground) prefs.keepAlive
                     else !isCurrent && prefs.notifySessionCompletions
        if (!should) return
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

    /** 设置页「其它会话完成也提醒」：需一条活连接才能观察到别的会话收尾。 */
    fun setNotifySessionCompletions(on: Boolean) {
        prefs.notifySessionCompletions = on
    }

    /** 设置页「完成语音播报」：任务跑完自动播服务端下发的整段语音。 */
    fun setPlayCompletionVoice(on: Boolean) {
        prefs.playCompletionVoice = on
        if (!on) VoicePlayer.stop()
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
                val f = a.downloadApk(info.url, ctx, info.versionName, info.md5) { done, total ->
                    if (total > 0) {
                        val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                        _downloadPct.value = pct
                        _downloadText.value = pct.toString() + "%  " + fmtSize(done) + "/" + fmtSize(total)
                    } else {
                        _downloadText.value = fmtSize(done)
                    }
                }
                if (f == null) {
                    _updateNote.value = "下载失败（安装包校验没过或链路中断，请重试）"
                    AppLog.log("update", "下载/校验失败，未交给安装器")
                    _downloadPct.value = -1
                    return@launch
                }
                _downloadPct.value = 100
                _downloadText.value = "100%"
                _updateNote.value = "下载完成，请在弹出的提示中安装"
                AppLog.log("update", "拉起安装器 " + f.absolutePath)
                launch(Dispatchers.Main) { installApk(ctx, f) }
            } catch (e: Exception) {
                _updateNote.value = "下载失败：" + (e.message ?: "?")
                AppLog.log("update", "下载异常：" + (e.message ?: "?"))
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
