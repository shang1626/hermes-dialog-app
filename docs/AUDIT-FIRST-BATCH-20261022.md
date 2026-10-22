# Audit first batch: A05 / A06 / A07

Scope: local v2.216 / 227, baseline 79561f1. No release, gateway restart,
credential use, public push, or production data migration was performed.

## Reproductions

- A05: real SessionRuntime and production callback gate, coroutine-delayed
  completion. Before: 3 failures / 5 cases. After: 5 passed.
- A06: real mergeByUserAnchor, quotes and attachment-only messages.
  Before: 6 failures / 8 cases. After: 8 passed; related merge coverage 45 passed.
- A07: real SessionStore and temporary files. Before: 4 failures / 7 cases.
  After: 10 passed; related persistence coverage 34 passed.

## Changes

Stop invalidates both send and stream generations before cancelling the stream.
All four SSE callback entry points validate both generations. The guard does not
reject finished runs merely because they are finished: trailing audio stays valid
until stop or a new send supersedes that turn.

History anchors prefer Receipt.rawText, falling back to the existing quote prefix.
The displayed text, metadata, ordering and timestamp matching window are retained.

Message IDs and message-level idempotency keys are persisted. Stored IDs are
reserved before assigning IDs to legacy messages. QUEUED is restored only when
stable ID and original key match a valid persisted queue item. Missing attachments,
orphaned messages and mismatched keys remain NOT_SENT; SENDING becomes UNCERTAIN.
Legacy messages without IDs are never guessed into a queue.

Queue JSON accepts legacy arrays and a new schema/paused/items envelope. Legacy
nonempty arrays default to paused. Clearing a queue also removes atomic backups
so an obsolete queue cannot reappear. Stop persists the pause before callbacks can
advance a nonempty queue; resume persists the unpause before permitting delivery.
Pause is loaded before cold-start queue advancement. An empty queue is not newly
paused by stop.

## Verification and limitations

Initial integrated run: testReleaseUnitTest + assembleRelease succeeded (199 tests,
zero failures/errors/skips; BUILD SUCCESSFUL, EXIT=0). Final revalidation after
review is recorded in /root/.hermes/cache/scratch/app-first-batch-final-build.log.
Evidence: scratch/a05/, scratch/a06/, scratch/a07-queue-evidence/.

These are JVM mechanism tests and an Android release build, not a real-phone,
network, playback, or process-kill end-to-end acceptance. New queue envelopes are
not understood by the old reader; retain data backups before release or migration.
No version bump or update-manifest change is included. The locally built APK must
not be distributed as an update with the unchanged version code.

## Public-history check

An anonymous ls-remote confirmed main de54e29 and master 0ed0e48. A targeted scan
of Keys.kt history found APP_PASSWORD and two key fields in master ancestors. An
anonymous raw-file request returned HTTP 200. Values were neither printed nor
tried, and current validity was not checked. Evidence is redacted in
/root/.hermes/cache/scratch/app-history-redacted-20261022.json. This is a targeted
check, not a complete repository-wide secret scan. Credential rotation and public
history rewriting require a separate approved operation.
