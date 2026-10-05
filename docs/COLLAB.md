# 协作规范（两个人 / 两个 Hermes 号）

两个号在同一台机器、同一个文件系统上，共用同一个工作副本 `~/hermes-app`。
共享裸仓库：`~/hermes-app-shared.git`（已作为 remote `shared` 加好）。

## 铁律

1. **动手前先 pull**：`git pull shared master`。裸仓库是共享真相，工作副本可能被别人改过。
2. **一次只做一件事**：一个提交一个主题，别把不相关的改动混在一起。
3. **改代码前先看 `docs/DESIGN.md`**，改完同步更新文档（设计变了就改 DESIGN，功能变了就写 CHANGELOG）。
4. **发布只有一个人做**：谁发版谁改 `versionCode` / `versionName` / `version.json`，另一个人不要并发改这三个字段 —— 两个号各发各的版本号打架是最容易出的岔子。
5. **版本号只增不减**，跟 version.json 必须一致。

## 日常流程

```bash
cd ~/hermes-app
git pull shared master                 # 1. 同步
# ...改代码...
./build.sh                              # 2. 本地编译必须过（没有 gradlew！）
git add -A
git commit -m "v2.9 (versionCode 20): 一句话说清改了什么"
git push shared master                 # 3. 推回共享仓库
```

## 提交信息格式

```
v<版本> (versionCode <码>): <做了什么>
```

一句话，动词开头，说清「改了什么、为什么」，别写「update」「fix bug」。

## 分支

日常直接推 `master`（自用项目，人少）。
要做有风险的改动（重构、换依赖、动发布链路）时开分支，验证通过再合：

```bash
git checkout -b feat/xxx
# ...改...
git push shared feat/xxx
# 合回：git checkout master && git merge feat/xxx && git push shared master
```

## 冲突处理

同时改了同一文件：先 `git pull shared master`，有冲突就手工合，别用 `-X ours/theirs` 糊过去。
合完必须重新 `./gradlew assembleDebug` 确认能编。

## 发布检查单

- [ ] `./build.sh` 出 `BUILD SUCCESSFUL` / `EXIT=0`
- [ ] `versionCode` 递增且与 `version.json` 一致
- [ ] APK 已复制进 `dist/update/`，`version.json` 的 size / md5 与实际文件一致
- [ ] `notes` 写清了这一版的变化
- [ ] 改动已 commit 并 push 到 shared
- [ ] 手机端实测：能更新、能登录、能收发消息

## 回滚

```bash
git log --oneline            # 找到上一个好的提交
git revert <hash>            # 或 git reset --hard <hash>（确认没别人依赖当前状态时）
```

APK 回滚：把 `version.json` 指回旧 APK（旧包都留在 `dist/update/`，不会自动删）。