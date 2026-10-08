# Hermes Dialog App

[中文](README.md) | **English**

A self-hosted Android client for your own [Hermes Agent](https://github.com/NousResearch/hermes-agent) gateway — chat with your AI assistant from your phone.

Pure Android project, **no server code** — the server side is just the `api_server` platform that ships with the Hermes gateway. You do not need to write a backend.

> This repository is a sanitized, generic version: it contains no real keys, passwords, domains, or internal addresses.
> Fill in your own server address and keys in `local.properties` (see [Configuration](#configuration)).

---

## Contents

- [What this app does](#what-this-app-does)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Project layout](#project-layout)
- [Quick start](#quick-start)
- [Configuration](#configuration)
- [Build](#build)
- [Signing](#signing)
- [Server preparation](#server-preparation)
- [Server API contract](#server-api-contract)
- [Release and update flow](#release-and-update-flow)
- [Troubleshooting](#troubleshooting)
- [Security notes](#security-notes)
- [License](#license)

---

## What this app does

### Chat

- **Streaming replies** over SSE — text appears as it is generated, no waiting for the whole answer
- **Multiple sessions**: create, switch, rename, delete; the list is sorted by recent activity
- **Search** across past sessions and messages
- **Steering**: send another message mid-run to change direction
- **Stop a running task** at any time

### Two profiles

- Switch between two different Hermes bots (profiles, e.g. `default` and `friend`)
- Each profile has its own API key, its own session list, and its own local storage directory
- Pick the profile on the login screen; every request after that carries the matching key

### Task visibility

- **Live usage stats** per turn: token counts (input / cache read / cache write / output / total), elapsed time, and speed (tok/s)
- **Sub-task progress** for multi-step runs, expandable into the step-by-step trace (step number + tool name + arguments + result summary)
- **Tool trace** collapsed inside the bubble by default
- **Status screen** built from the gateway's `/health/sysinfo`: gateway state, CPU, memory, disk, load, uptime, today's API calls, active runs / sub-agents, queue depth, last heartbeat — auto-refreshed every 5 seconds

### Multimodal

- **Send images** via the system Photo Picker (no storage permission), up to 5 files, 10 MB each
- **Receive files and images**: `MEDIA:` content from the server renders as an image or an attachment card, tappable to save and open
- **Capability probing**: asks the server whether it supports vision, and falls back to a text description policy if not

### Voice

- **Completion announcement**: the app speaks when a task finishes (via server-side TTS)
- **Streaming playback**: voice is synthesized and played as it arrives; past messages can be replayed; exactly one voice button per message, playback is mutually exclusive

### Other

- **In-app updates**: check → download APK with progress → verify MD5 → hand off to the system installer
- **Local persistence**: sessions, messages, and usage data are all stored on device and survive restarts; the most recent 300 messages per session are kept
- **Background switch**: when on, a foreground service keeps the connection alive and replies arrive as notifications; when off there are no notifications at all, the task still runs server-side, and reopening the app pulls the result
- **Themes**: follow system / force light / force dark
- **Crash log**: built-in crash capture for your own debugging

---

## Architecture

Single Activity + Jetpack Compose, all state in one ViewModel, one networking class, two local-persistence classes.

```
MainActivity (App.kt)
  └─ HermesApp : theme / day-night colors → LoginScreen if not signed in, MainScaffold otherwise
       └─ MainScaffold : drawer + top bar + three pages
            ├─ ChatScreen    ← ChatViewModel (messages, SSE stream, run lifecycle)
            ├─ StatusScreen  ← /health/* (status cards)
            └─ SettingsScreen (theme, server, keys, cache cleanup, ...)
```

Core data flow (one turn = one run; all inference happens server-side):

```
POST /v1/runs  ──►  run_id  ──►  GET /v1/runs/{id}/events (SSE)
                                      │
                                      ├─ message.delta    → append to current bubble
                                      ├─ tool.completed   → append a tool-trace line
                                      └─ run.completed    → finalize text, wrap up
```

When the app is killed and reopened, it looks up the persisted `activeRunId` on the server: still running → reattach to the event stream; finished → pull the output back.

---

## Tech stack

| Item | Notes |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose (Material3) |
| Networking | OkHttp 4.12 (REST + hand-written SSE parsing) |
| Images | Coil 2.6 |
| Local storage | SharedPreferences (settings) + JSON in the app-private directory (sessions) |
| Minimum | minSdk 26 (Android 8.0) |
| Target | targetSdk 34 / compileSdk 34 |
| JDK | 17+ (verified with JDK 21) |
| Build | Gradle 8.9 + Android Gradle Plugin 8.5.2 + Kotlin 1.9.24 |

Server requirement: a Hermes gateway (with the built-in `api_server` platform), listening on `127.0.0.1:8642` by default.

---

## Project layout

```
app/src/main/java/com/hermesapp/    Kotlin sources
  App.kt                            theme / colors / MainActivity / login / MainScaffold
  Screens.kt                        Compose UI (chat, message list, bubbles, status, settings)
  ChatViewModel.kt                  core: session state, run lifecycle, SSE parsing, messages
  net/HermesApi.kt                  HTTP / SSE layer
  Markdown.kt                       lightweight Markdown rendering (blocks, inline links, tables)
  SessionStore.kt                   local session persistence (JSON)
  Prefs.kt                          SharedPreferences wrapper
  Keys.kt                           build-time injected config (no real values in source)
  RunService.kt                     foreground service (keeps SSE alive during a run)
  Notifier.kt                       notification channels and foreground flag
  VoicePlayer.kt                    voice playback (streaming / replay)
  StreamVoicePlayer.kt              streaming voice player
  VoiceReplayPlayer.kt              replay of past voice messages
  Attachment.kt                     attachment / data URL decoding
  CacheUtil.kt                      cache stats and cleanup
  CrashLog.kt                       crash capture
  AppLog.kt / TimeFmt.kt / ...      logging and formatting helpers
app/src/main/res/                   icons / themes / strings / FileProvider paths
app/build.gradle.kts                app config (version, signing, BuildConfig injection)
build.gradle.kts                    root build script
settings.gradle.kts                 repositories (Alibaba mirror first) and modules
gradle.properties                   Gradle flags (heap, parallel, cache, daemon timeout)
build.sh                            one-shot build script
docs/                               design and development docs (Chinese)
```

Documentation index (Chinese):

| File | Contents |
|---|---|
| `docs/DESIGN.md` | architecture layers, file responsibilities, contracts, key decisions |
| `docs/INTERNALS.md` | runtime mechanics: run lifecycle, SSE event table, reconnection, image pipeline, storage format, pitfalls |
| `docs/BUILD.md` | toolchain locations, environment setup, dependency versions, error reference |
| `docs/DEPLOYMENT-GUIDE.md` | from-scratch deployment and customization guide |
| `docs/CHANGELOG.md` | full change history |
| `docs/COLLAB.md` | collaboration rules (commit format, release checklist, rollback) |

---

## Quick start

```bash
# 1. Clone
git clone https://github.com/shang1626/hermes-dialog-app.git
cd hermes-dialog-app

# 2. Configure (see next section — at minimum sdk.dir and the server address)
cp local.properties.example local.properties   # or create it by hand
$EDITOR local.properties

# 3. Build
./build.sh

# 4. Output
ls -l app/build/outputs/apk/debug/app-debug.apk
```

Install to a phone: `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy the APK over and tap it.

---

## Configuration

Real values are **not** stored in source. Create a `local.properties` in the project root (it is `.gitignore`d and never committed) and fill in your own values:

```properties
# Android SDK path (required, otherwise the build fails with "SDK location not found")
sdk.dir=/path/to/your/Android/SDK

# ===== Server configuration (injected into source at build time via BuildConfig) =====

# App login password: the client-side gate you type when opening the app
HERMES_APP_PASSWORD=your-app-password

# API keys for the two profiles (Bearer keys issued by the gateway)
HERMES_DEFAULT_KEY=your-default-profile-key
HERMES_FRIEND_KEY=your-second-profile-key

# Chat API address: points at your Hermes gateway
HERMES_DEFAULT_URL=https://your-gateway-domain

# Address of the in-app update version.json
HERMES_UPDATE_URL=https://your-update-domain/update/version.json

# Legacy host migration (optional): old= new=, the app rewrites stored old addresses on start
HERMES_LEGACY_HOSTS=old-domain=new-domain
```

These values are read by `app/build.gradle.kts` at build time and injected via `buildConfigField`; the source only references `BuildConfig.XXX` and contains no real values. **When building on another machine you must supply your own `local.properties`.**

---

## Build

### Prerequisites

- JDK 17 or newer
- Android SDK: platform 34 + build-tools 34.0.0 + platform-tools
- Gradle 8.9

> ⚠️ This project has **no Gradle wrapper** (no `gradlew`, no `gradle/wrapper/`).
> Do not follow tutorials that tell you to run `./gradlew` — you will get `command not found`. Use the system `gradle`, or just run `build.sh`.

### One-shot build

```bash
./build.sh            # build the debug APK
./build.sh clean      # clean first, then build
```

Output: `app/build/outputs/apk/debug/app-debug.apk`; the log goes to `build.log` (gitignored). Check the log for `BUILD SUCCESSFUL` and `EXIT=0`. A full build takes about half a minute (longer on the first run while dependencies download).

### Manual build

```bash
export JAVA_HOME=/path/to/jdk
export ANDROID_SDK_ROOT=/path/to/android/sdk
export ANDROID_HOME=/path/to/android/sdk
gradle assembleDebug
```

All three environment variables are required: `JAVA_HOME`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`. Missing `ANDROID_HOME` gives an SDK-not-found error.

### Dependencies and repositories

`settings.gradle.kts` lists **Chinese mirrors first** (Alibaba gradle-plugin / google / public), then falls back to `google()` / `mavenCentral()`. If you are outside China with good direct access you can delete the mirrors; inside China keep them or dependency downloads will be slow.

Core versions: AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, compose-compiler 1.5.14, OkHttp 4.12.0, Coil 2.6.0.

---

## Signing

By default the app uses the **debug signature** (`~/.android/debug.keystore`), and the release build type points at it too:

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

What that means:

- When building on another machine you **must bring the same `debug.keystore`**, otherwise the new APK has a different signature and the phone refuses to install it ("App not installed / signature conflict").
- Fine for personal use; not aiming for store distribution. For a dedicated signature, create your own keystore and change `build.gradle.kts`.
- If `~/.android/debug.keystore` does not exist, the Android toolchain generates a new one — so two machines produce differently signed packages. This is the most common pitfall.

---

## Server preparation

The app is only a client; you must run the server yourself:

1. Deploy and run [Hermes Agent](https://github.com/NousResearch/hermes-agent) and make sure the `api_server` platform is enabled.
2. Verify the gateway: `curl http://127.0.0.1:8642/health` should return normally.
3. Get the API keys for your two profiles (e.g. `default` / `friend`) and put them in `local.properties`.
4. To reach it from a phone over the internet, put a reverse proxy or reverse tunnel in front of `8642` with a proper HTTPS certificate.

> Note: `api_server` listens on `127.0.0.1` by default. Do not expose it directly to the internet — put an authenticating / rate-limiting reverse proxy in front.

---

## Server API contract

The `api_server` endpoints this app uses (check this table when changing the server):

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/runs` | start a run (body: `input` / `session_id?` / `images?`) → returns `run_id` |
| GET | `/v1/runs/{id}` | run status and result (used to recover after a restart) |
| GET | `/v1/runs/{id}/events` | SSE event stream (resumable via `Last-Event-ID`) |
| POST | `/v1/runs/{id}/stop` | stop a run |
| POST | `/v1/runs/{id}/steer` | steer mid-run |
| GET | `/v1/capabilities` | capability probing (e.g. `features.supports_vision`) |
| POST | `/v1/artifacts/upload` | upload an image, returns `artifact_id` |
| GET | `/api/sessions/{id}/messages` | fetch server-side session messages |
| GET | `/health` `/health/sysinfo` `/health/detailed` | liveness and status-screen data |

Auth: every request carries `Authorization: Bearer <your key>`, using the two profile keys injected from `local.properties`.

### SSE event types the client understands

| event | client behavior |
|---|---|
| `message.delta` | append to the current assistant bubble |
| `message.interim` | ignored |
| `tool.started` | ignored (only completion is recorded) |
| `tool.completed` | append a tool-trace line |
| `tool.failed` | append a tool-trace line with an ✗ prefix |
| `run.completed` | finalize text from `output`; notify if in background |
| `run.failed` | mark failed, trigger the auto-reconnect decision |
| `run.cancelled` / `run.interrupted` | mark interrupted and wrap up |

### Optional server patches (to receive non-image attachments)

By default the Hermes gateway only turns image `MEDIA:` into inline data URLs. To accept ordinary file attachments you need two server-side patches (**lost on Hermes upgrade, must be re-applied**):

- `gateway/platforms/api_server.py`: make `_resolve_media_to_data_urls()` accept non-image extensions and raise the size limit from 5 MB to 12 MB.
- `gateway/platforms/api_server_runs.py`: run the media resolution pass on `/v1/runs` completion too (originally only the two chat-completions paths did).

Without these patches, receiving files degrades to a filename link only.

---

## Release and update flow

The app checks for updates itself:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts` (`versionCode` **must** increase — that is what the client compares).
2. Build the APK.
3. Put the APK in your update directory, named like `<name>-<version>-<md5 first 8>.apk`.
4. Update `version.json` in the same directory:

```json
{"versionCode":19,"versionName":"2.8",
 "url":"https://your-domain/update/<filename>.apk",
 "notes":"what changed in this version",
 "size":12345678,
 "md5":"<md5 of the file>"}
```

5. The next time the client checks for updates it will see it, download with progress, verify the MD5, and hand off to the installer.

Notes:

- `size` and `md5` must match the actual APK or the download check fails.
- If your update distribution goes through a CDN (Cloudflare / EdgeOne / etc.), **the CDN caches `version.json`** — purge it after publishing or the client sees the old version number. The app already appends a timestamp to work around caching, but that does not fully replace a manual purge.
- Keep old APKs in the distribution directory for easy rollback (point `version.json` back at the old package).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `gradle: command not found` | You did not use the full path. Run `./build.sh`, or export the toolchain path |
| `SDK location not found` | `ANDROID_HOME` is not exported, or `local.properties` has no `sdk.dir` |
| `licenses not accepted` | Re-run `sdkmanager --licenses`, e.g. `yes | sdkmanager --licenses` |
| Phone refuses the new package (signature conflict) | Different build machine / missing `debug.keystore`. Rebuild with the same keystore |
| Build hangs downloading dependencies | Network issue. Check the mirror repositories are still in `settings.gradle.kts` |
| App stays "offline" | Check `HERMES_DEFAULT_URL` is reachable, `api_server` is running, and the HTTPS certificate is valid |
| Login says wrong password | `HERMES_APP_PASSWORD` differs from what you typed; **rebuild** after changing it (the value is injected at build time) |
| No file attachments arrive | Server patches not applied (see above), or the file exceeds the 12 MB limit |
| No voice announcement | Server TTS not enabled, the voice is unsupported, or the TTS proxy is unreachable |
| Update check sees the old version | CDN caching — purge it in the console |
| Text invisible in dark/light mode | The theme must replace the whole color set, not just the background |

---

## Security notes

- This repository contains **no real keys, passwords, domains, or internal IPs** and is safe to publish.
- All sensitive configuration is injected at build time from `local.properties` (gitignored); the source only references it.
- Never write real keys into source, docs, commit messages, or issues — keep them in your local `local.properties`.
- `.gitignore` also excludes `build/`, `dist/`, `build.log`, `.gradle/`, `.idea/` — that is deliberate, not a missing commit.
- When deploying to the internet, always put a reverse proxy + HTTPS + auth in front of the gateway; never expose `api_server` directly.

---

## License

[MIT](LICENSE)
