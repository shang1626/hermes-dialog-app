package com.hermesapp

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------- 昼夜配色 ----------
data class AppColors(
    val bg: Color,
    val panel: Color,
    val card: Color,
    val accent: Color,
    val dim: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val text: Color,
    val userBubble: Color,
    val userText: Color,
)

/** 深色：与旧版逐色一致，保证夜间观感零变化 */
val DarkColors = AppColors(
    bg = Color(0xFF0F1115),
    panel = Color(0xFF171A21),
    card = Color(0xFF1E2530),
    accent = Color(0xFF56A5FF),
    dim = Color(0xFF9BA3B3),
    ok = Color(0xFF3FB950),
    warn = Color(0xFFD29922),
    bad = Color(0xFFFA7D77),
    text = Color(0xFFE6EAF2),
    userBubble = Color(0xFF1E3A5F),
    userText = Color(0xFFE6EAF2),
)

/** 浅色：GitHub Light 系，白天可读 */
val LightColors = AppColors(
    bg = Color(0xFFF6F8FA),
    panel = Color(0xFFFFFFFF),
    card = Color(0xFFEAEEF2),
    accent = Color(0xFF0966D4),
    dim = Color(0xFF5C6670),
    ok = Color(0xFF197935),
    warn = Color(0xFF906000),
    bad = Color(0xFFCD222E),
    text = Color(0xFF1F2328),
    userBubble = Color(0xFFDDEBFF),
    userText = Color(0xFF1F2328),
)

/**
 * 护眼：暖米黄纸感底色 + 暖灰文字，压低蓝光与对比度，长时间看不刺眼。
 * 不是纯黑也不是纯白——纯白在暗环境里最累眼，纯黑在大段文字下反差过强。
 */
val EyeColors = AppColors(
    bg = Color(0xFFF4EFE3),
    panel = Color(0xFFFCF9F1),
    card = Color(0xFFE7E1D2),
    accent = Color(0xFF376E61),
    dim = Color(0xFF6A6458),
    ok = Color(0xFF3A703A),
    warn = Color(0xFF835E00),
    bad = Color(0xFFAF3F2D),
    text = Color(0xFF3A3730),
    userBubble = Color(0xFFDCE8DF),
    userText = Color(0xFF2E2B26),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

const val MODE_SYSTEM = "system"
const val MODE_DAY = "day"
const val MODE_NIGHT = "night"
const val MODE_EYE = "eye"

fun isDarkMode(mode: String, ctx: Context): Boolean = when (mode) {
    MODE_DAY -> false
    MODE_NIGHT -> true
    MODE_EYE -> false
    else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
}

/** 主题模式 → 色板（护眼优先判，其次按明暗）。窗口底色与 Compose 侧共用，保证不闪色。 */
fun colorsFor(mode: String, ctx: Context): AppColors = when {
    mode == MODE_EYE -> EyeColors
    isDarkMode(mode, ctx) -> DarkColors
    else -> LightColors
}

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    // Android 13+ 通知权限申请（后台消息提醒用）；拒绝也不影响其他功能
    private val notifPerm = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        // 启动前先定好窗口底色，避免浅色模式下闪一下黑
        val bg = colorsFor(prefs.themeMode, this).bg.toArgb()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        // 只在开启「后台运行」时申请通知权限：关掉后不起前台服务，通知自然不会出现
        if ((prefs.keepAlive || prefs.notifySessionCompletions) && android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        // 把通知授权状态写进运行日志（用户报「任务跑完没通知/没响」时要一眼看出是不是权限没给）
        runCatching {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            AppLog.log("notif", "启动检查 通知权限=" + granted + " 通知总开关=" +
                androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled())
        }
        Notifier.ensureChannel(this)
        setContent { HermesApp(vm, prefs) }
    }

    /**
     * 点击输入框以外的任何位置都收起软键盘。
     *
     * 为什么不只靠 Compose 侧 detectTapGestures：原生 EditText 承载在 AndroidView 里，
     * View 体系本身「点外部不会失焦」；而且消息气泡的 combinedClickable 会先消费掉 tap，
     * 父级 detectTapGestures 根本收不到 → 点气泡收不起键盘（Google issue 282963174 一类互通坑）。
     * dispatchTouchEvent 在任何子 View / Compose 消费之前拿到 ACTION_DOWN，判定落点是否落在
     * 当前聚焦的 EditText 内，不在就清焦点 + 收键盘，一次覆盖点空白 / 气泡 / 顶栏 / 抽屉 / 切 tab。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus
            if (focused is EditText) {
                val r = Rect()
                focused.getGlobalVisibleRect(r)
                if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    focused.clearFocus()
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(focused.windowToken, 0)
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        AppForeground.isForeground = true
        // 回到前台立刻体检一次：息屏/切后台期间流可能已被链路假死卡住，
        // 光靠 30 秒读超时要等很久，这里主动判定一次并重连。
        vm.onAppForeground()
        openFromNotification()
        // 回到前台就清掉「新消息/任务完成」「审批」两条业务通知：用户已经在 App 里了，
        // 那两条横幅是多余的。它们原本只在「点通知」时才自动消失（setAutoCancel 挂在
        // tap 的 PendingIntent 上），所以「直接点图标进 App」时一直挂着（用户报障）。
        // 只清业务通知，后台常驻通知（RunService, id 1001）不受影响。
        Notifier.clearBusinessNotifications(this)
    }

    override fun onStop() {
        super.onStop()
        // 退到后台：把待写的正文/索引与日志队列立刻落盘。
        // 写盘已经全异步化了，进程随时可能被系统回收，这一步保证「最后一段不丢」。
        runCatching { vm.flushSaves() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromNotification()
    }

    /**
     * 审批/澄清通知被点开：切到对应会话，让用户直接看到那张待处理的卡片。
     * 会话 id 有两个来源——Intent extra（App 还活着）与落盘（App 被系统杀过，冷启动时 intent 可能丢）。
     * 取到就清掉，避免每次回前台都重复跳。
     */
    private fun openFromNotification() {
        if (!Prefs(this).loggedIn) return
        val prefs = Prefs(this)
        // ① 目标页：定时任务通知要落在「定时任务」页。只认 Intent extra——PendingIntent
        // 的 Intent 由系统保存，冷启动也照样投递；不做落盘兜底，否则「造通知时写盘」
        // 会让没点通知的用户一开 App 就被顶到定时任务页。
        if (intent?.hasExtra(Notifier.EXTRA_OPEN_TAB) == true) {
            val tab = intent.getIntExtra(Notifier.EXTRA_OPEN_TAB, -1)
            if (tab >= 0) {
                runCatching { intent.removeExtra(Notifier.EXTRA_OPEN_TAB) }
                vm.requestOpenTab(tab)
            }
        }
        // ② 目标会话：审批/澄清/新消息通知。
        val fromIntent = intent?.getStringExtra(Notifier.EXTRA_OPEN_SESSION).orEmpty()
        val sid = fromIntent.ifEmpty { prefs.pendingOpenSession }
        if (sid.isEmpty()) return
        prefs.pendingOpenSession = ""
        runCatching { intent?.removeExtra(Notifier.EXTRA_OPEN_SESSION) }
        vm.switchSession(sid)
    }

    override fun onPause() {
        AppForeground.isForeground = false
        super.onPause()
    }
}

@Composable
fun HermesApp(vm: ChatViewModel, prefs: Prefs) {
    var loggedIn by remember { mutableStateOf(prefs.loggedIn) }
    var mode by remember { mutableStateOf(prefs.themeMode) }
    val ctx = LocalContext.current
    val dark = when (mode) {
        MODE_DAY -> false
        MODE_NIGHT -> true
        MODE_EYE -> false
        else -> isSystemInDarkTheme()
    }
    // 护眼优先：不是 Dark/Light 二分，直接按模式取板
    val c = if (mode == MODE_EYE) EyeColors else if (dark) DarkColors else LightColors

    // 键必须是 c（而不是 dark）：护眼(MODE_EYE)与白天(MODE_DAY)的 dark 同为 false，
    // 用 dark 当键时这两档互切不会重跑，系统栏会停在旧色。c 是 data class，配色一变即触发。
    LaunchedEffect(c) {
        (ctx as? Activity)?.window?.let {
            it.statusBarColor = c.bg.toArgb()
            it.navigationBarColor = c.bg.toArgb()
        }
    }

    val scheme = if (dark) darkColorScheme(
        primary = c.accent,
        onPrimary = Color.White,
        background = c.bg,
        onBackground = c.text,
        surface = c.panel,
        onSurface = c.text,
        surfaceVariant = c.card,
        onSurfaceVariant = c.dim,
        outline = c.dim,
        error = c.bad,
    ) else lightColorScheme(
        primary = c.accent,
        onPrimary = Color.White,
        background = c.bg,
        onBackground = c.text,
        surface = c.panel,
        onSurface = c.text,
        surfaceVariant = c.card,
        onSurfaceVariant = c.dim,
        outline = c.dim,
        error = c.bad,
    )

    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalAppColors provides c) {
            Surface(color = c.bg, modifier = Modifier.fillMaxSize()) {
                when {
                    !loggedIn -> LoginScreen(prefs) {
                        loggedIn = true
                        AppLog.log("ui", "登录完成 profile=" + prefs.profile)
                        vm.onProfileChanged(prefs)
                    }
                    else -> MainScaffold(
                        vm = vm,
                        prefs = prefs,
                        mode = mode,
                        onMode = { mode = it; prefs.themeMode = it },
                        onLogout = { loggedIn = false },
                    )
                }
            }
        }
    }
}

@Composable
fun LoginScreen(prefs: Prefs, onDone: () -> Unit) {
    val c = LocalAppColors.current
    var url by remember { mutableStateOf(prefs.serverUrl) }
    var acct by remember { mutableStateOf("") }
    var pwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 已登录过的账号（凭据存在手机本地）：直接按**账号名**列出，点一下进入。
    // 不写「本人/朋友」——身份由账号本身决定（YOUR_ACCOUNT_A=本人、YOUR_ACCOUNT_B=朋友），界面只认账号。
    val saved = remember {
        listOf("default", "friend").mapNotNull { p ->
            val cred = prefs.credential(p)
            if (cred.isEmpty()) null else p to cred.substringBefore(':')
        }
    }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Hermes", color = c.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            if (saved.isEmpty()) "首次使用请填写服务器地址" else "用账号密码登录",
            color = c.dim, fontSize = 13.sp
        )
        // 已登录过的账号：直接按账号名一键进入，不用重新输（切换身份也走这里）
        if (saved.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                saved.forEach { (p, acct) ->
                    OutlinedButton(
                        onClick = {
                            prefs.profile = p
                            prefs.loggedIn = true
                            AppLog.log("ui", "一键进入已登录账号 account=" + acct)
                            onDone()
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) { Text(acct, color = c.accent, fontSize = 13.sp) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("已登录的账号，点一下直接进入；或用另一个账号登录：", color = c.dim, fontSize = 11.sp)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it; err = "" },
            label = { Text("服务器地址") },
            placeholder = { Text("https://…", color = c.dim) },
            singleLine = true,
            colors = fieldColors(c),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = acct, onValueChange = { acct = it; err = "" },
            label = { Text("账号") },
            singleLine = true,
            colors = fieldColors(c),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = pwd, onValueChange = { pwd = it; err = "" },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors(c),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth()
        )
        if (err.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(err, color = c.bad, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            enabled = !busy,
            onClick = {
                val u = url.trim()
                val a = acct.trim()
                when {
                    u.isEmpty() -> err = "请先填写服务器地址"
                    !u.startsWith("https://") -> err = "地址需以 https:// 开头（明文 http 会泄露账号密码，已禁用）"
                    a.isEmpty() -> err = "请填写账号"
                    pwd.isEmpty() -> err = "请填写密码"
                    else -> {
                        err = ""
                        busy = true
                        scope.launch {
                            val cred = a + ":" + pwd
                            // 账号决定身份（R20）：先按 friend 打一次、再按 default 打一次，
                            // 谁回 200 就是谁。密码只在登录这一刻发给网关，之后凭据存手机本地
                            // —— APK 包里不再编入任何密钥，包泄露不再等于密钥泄露。
                            val res = withContext(Dispatchers.IO) {
                                val f = com.hermesapp.net.HermesApi(u, cred, "/p/friend").checkCredential()
                                if (f == 200) "friend" to 200
                                else {
                                    val d = com.hermesapp.net.HermesApi(u, cred, "").checkCredential()
                                    if (d == 200) "default" to 200 else "" to maxOf(f, d)
                                }
                            }
                            busy = false
                            val who = res.first
                            when {
                                who.isNotEmpty() -> {
                                    prefs.serverUrl = u
                                    prefs.setCredential(who, cred)
                                    prefs.profile = who
                                    prefs.loggedIn = true
                                    AppLog.log("ui", "登录成功 profile=" + who)
                                    onDone()
                                }
                                res.second == 401 -> err = "账号或密码不对"
                                res.second <= 0 -> err = "连不上服务器，检查地址后重试"
                                else -> err = "服务器返回 " + res.second + "，稍后再试"
                            }
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "登录中…" else "登录", color = c.accent) }

        Spacer(Modifier.height(10.dp))
        Text("身份由账号决定：用哪个账号登录就是哪个身份，界面不另设身份选项", color = c.dim, fontSize = 11.sp)
    }
}

@Composable
fun fieldColors(c: AppColors) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = c.text,
    unfocusedTextColor = c.text,
    focusedBorderColor = c.accent,
    unfocusedBorderColor = c.dim,
    cursorColor = c.accent,
    focusedLabelColor = c.accent,
    unfocusedLabelColor = c.dim,
    focusedPlaceholderColor = c.dim,
    unfocusedPlaceholderColor = c.dim,
)

@Composable
fun ProfileBtn(name: String, m: Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    OutlinedButton(onClick = onClick, modifier = m) { Text(name, color = c.accent) }
}

@Composable
fun MainScaffold(
    vm: ChatViewModel,
    prefs: Prefs,
    mode: String,
    onMode: (String) -> Unit,
    onLogout: () -> Unit,
) {
    val c = LocalAppColors.current
    var tab by remember { mutableStateOf(0) }
    // R18：把「聊天页是否真的在眼前」发布出去。用户在设置/状态/任务页时 currentId 仍是同一个，
    // 旧的 AppForeground && sid==currentId 判据会误以为审批卡看得见，从而抑制系统通知。
    LaunchedEffect(tab) { ChatVisibility.chatVisible = (tab == 0) }
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    // 输入框内容提到这里，切到状态/设置再回来不丢；草稿写盘，进程被杀重进也能恢复。
    // ⚠️ 草稿按会话隔离：remember(currentId) 让切会话时重新取该会话自己的草稿。
    // 原来用全局一个 prefs.draftInput，导致「A 会话输入没发出去、切到 B 会话内容还在」。
    val inputState = remember(currentId) { mutableStateOf(prefs.draftFor(currentId)) }
    // 草稿落盘去抖：长文本时每次按键都写盘会反复整串序列化 → 输入一卡一卡。停手 600ms 再写。
    val draftJob = remember { mutableStateOf<Job?>(null) }
    // 待落盘的草稿（会话 id to 文本）：切会话/离开前先冲刷，避免去抖期间切走把草稿丢了。
    val pendingDraft = remember { mutableStateOf<Pair<String, String>?>(null) }
    val flushDraft: () -> Unit = {
        pendingDraft.value?.let { (sid, text) -> prefs.setDraft(sid, text) }
        pendingDraft.value = null
        draftJob.value?.cancel()
    }
    // 会话一变就冲刷上一个会话的草稿，再让 inputState 取新会话自己的草稿。
    LaunchedEffect(currentId) { flushDraft() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(prefs.profile) {
        vm.onProfileChanged(prefs)
        // 进主界面就确保前台服务与常驻通知挂着：开关开着但还没发过消息时，
        // 原来要等第一次发消息或回前台才起，期间切后台状态栏是空的。
        vm.ensureRunService()
    }

    // 通知请求切页（如点「定时任务完成」通知 → 定时任务页）。收到即切并消费掉信号，
    // 避免每次重组重复切页、把用户手动切的页又顶回去。
    val openTabReq by vm.openTabReq.collectAsStateWithLifecycle()
    LaunchedEffect(openTabReq) {
        if (openTabReq < 0) return@LaunchedEffect
        tab = openTabReq
        vm.consumeOpenTab()
    }

    // 返回键/侧滑返回：抽屉开着先收抽屉（不再直接退出软件）；否则先回对话页；已在对话页则交给系统退出
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(enabled = !drawer.isOpen && tab != 0) { tab = 0 }

    ModalNavigationDrawer(
        drawerState = drawer,
        scrimColor = Color.Black.copy(alpha = 0.5f),
        drawerContent = {
            DrawerPanel(
                vm = vm,
                prefs = prefs,
                tab = tab,
                mode = mode,
                onMode = onMode,
                onTab = { tab = it; AppLog.log("ui", "切页 tab=" + it); scope.launch { drawer.close() } },
                onClose = { scope.launch { drawer.close() } },
            )
        }
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                vm = vm,
                prefs = prefs,
                tab = tab,
                onMenu = { scope.launch { drawer.open() } },
            )
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> ChatScreen(vm, prefs, inputState) { v ->
                        inputState.value = v
                        // 去抖写盘：输入过程零磁盘开销；清空（发完消息）立即落盘。
                        // 写到「当前会话」自己的键上，切会话各存各的。
                        val sid = currentId
                        draftJob.value?.cancel()
                        if (v.isEmpty()) {
                            prefs.setDraft(sid, "")
                            pendingDraft.value = null
                        } else {
                            pendingDraft.value = sid to v
                            draftJob.value = scope.launch {
                                delay(600)
                                prefs.setDraft(sid, v)
                                pendingDraft.value = null
                            }
                        }
                    }
                    1 -> StatusScreen(vm, prefs)
                    3 -> JobsScreen(vm, prefs)
                    else -> SettingsScreen(vm, prefs, mode, onMode, onLogout)
                }
            }
        }
    }
}

@Composable
fun TopBar(vm: ChatViewModel, prefs: Prefs, tab: Int, onMenu: () -> Unit) {
    val c = LocalAppColors.current
    val online by vm.online.collectAsStateWithLifecycle()
    val title = when (tab) { 0 -> "对话"; 1 -> "状态"; 3 -> "定时任务"; else -> "设置" }
    // 子任务小标展开状态：默认折叠，点顶栏那个小标才在下面列明细。
    var subOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(c.panel)) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "☰", fontSize = 20.sp, color = c.accent,
            modifier = Modifier.clickable { onMenu() }.padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (tab == 0) {
            // 「子任务 N」挤进这一行（标题右侧）；右侧 Spacer(weight) 顶住，
            // 搜索与在线原位不动，没有子任务时它不出现、顶栏不变。
            Spacer(Modifier.width(8.dp))
            SubagentChip(vm, subOpen) { subOpen = !subOpen }
        }
        Spacer(Modifier.weight(1f))
        // 右侧三项等距：搜索 · 在线圆点 · CPU%。原来各带自己的 padding（6 / 10 / 12dp），
        // 间隔不均匀、看着不齐；统一按 14dp 一个间隔排（用户 2026-10-09 要求重排）。
        if (tab == 0) {
            Text(
                "搜索", color = c.accent, fontSize = 13.sp,
                modifier = Modifier.clickable { vm.toggleSearch() }.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Text("●", color = if (online) c.ok else c.bad, fontSize = 12.sp)
        // 圆点右边：服务器 CPU 使用率，只显百分比、不带文字标签；颜色随负载变
        //（低绿 / 中黄 / 高红）。值取到 -1（还没拉到）时不占位。
        val cpu by vm.cpuPercent.collectAsStateWithLifecycle()
        if (cpu >= 0) {
            Spacer(Modifier.width(14.dp))
            val cpuColor = when {
                cpu >= 80 -> c.bad
                cpu >= 50 -> c.warn
                else -> c.ok
            }
            Text(cpu.toInt().toString() + "%", color = cpuColor, fontSize = 12.sp)
        }
    }
    // 展开的明细挂在那一行下面；收起状态下一行都不渲染。
    if (tab == 0 && subOpen) SubagentList(vm)
    }
}

@Composable
fun DrawerPanel(
    vm: ChatViewModel,
    prefs: Prefs,
    tab: Int,
    mode: String,
    onMode: (String) -> Unit,
    onTab: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val c = LocalAppColors.current
    val ctx = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val currentId by vm.currentId.collectAsStateWithLifecycle()
    val updateBadge by vm.updateBadge.collectAsStateWithLifecycle()
    val runFlags by vm.runFlags.collectAsStateWithLifecycle()
    var showArchived by remember { mutableStateOf(false) }
    // 排序模式：默认关，标题行点「排序」才在每行右侧露出上/下移箭头。
    // 目的是把常驻的排序控件收进一个入口，平时列表干净。
    var sortMode by remember { mutableStateOf(false) }

    ModalDrawerSheet(
        drawerContainerColor = c.panel,
        drawerContentColor = c.text,
        modifier = Modifier.width(300.dp)
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Hermes", color = c.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onTab(0) }
                )
                // 身份标识从对话窗口顶栏挪到这里（顶栏留给标题与状态）。
                Spacer(Modifier.width(6.dp))
                Text(prefs.profile, color = c.dim, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { vm.newConversation(); onClose() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("+ 新对话", fontSize = 12.sp, color = c.accent) }
            }
            Spacer(Modifier.height(10.dp))

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = c.card)
            Spacer(Modifier.height(8.dp))

            // 会话列表标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showArchived) "已归档" else "历史对话",
                    color = c.dim, fontSize = 12.sp
                )
                Spacer(Modifier.weight(1f))
                if (sortMode) {
                    // 排序模式：只剩一个「完成」出口，收起箭头回到常规视图。
                    Text(
                        "完成", color = c.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { sortMode = false }
                    )
                } else {
                    Text(
                        "排序", color = c.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable {
                            if (showArchived) showArchived = false
                            sortMode = true
                        }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "搜索", color = c.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { vm.toggleGlobalSearch() }
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (showArchived) "返回" else "已归档",
                        color = c.accent, fontSize = 12.sp,
                        modifier = Modifier.clickable { showArchived = !showArchived }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            val gActive by vm.globalActive.collectAsStateWithLifecycle()
            val gQuery by vm.globalQuery.collectAsStateWithLifecycle()
            val gHits by vm.globalHits.collectAsStateWithLifecycle()

            if (gActive) {
                // 跨会话搜索：搜本地全部会话，点结果跳到那个会话并定位到该条
                OutlinedTextField(
                    value = gQuery,
                    onValueChange = { vm.setGlobalQuery(it) },
                    singleLine = true,
                    placeholder = { Text("搜索全部会话", fontSize = 13.sp) },
                    colors = fieldColors(c),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        gQuery.isBlank() -> "输入关键词，搜所有会话的正文与工具轨迹"
                        gHits.isEmpty() -> "无结果"
                        else -> "共 " + gHits.size + " 条"
                    },
                    color = c.dim, fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                // LazyColumn：原来整个结果列表一次性铺出来，条目多时每次重组都要全量测量。
                // 惰性化后只渲染看得见的几行。
                LazyColumn(Modifier.weight(1f)) {
                    items(gHits.size) { gi ->
                        val h = gHits[gi]
                        Column(
                            Modifier.fillMaxWidth()
                                .clickable { vm.openGlobalHit(h); onTab(0); onClose() }
                                .padding(horizontal = 6.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    (if (h.role == "user") "我" else "助手") + " · " + h.sessionTitle,
                                    color = c.accent, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(Modifier.weight(1f))
                                Text(TimeFmt.mdhm(h.ts), color = c.dim, fontSize = 10.sp)
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                h.snippet, color = c.text, fontSize = 12.sp,
                                maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                        }
                        HorizontalDivider(color = c.card)
                    }
                }
            } else {
                val list = sessions.filter { it.archived == showArchived }
                if (list.isEmpty()) {
                    Text(
                        if (showArchived) "（无归档）" else "（无历史对话）",
                        color = c.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
                    )
                } else {
                    // LazyColumn + 稳定 key：原来整个会话列表一次性铺出来，切会话会整体
                    // 重组一遍（会话越多越卡）。惰性化后只渲染看得见的行，且 key 稳定时
                    // 只有真正变化的行重组。
                    LazyColumn(Modifier.weight(1f)) {
                        itemsIndexed(list, key = { _, it -> it.id }) { idx, s ->
                            SessionRow(
                                meta = s,
                                selected = s.id == currentId && !showArchived,
                                archived = showArchived,
                                running = (runFlags[s.id]?.busy == true) || (runFlags[s.id]?.remote == true),
                                flag = runFlags[s.id],
                                showSort = sortMode,
                                canUp = idx > 0,
                                canDown = idx < list.size - 1,
                                onMoveUp = { vm.moveSession(s.id, -1) },
                                onMoveDown = { vm.moveSession(s.id, +1) },
                                onOpen = { sortMode = false; vm.switchSession(s.id); onTab(0); onClose() },
                                onArchive = { vm.archiveSession(s.id, !s.archived) },
                                onDelete = { vm.deleteSession(s.id) },
                                onExport = { vm.exportSession(ctx, s.id) },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = c.card)
            Spacer(Modifier.height(8.dp))

            // 页面切换（对话通过点会话/标题进入，不单列按钮）
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavChip("状态", tab == 1, Modifier.weight(1f)) { onTab(1) }
                NavChip("任务", tab == 3, Modifier.weight(1f)) { onTab(3) }
                NavChip("设置", tab == 2, Modifier.weight(1f), badge = updateBadge) { onTab(2) }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = c.card)
            Spacer(Modifier.height(8.dp))

            // 主题切换（三选）
            Text("外观", color = c.dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            // 四档平铺（无下拉）：跟随系统 / 白天 / 夜间 / 护眼
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeBtn("系统", mode == MODE_SYSTEM, Modifier.weight(1f)) { onMode(MODE_SYSTEM) }
                ModeBtn("白天", mode == MODE_DAY, Modifier.weight(1f)) { onMode(MODE_DAY) }
                ModeBtn("夜间", mode == MODE_NIGHT, Modifier.weight(1f)) { onMode(MODE_NIGHT) }
                ModeBtn("护眼", mode == MODE_EYE, Modifier.weight(1f)) { onMode(MODE_EYE) }
            }
        }
    }
}

@Composable
fun NavChip(
    label: String,
    selected: Boolean,
    m: Modifier,
    badge: Boolean = false,
    onClick: () -> Unit,
) {
    val c = LocalAppColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = m,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.card else Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp),
    ) {
        Box {
            Text(label, color = if (selected) c.accent else c.dim, fontSize = 13.sp)
            // 有新版本时：按钮右上角（边框内）一个小绿点
            if (badge) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-5).dp)
                        .size(7.dp)
                        .background(c.ok, CircleShape)
                )
            }
        }
    }
}

@Composable
fun SessionRow(
    meta: SessionMeta,
    selected: Boolean,
    archived: Boolean,
    running: Boolean,
    /** 本会话的运行态摘要：跑着任务 / 子任务 N / 排队 N。null = 没动静。 */
    flag: SessionRunFlag? = null,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit = {},
    /** 是否处于排序模式（决定是否露出上/下移箭头）。 */
    showSort: Boolean = false,
    /** 是否可上/下移（顶行不能上、底行不能下）。 */
    canUp: Boolean = false,
    canDown: Boolean = false,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    val c = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    // 删除会连本地记录一起清掉且不可恢复，手一滑就没了——点删除先弹确认。
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.card else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onOpen() }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val sub = flag?.subagents ?: 0
                val queued = flag?.queued ?: 0
                // 亮点不只代表「本轮在跑」：子任务是后台子代理，可能比父轮次活得久
                // （父 run 结束了它还在干），这种情况也要让用户在列表上看得见。
                if (running || sub > 0 || queued > 0) {
                    Text("●", color = if (running) c.warn else c.accent, fontSize = 9.sp)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    meta.title, color = if (selected) c.accent else c.text, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                val marks = mutableListOf<String>()
                if (flag?.busy == true) marks.add("执行中") else if (flag?.remote == true) marks.add("其它端执行中")
                if (sub > 0) marks.add("子任务 " + sub)
                if (queued > 0) marks.add("排队 " + queued)
                if (marks.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        marks.joinToString(" · "),
                        color = if (running) c.warn else c.accent, fontSize = 10.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                TimeFmt.mdhm(meta.updatedAt), color = c.dim, fontSize = 10.sp
            )
        }
        // 手动排序：仅在排序模式下露出一对迷你箭头（上移/下移）。
        // 默认收起，避免每行常驻一列箭头把列表搅得很吵。顶行▲、底行▼ 置灰。
        if (showSort) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.KeyboardArrowUp, contentDescription = "上移",
                    tint = if (canUp) c.accent else c.dim.copy(alpha = 0.25f),
                    modifier = Modifier.size(20.dp).clickable(enabled = canUp) { onMoveUp() }
                )
                Icon(
                    Icons.Rounded.KeyboardArrowDown, contentDescription = "下移",
                    tint = if (canDown) c.accent else c.dim.copy(alpha = 0.25f),
                    modifier = Modifier.size(20.dp).clickable(enabled = canDown) { onMoveDown() }
                )
            }
        }
        Box {
            Text(
                "⋯", color = c.dim, fontSize = 18.sp,
                modifier = Modifier.clickable { menu = true }.padding(horizontal = 6.dp)
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("导出为 Markdown", fontSize = 13.sp) },
                    onClick = { menu = false; onExport() }
                )
                DropdownMenuItem(
                    text = { Text(if (archived) "恢复" else "归档", fontSize = 13.sp) },
                    onClick = { menu = false; onArchive() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = c.bad, fontSize = 13.sp) },
                    onClick = { menu = false; confirmDelete = true; AppLog.log("ui", "点删除(待确认) sid=" + meta.id.take(8)) }
                )
            }
        }
    }
    // 删除前二次确认：删除会把本地记录一并清掉，删了就找不回来。
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除这个对话？", color = c.text, fontSize = 15.sp) },
            text = { Text("本地记录会一并清掉，删了就找不回来了。", color = c.dim, fontSize = 12.sp) },
            confirmButton = {
                Text(
                    "删除", color = c.bad, fontSize = 14.sp,
                    modifier = Modifier.clickable { confirmDelete = false; onDelete() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            },
            dismissButton = {
                Text(
                    "取消", color = c.dim, fontSize = 14.sp,
                    modifier = Modifier.clickable { confirmDelete = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            },
            containerColor = c.panel,
        )
    }
}

@Composable
fun ModeBtn(label: String, selected: Boolean, m: Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = m,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.card else Color.Transparent
        ),
    ) { Text(label, color = if (selected) c.accent else c.dim, fontSize = 12.sp) }
}

/** 语速档位小按钮：描边、选中填充，风格同 ModeBtn，但不吃 Modifier（设置页平铺一排）。 */
@Composable
fun SpeedBtn(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalAppColors.current
    OutlinedButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.card else Color.Transparent
        ),
    ) { Text(label, color = if (selected) c.accent else c.dim, fontSize = 12.sp) }
}

/**
 * 进程入口：第一时间装上崩溃留痕。
 *
 * 放在 Application 而不是 MainActivity——闪退可能发生在界面起来之前，
 * 挂在这里才能保证任何阶段的崩溃都留下堆栈。
 */
class HermesApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // 运行日志：连接/重连/发送/收流的关键节点留痕，设置页可查看与复制。
        AppLog.install(this)
    }
}
