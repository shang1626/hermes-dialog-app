package com.hermesapp

/**
 * 敏感权限审批「说明」的中文化（**纯展示层**）。
 *
 * 为什么不改服务端：服务端那些英文说明（如 `recursive delete`）本身就是 **pattern_key**——
 * 会被写进会话级/永久批准记录（`approvals.allow`、`_persist_choice`），改文案等于改批准键，
 * 老记录全部失效。所以只在 App 显示这一层翻译，模型、回执、落盘一律保留原文。
 *
 * 词表来源：服务端 `tools/approval_detection.py` 的 HARDLINE_PATTERNS + DANGEROUS_PATTERNS
 * （115 条，2026-10-12 对账）。上游以后新增模式时，App 会原样显示英文——不会崩、不会空；
 * 需要补译时用同一张表对照补进 [APPROVAL_DESC_ZH] 即可。
 *
 * 另有几条「组合句」（带动态路径/动作名）走 [approvalDescZh] 里的正则模板，动态部分原样保留。
 */
val APPROVAL_DESC_ZH: Map<String, String> = mapOf(
    "PowerShell destructive delete (Remove-Item)" to "PowerShell 破坏性删除（Remove-Item）",
    "PowerShell encoded command execution" to "PowerShell 编码命令执行",
    "SQL DELETE without WHERE" to "SQL DELETE 没有 WHERE 条件",
    "SQL DROP" to "SQL DROP（删表/删库）",
    "SQL TRUNCATE" to "SQL TRUNCATE（清空表）",
    "Windows PowerShell destructive delete" to "Windows PowerShell 破坏性删除",
    "Windows cmd destructive delete" to "Windows cmd 破坏性删除",
    "Windows destructive delete (recursive/quiet switch)" to "Windows 破坏性删除（递归/静默开关）",
    "access to Hermes secrets (Windows path)" to "读取 Hermes 密钥/配置（Windows 路径）",
    "access to SSH keys (Windows path)" to "读取 SSH 密钥（Windows 路径）",
    "chmod +x followed by immediate execution" to "先 chmod +x 再立刻执行",
    "cloud metadata endpoint access (instance credentials)" to "访问云元数据端点（可能拿到实例凭证）",
    "copy/move file into sensitive credential/SSH/shell-rc path" to "把文件复制/移动到敏感凭证、SSH 或 shell 配置路径",
    "copy/move file into system config path" to "把文件复制/移动到系统配置路径",
    "dd to raw block device" to "dd 直接写入裸块设备",
    "delete backups (wbadmin)" to "删除备份（wbadmin）",
    "delete in root path" to "在根路径下删除",
    "delete volume shadow copies (vssadmin)" to "删除卷影副本（vssadmin）",
    "disk copy" to "磁盘复制（dd）",
    "disk partitioning (diskpart)" to "磁盘分区操作（diskpart）",
    "docker compose restart/stop/kill/down (container lifecycle)" to "docker compose 重启/停止/结束容器",
    "docker context use (switches default daemon for future commands)" to "切换 docker context（改变后续命令的默认守护进程）",
    "docker restart/stop/kill (container lifecycle)" to "docker 重启/停止/杀死容器",
    "docker with daemon redirect (--context: alternate daemon)" to "docker 指向别的 context（连到另一个守护进程）",
    "docker with remote daemon redirect (-H/--host)" to "docker 连到远端守护进程（-H/--host）",
    "docker/podman daemon redirect via environment (DOCKER_HOST/CONTAINER_HOST)" to
        "用环境变量把 docker/podman 指向别的守护进程（DOCKER_HOST/CONTAINER_HOST）",
    "dynamic shell word may expand to arbitrary program execution flag" to
        "命令里有会动态展开的 shell 词，可能变成任意程序执行参数",
    "execute remote content via Invoke-Expression" to "用 Invoke-Expression 执行远端内容",
    "execute remote content via command substitution" to "用命令替换执行远端内容",
    "execute remote script via process substitution" to "用进程替换执行远端脚本",
    "find -delete" to "find -delete（按条件删文件）",
    "find -exec/-execdir rm" to "find 调用 rm 删文件",
    "find dynamic shell word may expand to destructive flag" to "find 命令里有动态展开的词，可能变成破坏性参数",
    "force kill processes" to "强制杀死进程",
    "force kill processes (Stop-Process -Force)" to "强制杀死进程（Stop-Process -Force）",
    "force kill processes (killall -KILL)" to "强制杀死进程（killall -KILL）",
    "force kill processes (killall -s KILL)" to "强制杀死进程（killall -s KILL）",
    "force kill processes (taskkill /F)" to "强制杀死进程（taskkill /F）",
    "force stop service (Stop-Service -Force)" to "强制停止服务（Stop-Service -Force）",
    "fork bomb" to "fork 炸弹（会耗尽系统资源）",
    "format drive (format.com)" to "格式化磁盘（format.com）",
    "format filesystem" to "格式化文件系统",
    "format filesystem (Format-Volume)" to "格式化文件系统（Format-Volume）",
    "format filesystem (mkfs)" to "格式化文件系统（mkfs）",
    "git branch force delete" to "强制删除 git 分支",
    "git branch force delete (long flags)" to "强制删除 git 分支（长参数）",
    "git branch force delete (long flags, force-first)" to "强制删除 git 分支（长参数，force 在前）",
    "git clean with force (deletes untracked files)" to "git clean -f（删掉未跟踪的文件）",
    "git force push (rewrites remote history)" to "git 强制推送（会改写远端历史）",
    "git force push short flag (rewrites remote history)" to "git 强制推送（短参数，会改写远端历史）",
    "git reset --hard (destroys uncommitted changes)" to "git reset --hard（丢弃未提交的改动）",
    "grant Everyone access (icacls)" to "给 Everyone 授权（icacls）",
    "hermes update (restarts gateway, kills running agents)" to "hermes update（会重启网关、中断正在跑的 agent）",
    "in-place edit of Hermes config/env" to "就地修改 Hermes 配置/环境文件",
    "in-place edit of Hermes config/env (long flag)" to "就地修改 Hermes 配置/环境文件（长参数）",
    "in-place edit of Hermes config/env (perl/ruby)" to "就地修改 Hermes 配置/环境文件（perl/ruby）",
    "in-place edit of sensitive credential/SSH/shell-rc path" to "就地修改敏感凭证、SSH 或 shell 配置文件",
    "in-place edit of sensitive credential/SSH/shell-rc path (long flag)" to
        "就地修改敏感凭证、SSH 或 shell 配置文件（长参数）",
    "in-place edit of sensitive credential/SSH/shell-rc path (perl/ruby)" to
        "就地修改敏感凭证、SSH 或 shell 配置文件（perl/ruby）",
    "in-place edit of system config" to "就地修改系统配置",
    "in-place edit of system config (long flag)" to "就地修改系统配置（长参数）",
    "init 0/6 (shutdown/reboot)" to "init 0/6（关机或重启）",
    "invisible or bidirectional Unicode control character (possible obfuscation)" to
        "含不可见或双向 Unicode 控制字符（可能在伪装命令）",
    "kill all processes" to "杀死全部进程",
    "kill hermes/gateway process (self-termination)" to "杀死 hermes/网关进程（会把自己弄停）",
    "kill process via backtick pgrep/pidof expansion (self-termination)" to
        "用反引号 pgrep/pidof 展开杀进程（会把自己弄停）",
    "kill process via pgrep/pidof expansion (self-termination)" to
        "用 pgrep/pidof 展开杀进程（会把自己弄停）",
    "kill processes by regex (killall -r)" to "按正则批量杀进程（killall -r）",
    "modify boot configuration (bcdedit /set)" to "修改启动配置（bcdedit /set）",
    "overwrite project env/config file" to "覆盖项目 env/配置文件",
    "overwrite project env/config via redirection" to "用重定向覆盖项目 env/配置文件",
    "overwrite project env/config via tee" to "用 tee 覆盖项目 env/配置文件",
    "overwrite system config" to "覆盖系统配置",
    "overwrite system file via redirection" to "用重定向覆盖系统文件",
    "overwrite system file via tee" to "用 tee 覆盖系统文件",
    "package manager uninstall" to "用包管理器卸载软件",
    "pipe a credential file into a curl/wget upload (possible exfiltration)" to
        "把凭证文件通过 curl/wget 上传（可能在往外传密钥）",
    "pipe decoded content to shell (possible command obfuscation)" to "把解码后的内容管道给 shell（可能在伪装命令）",
    "pipe openssl-decoded content to shell (possible command obfuscation)" to
        "把 openssl 解码后的内容管道给 shell（可能在伪装命令）",
    "pipe remote content to PowerShell (iwr | iex)" to "把远端内容管道给 PowerShell（iwr | iex）",
    "pipe remote content to shell" to "把远端内容管道给 shell 执行",
    "pipe tr-transformed output to shell (possible command obfuscation)" to
        "把 tr 变换后的输出管道给 shell（可能在伪装命令）",
    "pipe xxd-decoded content to shell (possible command obfuscation)" to
        "把 xxd 解码后的内容管道给 shell（可能在伪装命令）",
    "podman remote mode (-r/--remote: remote daemon)" to "podman 远端模式（-r/--remote）",
    "podman with remote daemon redirect (--url/--connection/--identity)" to
        "podman 连到远端守护进程（--url/--connection/--identity）",
    "recursive chown to root" to "递归把属主改成 root",
    "recursive chown to root (long flag)" to "递归把属主改成 root（长参数）",
    "recursive delete" to "递归删除",
    "recursive delete (flags after operands)" to "递归删除（参数写在操作数之后）",
    "recursive delete (long flag)" to "递归删除（长参数）",
    "recursive delete of home directory" to "递归删除家目录",
    "recursive delete of root filesystem" to "递归删除根文件系统",
    "recursive delete of system directory" to "递归删除系统目录",
    "recursive world/other-writable (long flag)" to "递归放开全世界可写权限（长参数）",
    "redirect to raw block device" to "重定向写入裸块设备",
    "registry delete (reg delete)" to "删除注册表项（reg delete）",
    "registry value delete (Remove-ItemProperty -Force)" to "删除注册表值（Remove-ItemProperty -Force）",
    "reset ACLs recursively (icacls /reset)" to "递归重置 ACL 权限（icacls /reset）",
    "shell execution via heredoc" to "用 heredoc 执行 shell",
    "start gateway outside systemd (use 'systemctl --user restart hermes-gateway')" to
        "绕过 systemd 直接起网关（应改用 systemctl --user restart hermes-gateway）",
    "stop/delete service (sc)" to "停止/删除系统服务（sc）",
    "stop/restart hermes gateway (kills running agents)" to "停止/重启 hermes 网关（会中断正在跑的 agent）",
    "stop/restart hermes launchd service (kills running agents)" to "停止/重启 hermes launchd 服务（会中断正在跑的 agent）",
    "stop/restart system service" to "停止/重启系统服务",
    "sudo with combined-flag privilege escalation" to "用组合参数 sudo 提权",
    "sudo with privilege flag (stdin/askpass/shell/list)" to "带提权参数的 sudo（stdin/askpass/shell/list）",
    "system shutdown/reboot" to "关机或重启系统",
    "systemctl poweroff/reboot" to "systemctl 关机或重启",
    "telinit 0/6 (shutdown/reboot)" to "telinit 0/6（关机或重启）",
    "upload a secret or credential file via curl/wget (possible exfiltration)" to
        "用 curl/wget 上传密钥或凭证文件（可能在往外传）",
    "wipe disk (Clear-Disk)" to "擦除磁盘（Clear-Disk）",
    "wipe free space (cipher /w)" to "擦除磁盘空闲空间（cipher /w）",
    "world/other-writable permissions" to "放开全世界可写权限",
    "write to block device" to "写入块设备",
    "xargs with rm" to "xargs 调用 rm 删除",
)

/**
 * 组合句：`Write to SSH client config file(s): <路径>. ...`（写入面来自 file_tools_write_guards）。
 * 结尾的 `\.\s` 很关键：路径里本身带点（`/root/.ssh/config`），只写 `\.` 会懒匹配到 `.ssh` 那个点。
 */
private val RE_SSH_CONFIG_WRITE =
    Regex("^Write to SSH client config file\\(s\\):\\s*(.+?)\\.\\s", RegexOption.DOT_MATCHES_ALL)

/** 组合句：`Write to protected agent-instruction file(s): <路径>. ...`（如 AGENTS.md / SKILL.md）。 */
private val RE_PROTECTED_INSTR_WRITE =
    Regex("^Write to protected agent-instruction file\\(s\\):\\s*(.+?)\\.\\s", RegexOption.DOT_MATCHES_ALL)

/** 组合句：`Allow computer_use to perform \\`<动作>\\`?`。 */
private val RE_COMPUTER_USE =
    Regex("^Allow computer_use to perform `(.+?)`\\?")

/**
 * 把服务端给的审批说明翻成中文；**未收录的原文原样返回**（插件自定义规则、MCP 自述等）。
 * 只做展示，任何情况下都不改变回执内容。
 */
fun approvalDescZh(desc: String): String {
    val d = desc.trim()
    if (d.isEmpty()) return d
    APPROVAL_DESC_ZH[d]?.let { return it }
    RE_SSH_CONFIG_WRITE.find(d)?.let {
        return "写入 SSH 客户端配置文件：" + it.groupValues[1] +
            "。SSH 配置可携带 ProxyCommand / Match exec 之类的指令去执行命令，所以写入需要你批准。"
    }
    RE_PROTECTED_INSTR_WRITE.find(d)?.let {
        return "写入受保护的 agent 指令文件：" + it.groupValues[1] +
            "。这类文件会左右 agent 之后的行为，因此每次都必须批准（自动批准也绕不过去）。"
    }
    RE_COMPUTER_USE.find(d)?.let { return "允许 computer_use 执行「" + it.groupValues[1] + "」？" }
    return d
}
