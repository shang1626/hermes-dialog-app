# 手动恢复服务端独有会话审计

## 现状证据（修改前）

- `ChatViewModel.syncFromServer` 调用已有 `HermesApi.listSessions()`，但本地索引中找不到 ID 时明确 `continue`，只同步已有行标题，不自动补行。这符合用户 2026-10-07 的“不自动拉入全部历史”约定。
- `DrawerPanel` 原来只有新建、排序、搜索、归档、清空入口，没有服务端独有会话的选择恢复入口。
- `switchSession` 已调用 `ensureLoaded` 和 `refreshFromServerFor`；后者使用 `mergeByUserAnchor` 合并消息，不覆盖本地消息载荷。此次复用这条打开即加载链路，没有另写消息解析/合并。

## 真实协议与边界

核对运行中安装源码 `/usr/local/lib/hermes-agent/gateway/platforms/api_server.py`：

- `_handle_list_sessions`：`GET /api/sessions` 接受 limit（最大 200）、offset、compact 等参数，响应包含 data/limit/offset/has_more；默认隐藏内部子会话，compact 行仍包含 is_internal_child。固定会话可能额外回填，因此 200 是查询窗口而非严格响应条数上限。
- App 当前 `HermesApi.listSessions(limit=200, compact=true)` 没有 offset 参数；本次保持该 API 不变，只查一次窗口，不自动翻页、不宣称全部服务端历史。
- `_handle_session_messages`：不传 limit 时默认为最近 500 条，最大 limit 也是 500，支持 offset/order，返回 pagination。现有 App `sessionMessages()` 不分页，名字“整份”不代表完整服务端历史。此批不改变该既有行为。
- 没有凭据泄露或服务端数据修改；未发送真实聊天、未修改/重启网关。

## 实现

- 抽屉新增“从服务器选择恢复…”；仅用户点击时查询远端。
- 弹窗只列远端独有 ID（本地已归档也算已有）、排除空 ID/内部子会话、去重，多选且默认全不选。失败可重试、取消不动索引，空列表明确说明只代表本次窗口。
- 确认仅追加选中行，不改本地原有行标题、归档、手动排序键，不清理本地/服务端消息；恢复列表后打开会话才经现有加载合并逻辑读取消息。
- 生产筛选/合并纯逻辑集中于 `HistoryRestore`，测试直接调用同一函数。
- 请求捕获 api/store，在身份绑定 identityScope 内运行。回写与恢复确认验证 detached=false、全局当前 identity 与 VM identity 相等、已登录、api/store 引用未更换、请求代次仍有效。取消作废请求与候选有效性闭包。身份退出/切换 scope 取消由身份代理实现；本批不改其生命周期。
- 索引保存复用 identityScope 内 `saveIndexAsync`；不自行开跨身份写盘任务。

## 验证

- `:app:testReleaseUnitTest --tests com.hermesapp.HistoryRestoreTest --console=plain`：BUILD SUCCESSFUL，4 tests，0 failures，0 errors（2026-10-22）。覆盖本地含归档排除、空 ID、重复远端、空选择、只选有效项、保留全部本地字段顺序、重复恢复幂等、空返回不删除。
- `lintRelease` 最新报告中 Screens.kt 无 Error；已按父代理要求把版本读取改为 `PackageInfoCompat.getLongVersionCode`（兼容 minSdk26）。旧报告中的 Screens SuspiciousIndentation 未在本次真实重跑复现，未按过时行号盲改或添加 Suppress。
- 全仓 lint 当时仍有其他模块 Error（例如 Notifier MissingPermission），交父代理统一处理。
- release APK 构建、全仓最终测试和 lint 由父代理统一执行；未做真机交互验证、未发布、未提交、未 push、未改版本号。

## 精确修改触点

- `App.kt`：仅 DrawerPanel 的历史状态收集/弹窗调用与恢复按钮。
- `Screens.kt`：新增 HistoryRestoreDialog；SettingsScreen 的版本号读取兼容修复。
- `ChatViewModel.kt`：仅 prewarmRecentSessions 与 lastTitleSyncAt 之间插入手动恢复 state/functions；未更改登录/退出/身份初始化。
- 新增 `HistoryRestore.kt`、`HistoryRestoreTest.kt`、本审计文档。
