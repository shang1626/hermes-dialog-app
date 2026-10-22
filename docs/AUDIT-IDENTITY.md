# A03 / A04 / A10 Identity Isolation Audit

## Implemented boundary

- Credentials are keyed by canonical server + profile. The credential's account is part of the conversation domain: SHA-256(canonical server + NUL + profile + NUL + account).
- Canonical server normalizes scheme/host casing, standard ports and trailing slash; non-default ports and base paths remain distinct. Embedded userinfo, queries and fragments are rejected. Password is never a directory component.
- SessionStore production construction uses `filesDir/identities/<domain>/`; index, messages, queues and backups all stay within this directory.
- Drafts, active runs, sequence checkpoints, pending titles, current session, notification replies/open targets, report dedupe and shell cleanup state use identity-specific preferences. Theme/audio/background settings remain app-wide.
- RuntimeHub session maps are domain-specific. Every VM freezes its Prefs/domain/API/store and owns a SupervisorJob for ALL formerly viewModelScope and RuntimeHub.scope work. Identity changes retire the old VM and clear the Activity ViewModelStore before recreation; Activity.recreate alone is insufficient because Android retains VMs.
- Retirement invalidates SSE callbacks, cancels local observer jobs and transports, flushes message/queue/index state, stops local playback and clears global media fetch hooks. It does NOT call stopRun or erase server tasks. Switching back recovers persisted run IDs. A synchronous startRun POST finishing after retirement persists its run ID only into the old identity's preferences and returns without a stop request.

## Legacy migration and compatibility

- The first Prefs initialization snapshots the original saved server and accounts before any login writes a new server. Legacy credential keys are copied only to that original server's credential namespace and then removed.
- Profile-less legacy runtime/draft preferences can only be attributed to the previously selected profile; they are copied only into that profile/account domain (not both). Unknown ownership is not guessed.
- Old session files are copied (not moved/deleted) only into the original server/profile/account domain. A completion marker prevents repeated migration resurrecting explicitly cleared history. Backup and queue files are included. Interrupted copies can retry without deleting the source.
- Legacy source files/preferences remain for downgrade/data recovery. They are not read by another server's production store. Downgrading to an old APK reintroduces old isolation behavior and is not supported as an isolation guarantee.
- Previously saved credentials for other servers cannot be recovered from the old format because it never recorded them. Users must log into those servers again.
- Automatic substring-based legacy hostname rewriting is no longer performed during VM connection: an unverified alternate host is a different trust domain and requires login.

## Entry semantics

- Settings: `切换账号（保留登录凭据）` retires local observation and opens login; saved accounts remain one-tap entries.
- Login: `退出并移除本服务器保存的凭据` requires confirmation and removes this server's saved credentials; local conversation files and server tasks remain.
- Editing the login URL hides quick-entry credentials unless the canonical URL still equals the saved server. Typed credentials are validated against the selected server before saving.

## Real execution evidence

- Initial reproduction uses the real old SessionStore constructor twice for one profile, writes history in one and asserts the other server cannot see it. It failed at IdentityStorageTest.kt:16 (1 test, 1 failure). Log: `/root/.hermes/cache/scratch/identity-before2.log`. The first run was blocked by concurrently incomplete history UI compilation, not counted as reproduction.
- Fixed IdentityStorageTest exercises actual SessionStore IO, domain-directory migration and RuntimeHub maps: independent servers/accounts/profiles, equivalent canonical origins, distinct paths/ports, source-preserving original-server migration, no repeated import after clear, independent runtime objects.
- Full `:app:testDebugUnitTest` passed, 207 tests, zero failures/errors at the recorded run. Log: `/root/.hermes/cache/scratch/identity-final.log`. Shared worktree tests also include sibling agents' changes.

## Device / residual validation boundary

No Android emulator or physical device was exercised in this subtask. JVM coverage does NOT execute Android SharedPreferences, Activity/ViewModelStore lifecycle, real OkHttp late responses, playback, or Notification PendingIntents. Required device scenarios before release:

1. Upgrade with existing history/queued input on original server; enter another server first, verify old data only appears on returning to original account.
2. Delay synchronous create-run response, switch account/server, allow POST to finish; verify no stop request, no new-account message/queue change, and old run recoverable after switching back.
3. Switch during SSE/coalesced frame/save/index debounce, audio playback, attachment preparation and inbox polling; verify no stale notification/playback/global hook replacement.
4. Toggle rotation/background process reclaim, then resume multiple live sessions. Verify each task has one observer and correct last-sequence checkpoint.
5. Confirm switching is one tap, forgetting credentials requires confirmation, forgetting retains history, relogin restores live server work.

Migration is non-destructive but legacy retained copies contain private history. They remain within Android app-private storage; do not claim this is secure credential encryption or secure erasure. No credentials/version/release artifacts were modified, and no commit/push/server restart was performed.
