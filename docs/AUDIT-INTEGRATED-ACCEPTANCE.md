# Integrated local acceptance

## Scope

Follow-up to AUDIT-FIRST-BATCH-20261022.md: identity isolation, explicit credential
removal, opt-in history restoration, notification controls, update download limits,
and lint gate. Existing release version/signing/update manifest remain unchanged.

## Executed results

- Integrated testReleaseUnitTest: 207 tests, zero failures/errors/skips.
- Integrated notification/update audit source set (Robolectric and real localhost
  HTTP), including the normal suite: 225 tests, zero failures/errors/skips.
- assembleRelease: BUILD SUCCESSFUL, EXIT=0.
- lintRelease: zero errors; 66 warnings and 13 information entries.
- Full lint (debug and release): BUILD SUCCESSFUL, EXIT=0.
- git diff --check: passed after removing trailing whitespace.
- aapt package check: com.hermesapp, 2.216 / 227. No distribution of this unchanged
  version as a new update is authorized by this local acceptance.

Logs:
/root/.hermes/cache/scratch/app-all-final.log
/root/.hermes/cache/scratch/app-all-audit-final.log
/root/.hermes/cache/scratch/app-all-lint-final.log

## Integration notes

confirmUpdate now calls the UpdateInfo overload, so declared package size is checked
in the actual UI download path. Credential and data isolation use immutable identity
contexts; switching retires local observers but does not stop server execution.
History recovery is explicit, unselected by default and limited to the fetched
server window; it does not restore automatic bulk history import.

Notification fallbacks no longer bypass channel mute. Existing MD5 compatibility
is retained with mandatory formatting, size and streaming limits; this is integrity
checking, not an authenticated publisher signature. The existing Android signing
identity is not rotated. CI lint no longer has continue-on-error.

## Not completed / not claimed

No connected Android device was available (adb devices returned no devices), so
real-phone kill/restart, network handoff, notification-channel and audio end-to-end
acceptance remain outstanding. No production credential was tried or changed. No
public Git history was rewritten or pushed. No gateway was restarted. No APK update
was published. No promise of complete remote-history recovery is made (server
retention and current API windows still apply).

A signing migration, SHA-256 authenticated update manifest and complete public
server-patch distribution are not included; they require a separate release/server
contract change. Current historical exposure is documented in the first-batch report.

Before release: back up actual app data, validate identity migration on a device,
check package secret scan/signature, assign a fresh version code, and verify the
published package and update URL. Old readers do not understand the new queue
metadata envelope, so downgrade requires a data-aware rollback plan.
