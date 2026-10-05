package com.hermesapp

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
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
import kotlinx.coroutines.launch

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
    accent = Color(0xFF4EA1FF),
    dim = Color(0xFF8A93A6),
    ok = Color(0xFF3FB950),
    warn = Color(0xFFD29922),
    bad = Color(0xFFF85149),
    text = Color(0xFFE6EAF2),
    userBubble = Color(0xFF1E3A5F),
    userText = Color(0xFFE6EAF2),
)

/** 浅色：GitHub Light 系，白天可读 */
val LightColors = AppColors(
    bg = Color(0xFFF6F8FA),
    panel = Color(0xFFFFFFFF),
    card = Color(0xFFEAEEF2),
    accent = Color(0xFF0969DA),
    dim = Color(0xFF5C6670),
    ok = Color(0xFF1A7F37),
    warn = Color(0xFF9A6700),
    bad = Color(0xFFCF222E),
    text = Color(0xFF1F2328),
    userBubble = Color(0xFFDDEBFF),
    userText = Color(0xFF1F2328),
)

val LocalAppColors = staticCompositionLocalOf { DarkColors }

const val MODE_SYSTEM = "system"
const val MODE_DAY = "day"
const val MODE_NIGHT = "night"

fun isDarkMode(mode: String, ctx: Context): Boolean = when (mode) {
    MODE_DAY -> false
    MODE_NIGHT -> true
    else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
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
        val bg = (if (isDarkMode(prefs.themeMode, this)) DarkColors else LightColors).bg.toArgb()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            runCatching { notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }
        Notifier.ensureChannel(this)
        setContent { HermesApp(vm, prefs) }
    }

    override fun onResume() {
        super.onResume()
        AppForeground.isForeground = true
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
        else -> isSystemInDarkTheme()
    }
    val c = if (dark) DarkColors else LightColors

    LaunchedEffect(dark) {
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
    var pwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Hermes", color = c.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("首次使用请填写服务器地址", color = c.dim, fontSize = 13.sp)
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
            value = pwd, onValueChange = { pwd = it; err = "" },
            label = { Text("密码") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            colors = fieldColors(c),
            modifier = Modifier.fillMaxWidth()
        )
        if (err.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(err, color = c.bad, fontSize = 13.sp)
        }
        Spacer(Modifier.height(18.dp))
        OutlinedButton(
            onClick = {
                val u = url.trim()
                when {
                    u.isEmpty() -> err = "请先填写服务器地址"
                    !u.startsWith("http") -> err = "地址需以 http(s):// 开头"
                    pwd != Keys.APP_PASSWORD -> err = "密码错误"
                    else -> { prefs.serverUrl = u; choosing = true }
                }
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("登录", color = c.accent) }

        if (choosing) {
            Spacer(Modifier.height(28.dp))
            Text("选择对话身份", color = c.dim, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProfileBtn("friend", Modifier.weight(1f)) {
                    prefs.profile = "friend"; prefs.loggedIn = true; onDone()
                }
                ProfileBtn("default", Modifier.weight(1f)) {
                    prefs.profile = "default"; prefs.loggedIn = true; onDone()
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("选定后默认保持，除非退出登录", color = c.dim, fontSize = 11.sp)
        }
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
    // 输入框内容提到这里，切到状态/设置再回来不丢；草稿写盘，进程被杀重进也能恢复
    val inputState = remember { mutableStateOf(prefs.draftInput) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(prefs.profile) { vm.onProfileChanged(prefs) }

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
                onTab = { tab = it; scope.launch { drawer.close() } },
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
                        prefs.draftInput = v
                    }
                    1 -> StatusScreen(vm, prefs)
                    else -> SettingsScreen(vm, prefs, mode, onMode, onLogout)
                }
            }
        }
    }
}

@Composable
fun TopBar(vm: ChatViewModel, prefs: Prefs, tab: Int, onMenu: () -> Unit) {
    val c = LocalAppColors.current
    val online by vm.online.collectAsState()
    val title = when (tab) { 0 -> "对话"; 1 -> "状态"; else -> "设置" }
    Row(
        Modifier.fillMaxWidth().background(c.panel).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "☰", fontSize = 20.sp, color = c.accent,
            modifier = Modifier.clickable { onMenu() }.padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(10.dp))
        Text(title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(prefs.profile, color = c.dim, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text(if (online) "● 在线" else "● 离线", color = if (online) c.ok else c.bad, fontSize = 12.sp)
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
    val sessions by vm.sessions.collectAsState()
    val currentId by vm.currentId.collectAsState()
    var showArchived by remember { mutableStateOf(false) }

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
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = { vm.newConversation(); onClose() },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                ) { Text("+ 新对话", fontSize = 12.sp, color = c.accent) }
            }
            Spacer(Modifier.height(10.dp))

            // 页面切换（对话通过点会话/标题进入，不单列按钮）
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NavChip("状态", tab == 1, Modifier.weight(1f)) { onTab(1) }
                NavChip("设置", tab == 2, Modifier.weight(1f)) { onTab(2) }
            }
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
                Text(
                    if (showArchived) "返回" else "已归档",
                    color = c.accent, fontSize = 12.sp,
                    modifier = Modifier.clickable { showArchived = !showArchived }
                )
            }
            Spacer(Modifier.height(6.dp))

            val list = sessions.filter { it.archived == showArchived }
            if (list.isEmpty()) {
                Text(
                    if (showArchived) "（无归档）" else "（无历史对话）",
                    color = c.dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    for (s in list) {
                        SessionRow(
                            meta = s,
                            selected = s.id == currentId && !showArchived,
                            archived = showArchived,
                            onOpen = { vm.switchSession(s.id); onTab(0); onClose() },
                            onArchive = { vm.archiveSession(s.id, !s.archived) },
                            onDelete = { vm.deleteSession(s.id) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = c.card)
            Spacer(Modifier.height(8.dp))

            // 主题切换（三选）
            Text("外观", color = c.dim, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeBtn("跟随系统", mode == MODE_SYSTEM, Modifier.weight(1f)) { onMode(MODE_SYSTEM) }
                ModeBtn("白天", mode == MODE_DAY, Modifier.weight(1f)) { onMode(MODE_DAY) }
                ModeBtn("夜间", mode == MODE_NIGHT, Modifier.weight(1f)) { onMode(MODE_NIGHT) }
            }
        }
    }
}

@Composable
fun NavChip(label: String, selected: Boolean, m: Modifier, onClick: () -> Unit) {
    val c = LocalAppColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = m,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) c.card else Color.Transparent
        ),
        shape = RoundedCornerShape(8.dp),
    ) { Text(label, color = if (selected) c.accent else c.dim, fontSize = 13.sp) }
}

@Composable
fun SessionRow(
    meta: SessionMeta,
    selected: Boolean,
    archived: Boolean,
    onOpen: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalAppColors.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) c.card else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).clickable { onOpen() }) {
            Text(
                meta.title, color = if (selected) c.accent else c.text, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                TimeFmt.mdhm(meta.updatedAt), color = c.dim, fontSize = 10.sp
            )
        }
        Box {
            Text(
                "⋯", color = c.dim, fontSize = 18.sp,
                modifier = Modifier.clickable { menu = true }.padding(horizontal = 6.dp)
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (archived) "恢复" else "归档", fontSize = 13.sp) },
                    onClick = { menu = false; onArchive() }
                )
                DropdownMenuItem(
                    text = { Text("删除", color = c.bad, fontSize = 13.sp) },
                    onClick = { menu = false; onDelete() }
                )
            }
        }
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
