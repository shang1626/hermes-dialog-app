# 变更记录

按 versionCode 递增。发布时同步更新 `dist/update/version.json` 的 notes 字段。
（versionCode 12 / 14 未产生提交，编号有跳档属正常。）

## 2.8 — versionCode 19
新增应用图标：Hermes 原图标，传统 PNG + adaptive icon 全密度，含 round 版。

## 2.7 — versionCode 18
修自动续跑伪造用户消息：连接中断改为查同 run 状态后续接流 / 拉回结果，不再塞「继续」。
（ChatViewModel.kt）

## 2.6 — versionCode 17
修输入长文本卡顿：草稿去抖落盘、输入框配色只在变更时重建、TextWatcher 用 rememberUpdatedState。

## 2.5 — versionCode 16
输入框图标改单色描边；设置新增「清理缓存」；对话正文字号 14→16。

## 2.4 — versionCode 15
Markdown 表格渲染 + 链接可点；白天模式输入框白字修复；后台运行开关（关掉即无常驻通知）。

## 2.2 — versionCode 13
常驻通知降噪：IMPORTANCE_MIN 静默频道，无正文 / 无提示音 / 无震动。

## 2.0 — versionCode 11
输入框 2–8 行；过程折叠；全屏不截断；图片输入（上传 artifact + 气泡回显）；
重开恢复 run 状态；全屏输入 fill。

## 1.9 — versionCode 10
输入栏对齐；点空白收键盘；状态页细化；草稿持久化；全屏返回；输入卡顿优化；列表 key 稳定。

## 1.8 — versionCode 9
原生 EditText 输入修输入法符号；外观固定底部；空态新建会话；侧滑返回收抽屉；
重开拉取会话；移除设置里的对话身份项。

## 1.7 — versionCode 8（baseline）
中文标点修复；点空白收键盘；发送按钮缩小；服务器地址必填；侧滑返回；后台任务常驻。