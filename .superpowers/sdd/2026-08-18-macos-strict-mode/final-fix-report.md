# macOS Strict Mode final-fix report

## Outcome

This wave addresses all final-review findings without installing the companion,
registering a LaunchAgent/native host, changing a Chrome profile, or using an
external service.

1. Active disconnected sessions retain one safe fixed-rate poll, including an
   indefinite session while Chrome is closed. A regression closes Chrome, then
   reopens it without a relay and observes a fresh 30-second warning.
2. `StrictModeService` owns an injected `RestoreWarningNotifier`. The packaged
   service supplies `MacRestoreWarningNotifier`, an asynchronous local
   AppleScript dialog. Reconnect, Chrome closure, session completion, and service
   shutdown clear only that dialog process. Notification failure never stops
   monitoring or escalates enforcement.
3. Chrome helper commands have a five-second deadline. A timed-out child gets
   `destroy()`, then only that child gets `destroyForcibly()` after a bounded
   250-ms cleanup wait. Inspection timeout maps through the existing UNKNOWN
   service path; graceful-quit timeout returns `FAILED`. Chrome is never
   force-killed.
4. Production builds require `LFC_EXTENSION_PUBLIC_KEY`, validate canonical
   base64 DER SubjectPublicKeyInfo, embed it as manifest `key`, derive the stable
   Chrome ID, and emit `dist/production-extension-identity.json`. Production
   installation consumes only that metadata. Raw unpacked IDs are accepted only
   with `--development-extension-id` and use the distinct
   `com.localfocuscoach.strict_mode_dev` host. Bridge selection, installer
   receipt, uninstaller, and relay lookup preserve this split; duplicate
   production/development allowlists are rejected. No private key or fabricated
   production public key is committed. The exact remaining release input is the
   real production public key; its corresponding private key stays in protected
   release infrastructure outside the repository.
5. Desktop Gradle outputs are ignored. The named-clear test now clears a
   different nonexistent UUID and proves the retained session is isolated. The
   manual checklist now covers dashboard-closed warning/cancellation, Chrome
   reopen, MV3 worker restart, unavailable host, and synchronous host failure.

## TDD evidence

- The Chrome-reopen service regression first failed because no scheduled check
  existed, then passed after disconnected active sessions were scheduled.
- Injected-notifier service tests and `MacRestoreWarningNotifierTest` first
  failed to compile because the notifier boundary/implementation did not exist,
  then passed after their minimal implementations.
- `MacChromeControllerTest` first failed because the concrete runner was private
  and unbounded, then passed with bounded waits and child cleanup.
- Focused manifest/native-bridge tests first had four expected failures for the
  missing identity API and wrong unkeyed host, then all passed.
- Installer tests first failed because ambiguous `--extension-id` was accepted,
  then passed after explicit production/development modes were separated.
- Relay tests first failed to compile because caller-specific configuration
  lookup was absent, then passed after exact lookup and duplicate rejection.

## Commands and results

Focused verification:

```text
npm run typecheck
npm test -- --run tests/manifest.test.ts tests/native-bridge.test.ts
./desktop/installer/test-installer.sh
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  PATH="$(brew --prefix openjdk@21)/bin:$PATH" \
  ./desktop/gradlew -p desktop \
  :strict-service:test --tests '*StrictModeServiceTest' \
  --tests '*MacChromeControllerTest' \
  --tests '*MacRestoreWarningNotifierTest' \
  :strict-relay:test --tests '*NativeMessagingRelayTest' \
  :strict-store:test --tests '*SqliteStrictSessionRepositoryTest'
```

All focused TypeScript, installer, service, relay, controller, notifier, and
store checks passed.

A production smoke build used an ephemeral test key created outside the
repository. `npm run build:production` embedded its public key and emitted ID
`lcmfobccbilaokobiabliejmbngjnhmn` for the production host. This disposable key
is not release material and no key file was committed.

Fresh full verification:

```text
npm run typecheck
npm test
npm run build
./desktop/installer/test-installer.sh
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  PATH="$(brew --prefix openjdk@21)/bin:$PATH" \
  ./desktop/gradlew -p desktop test build jpackage
git diff --check
```

Results: TypeScript typecheck exited 0; Vitest passed 24 files and 154 tests;
the development bundle completed without production identity metadata; the
installer suite passed; Gradle reported `BUILD SUCCESSFUL` for all desktop
tests, builds, and `jpackage` (31 tasks, 15 executed, 16 up-to-date); and
`git diff --check` exited 0. Vite printed its existing future native config
loader warning, which did not affect typecheck, tests, or builds.

## Changed files

- Service: `StrictModeService.java`, `RestoreWarningNotifier.java`,
  `MacRestoreWarningNotifier.java`, `MacChromeController.java`, service bootstrap
  generation, and their service/controller/notifier tests.
- Identity/bridge: `manifest.config.ts`, `vite.config.ts`, `package.json`,
  `native-bridge.ts`, Chrome test stubs, manifest tests, and bridge tests.
- Registration/relay: native-host template, installer, uninstaller, installer
  tests, `NativeMessagingRelay.java`, and relay tests.
- Hygiene/docs: `.gitignore`, store isolation test, root `README.md`,
  `desktop/README.md`, and the manual Strict Mode checklist.

## Self-review

- Re-read the approved design, plan, and five findings and mapped each to code,
  automated coverage, or the manual checklist.
- Confirmed scheduler creation remains single-task and cancels on healthy
  reconnect or session completion.
- Confirmed warning data is fixed local copy plus a duration: no URLs, content,
  history, telemetry, network call, or browsing data crosses the boundary.
- Confirmed notifier/process failures preserve the active session and never
  escalate beyond the exact graceful Google Chrome AppleScript request.
- Confirmed installer tests use temporary HOME plus mocked `launchctl`; no live
  registration, Chrome profile, or system state changed.
- Confirmed production/development names, filenames, bridge selection, receipt,
  relay, tests, and docs agree, and no release key appears in the diff.
- Confirmed generated Gradle output is absent from `git status`, whitespace is
  clean, and no unrelated changes are included.
- No unresolved Critical, Important, or Minor issue remains in the reviewed
  diff.
