# macOS Strict Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local macOS companion that keeps an opt-in Strict Mode session active, detects an unavailable Chrome extension through Native Messaging, warns for 30 seconds, and then gracefully quits Google Chrome.

**Architecture:** A Java 21 LaunchAgent service is the durable authority for Strict Mode state, SQLite persistence, the warning timer, and Chrome quit requests. A JavaFX dashboard and a Chrome-spawned Java native-messaging relay communicate with it through authenticated Unix-domain sockets. The existing MV3 TypeScript extension retains feed enforcement and adds only a native heartbeat client.

**Tech Stack:** Java 21, Gradle 8.10.2, JavaFX 21.0.5, JUnit Jupiter 5.10.3, SQLite JDBC 3.46.1.0, Jackson Databind 2.17.2, Chrome Manifest V3, TypeScript, Vite, Vitest, macOS `launchd`, `jpackage`.

**Spec:** `docs/superpowers/specs/2026-08-18-macos-strict-mode-design.md`

## Global Constraints

- Target macOS and Google Chrome only; do not add Brave, Edge, Windows, mobile, accounts, or cloud sync.
- Strict Mode is opt-in and local-only. Do not send URLs, feed content, screenshots, browsing history, Gemini payloads, or telemetry to the Java service or any network endpoint.
- Use a user-level LaunchAgent, never a root LaunchDaemon; require no administrator privileges.
- Timed sessions end automatically at `endsAt`. Indefinite sessions always require a typing challenge; timed sessions require it only for early exit when enabled at session start.
- The typing challenge is exactly 500 cryptographically random ASCII letters. Backspace works; paste, drop, and clipboard shortcuts are blocked; no mismatch position or hint is displayed.
- A missing extension triggers enforcement only while an active Strict Mode session exists **and** Google Chrome is running. Show a 30-second restore warning before a graceful Chrome quit. Never force-kill Chrome.
- The relay host manifest allows one exact 32-character Chrome extension ID. The installer accepts that ID explicitly and rejects any malformed value.
- Java service/UI/relay socket messages are versioned JSON and authenticated with a per-install secret stored in a user-only directory.
- Any malformed IPC, unavailable Chrome process, uncertain process state, or failed graceful quit must fail toward less destructive behavior.
- Preserve current extension behavior and privacy tests. Add `nativeMessaging` only; no HTTP/network API is permitted in extension code.

## File Structure

| Path | Responsibility |
| --- | --- |
| `desktop/settings.gradle.kts` | Declares the Java multi-project build. |
| `desktop/build.gradle.kts` | Central Java 21, repositories, and test conventions. |
| `desktop/strict-core/` | Pure Strict Mode types, state machine, challenge verification, and protocol vocabulary. |
| `desktop/strict-store/` | SQLite schema/migrations and durable session repository. |
| `desktop/strict-service/` | LaunchAgent process, authenticated Unix socket server, timer scheduling, connection health, and Chrome controller interface. |
| `desktop/strict-relay/` | Chrome Native Messaging framing on stdin/stdout and service-socket forwarding. |
| `desktop/strict-dashboard/` | JavaFX session controls, warning state, and unlock challenge UI. |
| `desktop/installer/` | LaunchAgent and Chrome native-host manifest templates plus install/uninstall scripts. |
| `desktop/README.md` | JDK setup, development, packaging, and manual verification instructions. |
| `src/background/native-bridge.ts` | MV3 native-port connection, heartbeat, reconnect, and strict-state handling. |
| `src/background/service-worker.ts` | Starts/stops the bridge without changing feed-decision behavior. |
| `src/shared/native-protocol.ts` | Typed extension-side Native Messaging payloads and validation. |
| `manifest.config.ts` | Adds only the `nativeMessaging` permission. |
| `tests/native-bridge.test.ts` | Extension native-port/reconnect unit tests. |
| `tests/manifest.test.ts` | Guards the exact added permission and preserves the no-network policy. |
| `docs/manual-test-checklist.md` | Adds macOS installer, warning, reconnect, expiry, and challenge checks. |
| `README.md` | Documents the companion’s scope, opt-in model, and non-tamper-proof boundary. |

---

### Task 1: Create the reproducible Java workspace

**Files:**
- Create: `desktop/settings.gradle.kts`
- Create: `desktop/build.gradle.kts`
- Create: `desktop/gradle/wrapper/gradle-wrapper.properties`
- Create: `desktop/strict-core/build.gradle.kts`
- Create: `desktop/strict-store/build.gradle.kts`
- Create: `desktop/strict-service/build.gradle.kts`
- Create: `desktop/strict-relay/build.gradle.kts`
- Create: `desktop/strict-dashboard/build.gradle.kts`
- Create: `desktop/README.md`

**Interfaces:**
- Produces Gradle projects named `strict-core`, `strict-store`, `strict-service`, `strict-relay`, and `strict-dashboard`.
- All Java code compiles with `JavaLanguageVersion.of(21)` and tests run with JUnit Jupiter.

- [ ] **Step 1: Verify the current JDK prerequisite fails clearly**

Run: `java -version`

Expected: no Java runtime is currently installed; record this in `desktop/README.md` as a prerequisite rather than installing a JDK silently.

- [ ] **Step 2: Create the Gradle build files and a smoke test**

```kotlin
// desktop/settings.gradle.kts
rootProject.name = "local-focus-coach-desktop"
include("strict-core", "strict-store", "strict-service", "strict-relay", "strict-dashboard")

// desktop/build.gradle.kts
plugins { java }
allprojects {
  repositories { mavenCentral() }
}
subprojects {
  plugins.apply("java")
  java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
  tasks.test { useJUnitPlatform() }
}
```

Add `strict-core/src/test/java/com/localfocuscoach/strict/BuildSmokeTest.java`:

```java
@Test
void javaWorkspaceRunsTests() {
  assertTrue(true);
}
```

- [ ] **Step 3: Run the smoke test and build**

Run: `cd desktop && ./gradlew :strict-core:test build`

Expected: PASS after Java 21 is installed; no test source is skipped.

- [ ] **Step 4: Document exact local setup**

Add the Java 21 install prerequisite, `./gradlew test`, module descriptions, and the fact that the app image bundles a runtime later through `jpackage`.

- [ ] **Step 5: Commit the workspace baseline**

```bash
git add desktop
git commit -m "build(strict): add Java workspace"
```

### Task 2: Implement the pure Strict Mode state machine

**Files:**
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/core/StrictMode.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/core/StrictSession.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/core/StrictAction.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/core/StrictStateMachine.java`
- Create: `desktop/strict-core/src/test/java/com/localfocuscoach/strict/core/StrictStateMachineTest.java`

**Interfaces:**
- Produces `StrictSession(UUID id, StrictMode mode, Instant startedAt, Instant endsAt, boolean earlyExitChallenge, SessionStatus status)`.
- Produces `StrictStateMachine.advance(StrictSession session, ConnectionHealth health, boolean chromeRunning, Instant now): StrictAction`.
- `StrictAction` is one of `NONE`, `SHOW_RESTORE_WARNING`, `CANCEL_RESTORE_WARNING`, `QUIT_CHROME`, `EXPIRE_SESSION`, or `BEGIN_UNLOCK_CHALLENGE`.

- [ ] **Step 1: Write failing state-machine tests**

```java
@Test
void warnsOnlyWhenChromeRunsWithoutExtension() {
  var action = machine.advance(activeTimed(), ConnectionHealth.DISCONNECTED, true, now);
  assertEquals(StrictAction.SHOW_RESTORE_WARNING, action);
}

@Test
void expiresTimedSessionWithoutAChallenge() {
  var action = machine.advance(timedEndingAt(now), ConnectionHealth.HEALTHY, true, now);
  assertEquals(StrictAction.EXPIRE_SESSION, action);
}

@Test
void indefiniteSessionRequestsChallengeBeforeUnlock() {
  assertEquals(StrictAction.BEGIN_UNLOCK_CHALLENGE, machine.requestEarlyUnlock(indefinite()));
}
```

- [ ] **Step 2: Run the focused test class to verify it fails**

Run: `cd desktop && ./gradlew :strict-core:test --tests '*StrictStateMachineTest'`

Expected: FAIL because `StrictStateMachine`, `StrictSession`, and `StrictAction` do not exist.

- [ ] **Step 3: Implement the minimal deterministic state machine**

Use injected `Clock`/`Instant` values only; no thread, socket, process, or database calls belong in this module. Encode the 30-second deadline as an `Instant warningEndsAt` owned by the session state. Return `NONE` when Chrome is not running, even if the relay is disconnected.

- [ ] **Step 4: Add boundary tests and run the module suite**

Add tests for reconnect-before-deadline cancellation, deadline expiry yielding `QUIT_CHROME`, no challenge on normal timed expiry, and a timed early-exit request yielding a challenge only when `earlyExitChallenge` is true.

Run: `cd desktop && ./gradlew :strict-core:test`

Expected: PASS.

- [ ] **Step 5: Commit the pure domain layer**

```bash
git add desktop/strict-core
git commit -m "feat(strict): add session state machine"
```

### Task 3: Add typing-challenge generation and verification

**Files:**
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/core/TypingChallenge.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/core/TypingChallengeService.java`
- Create: `desktop/strict-core/src/test/java/com/localfocuscoach/strict/core/TypingChallengeServiceTest.java`

**Interfaces:**
- Produces `TypingChallenge(UUID id, String target, Instant createdAt)`.
- Produces `TypingChallengeService.create(Instant now): TypingChallenge` and `matches(TypingChallenge challenge, String candidate): boolean`.

- [ ] **Step 1: Write failing challenge tests**

```java
@Test
void createsExactlyFiveHundredAsciiLetters() {
  var challenge = service.create(now);
  assertEquals(500, challenge.target().length());
  assertTrue(challenge.target().matches("[A-Za-z]{500}"));
}

@Test
void requiresAnExactFullMatch() {
  var challenge = new TypingChallenge(UUID.randomUUID(), "AbC", now);
  assertTrue(service.matches(challenge, "AbC"));
  assertFalse(service.matches(challenge, "Abc"));
  assertFalse(service.matches(challenge, "AbC "));
}
```

- [ ] **Step 2: Run the challenge tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-core:test --tests '*TypingChallengeServiceTest'`

Expected: FAIL because the challenge types do not exist.

- [ ] **Step 3: Implement with `SecureRandom`**

Generate each character from the exact alphabet
`ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz`. Do not persist the target in SQLite; keep the active challenge only in service memory and invalidate it after a successful match or process restart.

- [ ] **Step 4: Run all core tests**

Run: `cd desktop && ./gradlew :strict-core:test`

Expected: PASS.

- [ ] **Step 5: Commit challenge support**

```bash
git add desktop/strict-core
git commit -m "feat(strict): add unlock challenge"
```

### Task 4: Persist sessions in SQLite

**Files:**
- Create: `desktop/strict-store/src/main/java/com/localfocuscoach/strict/store/StrictSessionRepository.java`
- Create: `desktop/strict-store/src/main/java/com/localfocuscoach/strict/store/SqliteStrictSessionRepository.java`
- Create: `desktop/strict-store/src/main/resources/db/migration/V1__strict_session.sql`
- Create: `desktop/strict-store/src/test/java/com/localfocuscoach/strict/store/SqliteStrictSessionRepositoryTest.java`
- Modify: `desktop/strict-store/build.gradle.kts`

**Interfaces:**
- Produces `Optional<StrictSession> loadActive()` and `void save(StrictSession session)`.
- Produces `void clear(UUID sessionId)` and `void appendAudit(UUID sessionId, String event, Instant at)`.

- [ ] **Step 1: Write failing repository tests against a temporary database**

```java
@Test
void activeSessionSurvivesRepositoryReopen(@TempDir Path directory) {
  var first = repository(directory);
  first.save(activeIndefinite());
  var reopened = repository(directory);
  assertEquals(activeIndefinite().id(), reopened.loadActive().orElseThrow().id());
}

@Test
void clearRemovesOnlyTheNamedSession(@TempDir Path directory) {
  var repo = repository(directory);
  var session = activeTimed();
  repo.save(session);
  repo.clear(session.id());
  assertTrue(repo.loadActive().isEmpty());
}
```

- [ ] **Step 2: Run repository tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-store:test --tests '*SqliteStrictSessionRepositoryTest'`

Expected: FAIL because the repository and migration do not exist.

- [ ] **Step 3: Implement the schema and repository**

Use `org.xerial:sqlite-jdbc:3.46.1.0`. Store session UUID, mode, start/end instants, early-exit challenge flag, status, warning deadline, and audit timestamps. Do not add columns for browsing or extension-content data. Run migrations transactionally before the first query.

- [ ] **Step 4: Run repository and dependent core tests**

Run: `cd desktop && ./gradlew :strict-store:test :strict-core:test`

Expected: PASS.

- [ ] **Step 5: Commit durable state**

```bash
git add desktop/strict-store
git commit -m "feat(strict): persist active sessions"
```

### Task 5: Build authenticated local IPC and the long-running service

**Files:**
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/protocol/ProtocolMessage.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/protocol/ProtocolCodec.java`
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/StrictModeService.java`
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/UnixSocketServer.java`
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/InstallSecret.java`
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/ChromeController.java`
- Create: `desktop/strict-service/src/test/java/com/localfocuscoach/strict/service/UnixSocketServerTest.java`
- Create: `desktop/strict-service/src/test/java/com/localfocuscoach/strict/service/StrictModeServiceTest.java`
- Modify: `desktop/strict-service/build.gradle.kts`

**Interfaces:**
- Every IPC frame contains `version`, `secret`, `type`, and `payload`.
- Valid `type` values are `dashboard.start`, `dashboard.status`, `dashboard.beginUnlock`, `dashboard.submitUnlock`, `relay.connected`, `relay.heartbeat`, and `relay.disconnected`.
- Produces `StrictModeService.handle(ProtocolMessage message, Instant now): ProtocolMessage`.
- Consumes a `ChromeController` interface with `isRunning()` and `requestGracefulQuit()`; Task 6 supplies the macOS implementation.

- [ ] **Step 1: Write failing protocol and authentication tests**

```java
@Test
void rejectsWrongSecretWithoutChangingSession() {
  var response = service.handle(message("wrong", "dashboard.start"), now);
  assertEquals("error.unauthorized", response.type());
  assertTrue(repository.loadActive().isEmpty());
}

@Test
void relayReconnectCancelsExistingWarning() {
  service.handle(relayDisconnected(), now);
  service.handle(relayConnected(), now.plusSeconds(10));
  assertEquals(SessionStatus.ACTIVE, repository.loadActive().orElseThrow().status());
}
```

- [ ] **Step 2: Run the focused service tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-service:test --tests '*StrictModeServiceTest'`

Expected: FAIL because the protocol and service do not exist.

- [ ] **Step 3: Implement the socket server and service boundary**

Use `UnixDomainSocketAddress` under
`~/Library/Application Support/Local Focus Coach/run/strict-mode.sock`. Create a
random per-install secret under the same app-support directory with owner-only
permissions. Require the JSON protocol version to be `1`; reject any other
version, invalid JSON, missing secret, or unknown type without mutating SQLite.

Start a scheduled check at one-second resolution only while a session has a
warning deadline or timed expiry. On recovery, reload `loadActive()` and resume
the deadline from persisted timestamps.

- [ ] **Step 4: Add service-recovery tests and run the service suite**

Add tests for a service restart restoring a timed session, a disconnected relay
with Chrome absent producing no warning, and malformed JSON producing an error
frame without a database write.

Run: `cd desktop && ./gradlew :strict-service:test`

Expected: PASS.

- [ ] **Step 5: Commit the durable service**

```bash
git add desktop/strict-core desktop/strict-service
git commit -m "feat(strict): add local service IPC"
```

### Task 6: Add Google Chrome process control and Native Messaging relay

**Files:**
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/ChromeController.java`
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/MacChromeController.java`
- Create: `desktop/strict-service/src/test/java/com/localfocuscoach/strict/service/MacChromeControllerTest.java`
- Create: `desktop/strict-relay/src/main/java/com/localfocuscoach/strict/relay/NativeMessageFraming.java`
- Create: `desktop/strict-relay/src/main/java/com/localfocuscoach/strict/relay/NativeMessagingRelay.java`
- Create: `desktop/strict-relay/src/test/java/com/localfocuscoach/strict/relay/NativeMessageFramingTest.java`
- Create: `desktop/strict-relay/src/test/java/com/localfocuscoach/strict/relay/NativeMessagingRelayTest.java`

**Interfaces:**
- Produces `ChromeController.isRunning(): boolean` and `ChromeController.requestGracefulQuit(): QuitResult`.
- Produces `NativeMessageFraming.read(InputStream): Optional<JsonNode>` and `write(OutputStream, JsonNode): void`.
- Relay maps Chrome port lifecycle to `relay.connected`, `relay.heartbeat`, and `relay.disconnected` service messages.

- [ ] **Step 1: Write failing process-control and framing tests**

```java
@Test
void nativeFrameUsesByteLengthPrefix() throws Exception {
  var output = new ByteArrayOutputStream();
  var expected = objectMapper.readTree("{\"type\":\"heartbeat\"}");
  framing.write(output, expected);
  assertEquals(expected, framing.read(new ByteArrayInputStream(output.toByteArray())).orElseThrow());
}

@Test
void quitRequestTargetsGoogleChromeOnly() {
  controller.requestGracefulQuit();
  assertEquals(List.of("osascript", "-e", "tell application \"Google Chrome\" to quit"), runner.command());
}
```

- [ ] **Step 2: Run the focused relay tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-relay:test :strict-service:test --tests '*NativeMessageFramingTest' --tests '*MacChromeControllerTest'`

Expected: FAIL because the relay and Chrome controller do not exist.

- [ ] **Step 3: Implement minimal, bounded OS integration**

Use `pgrep -x "Google Chrome"` only to decide whether Chrome is running. Use
`osascript -e 'tell application "Google Chrome" to quit'` for a graceful quit;
do not invoke `kill`, `pkill`, `killall`, or a shell. Inject a command runner so
unit tests never inspect or quit a real browser.

Implement native framing as UTF-8 JSON preceded by a four-byte native-endian
length. Write protocol data only to stdout; write diagnostics only to stderr.
Read the native host caller origin argument and reject it unless it equals the
installed extension’s exact `chrome-extension://<id>/` origin before opening
the service socket.

- [ ] **Step 4: Verify reconnection and graceful-failure behavior**

Add tests for EOF generating one `relay.disconnected`, wrong origin refusing to
connect, a service-socket failure returning a valid error frame, and a failed
AppleScript result leaving the Strict Mode session active rather than retrying
with a force kill.

Run: `cd desktop && ./gradlew :strict-relay:test :strict-service:test`

Expected: PASS.

- [ ] **Step 5: Commit relay and Chrome control**

```bash
git add desktop/strict-relay desktop/strict-service
git commit -m "feat(strict): add Chrome health relay"
```

### Task 7: Build the JavaFX dashboard

**Files:**
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardApp.java`
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/ServiceClient.java`
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/StrictModeView.java`
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/UnlockChallengeView.java`
- Create: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/UnlockChallengeViewTest.java`
- Modify: `desktop/strict-dashboard/build.gradle.kts`

**Interfaces:**
- Produces `ServiceClient.request(ProtocolMessage): ProtocolMessage`.
- `StrictModeView` starts `TIMED` or `INDEFINITE` sessions and exposes a per-session `earlyExitChallenge` checkbox for timed mode.
- `UnlockChallengeView` calls `dashboard.beginUnlock` then submits only the full candidate through `dashboard.submitUnlock`.

- [ ] **Step 1: Write failing JavaFX view tests**

```java
@Test
void pasteAndDropDoNotAlterChallengeInput() {
  var view = new UnlockChallengeView(client);
  view.onPaste();
  view.onDrop("abc");
  assertEquals("", view.currentCandidate());
}

@Test
void viewNeverExposesMismatchIndex() {
  var result = view.submit("wrong");
  assertEquals("Challenge not complete", result.message());
  assertFalse(result.message().contains("position"));
}
```

- [ ] **Step 2: Run the dashboard test class to verify it fails**

Run: `cd desktop && ./gradlew :strict-dashboard:test --tests '*UnlockChallengeViewTest'`

Expected: FAIL because the dashboard views do not exist.

- [ ] **Step 3: Implement the minimum dashboard screens**

Build three states: idle/start session, active/warning countdown, and unlock
challenge. Timed start requires a positive duration. Indefinite start does not
show an early-exit toggle because it always needs a challenge. Render the target
in a monospaced wrapping label, allow ordinary typing/backspace, consume
`Clipboard`, `ContextMenu`, `DragEvent`, and paste-related input events, and
show only generic success/failure copy from the service.

- [ ] **Step 4: Run dashboard tests and manual launch**

Run: `cd desktop && ./gradlew :strict-dashboard:test :strict-dashboard:run`

Expected: unit tests PASS and the app opens an idle dashboard without launching
or controlling Chrome.

- [ ] **Step 5: Commit the dashboard**

```bash
git add desktop/strict-dashboard
git commit -m "feat(strict): add JavaFX dashboard"
```

### Task 8: Package the user LaunchAgent and Chrome host registration

**Files:**
- Create: `desktop/installer/com.localfocuscoach.strict-service.plist.template`
- Create: `desktop/installer/com.localfocuscoach.strict_mode.json.template`
- Create: `desktop/installer/install-local-focus-coach.sh`
- Create: `desktop/installer/uninstall-local-focus-coach.sh`
- Create: `desktop/installer/validate-extension-id.sh`
- Create: `desktop/installer/test-installer.sh`
- Modify: `desktop/build.gradle.kts`
- Modify: `desktop/README.md`

**Interfaces:**
- Installer command: `./installer/install-local-focus-coach.sh --app-image <absolute-path> --extension-id <32-letter-id>`.
- Uninstaller command: `./installer/uninstall-local-focus-coach.sh`.
- LaunchAgent label: `com.localfocuscoach.strict-service`.
- Native host name: `com.localfocuscoach.strict_mode`.

- [ ] **Step 1: Write failing installer validation tests**

```bash
./installer/validate-extension-id.sh abc
test "$?" -eq 2

./installer/validate-extension-id.sh aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
test "$?" -eq 0
```

- [ ] **Step 2: Run installer tests to verify the scripts are absent**

Run: `cd desktop && ./installer/test-installer.sh`

Expected: FAIL because the scripts and templates do not exist.

- [ ] **Step 3: Implement explicit per-user installation**

Create the plist under `~/Library/LaunchAgents/` with `RunAtLoad=true` and
`KeepAlive=true`; bootstrap it with `launchctl bootstrap gui/$(id -u)`. Render
the host manifest at
`~/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.localfocuscoach.strict_mode.json`.
The manifest’s `allowed_origins` must contain exactly
`chrome-extension://<validated-id>/`. Use absolute paths only and make
uninstall remove only the exact files this installer created.

Configure a `jpackage` task that creates a macOS app image containing Java 21,
the service, relay, dashboard, and dependencies. Do not claim notarization or
automatic updates in this first version.

- [ ] **Step 4: Run static installer checks**

Run: `cd desktop && ./installer/test-installer.sh && ./gradlew jpackage`

Expected: script tests PASS; `jpackage` emits a local macOS app image after a
JDK is installed. Do not run the installer against the developer’s real Chrome
profile during automated tests.

- [ ] **Step 5: Commit packaging support**

```bash
git add desktop/installer desktop/build.gradle.kts desktop/README.md
git commit -m "build(strict): package macOS companion"
```

### Task 9: Connect the MV3 extension to the native relay

**Files:**
- Create: `src/shared/native-protocol.ts`
- Create: `src/background/native-bridge.ts`
- Create: `tests/native-bridge.test.ts`
- Modify: `manifest.config.ts`
- Modify: `src/background/service-worker.ts`
- Modify: `tests/chrome-storage.ts`
- Modify: `tests/manifest.test.ts`
- Modify: `tests/privacy-boundary.test.ts`

**Interfaces:**
- `startNativeBridge(): void`, `stopNativeBridge(): void`, and `resetNativeBridgeForTest(): void`.
- Native messages are `{ version: 1, type: 'extension.hello' | 'extension.heartbeat', payload: {} }`.
- `NativeBridgeState` is `DISCONNECTED | CONNECTING | CONNECTED`.

- [ ] **Step 1: Write failing TypeScript bridge tests**

```ts
it('connects to the strict host and sends a heartbeat', () => {
  startNativeBridge();
  expect(chrome.runtime.connectNative).toHaveBeenCalledWith('com.localfocuscoach.strict_mode');
  expect(port.postMessage).toHaveBeenCalledWith({ version: 1, type: 'extension.hello', payload: {} });
});

it('reconnects after the native port disconnects', () => {
  startNativeBridge();
  disconnectListener();
  expect(chrome.runtime.connectNative).toHaveBeenCalledTimes(2);
});
```

- [ ] **Step 2: Run the focused Vitest file to verify it fails**

Run: `npm test -- --run tests/native-bridge.test.ts`

Expected: FAIL because the bridge module and `connectNative` test stub do not exist.

- [ ] **Step 3: Implement the bridge with bounded messages**

Add `nativeMessaging` to the manifest permissions. Extend the Chrome test stub
with a fake native `Port` (`postMessage`, `onMessage`, `onDisconnect`). In the
worker, start the bridge after listener registration; it keeps one native port
open while Chrome is running, posts `extension.hello`, and emits
`extension.heartbeat` every five seconds. The service treats it as
informational when no Strict Mode session is active. On `onDisconnect`, retry
with capped exponential delays of 1, 2, 4, 8, and 15 seconds while Chrome
remains open. Do not transmit content-script events, page data, session
summaries, or any browser text.

- [ ] **Step 4: Verify extension safety and build output**

Add tests that an invalid native message is ignored, stopping the bridge clears
the heartbeat/retry timers, and no bridge code calls `fetch`, `XMLHttpRequest`,
or `WebSocket`. Update manifest assertions and run:

```bash
npm run typecheck
npm test
npm run build
```

Expected: all PASS; the emitted manifest has `nativeMessaging`, and the privacy
boundary remains network-free.

- [ ] **Step 5: Commit extension integration**

```bash
git add manifest.config.ts src tests
git commit -m "feat(strict): connect extension heartbeat"
```

### Task 10: Document and manually verify the complete macOS flow

**Files:**
- Modify: `README.md`
- Modify: `docs/manual-test-checklist.md`
- Create: `desktop/docs/manual-strict-mode-checklist.md`

**Interfaces:**
- Documents the exact installation order: build extension, obtain stable extension ID, package app image, install LaunchAgent/host manifest, launch dashboard, then enable Strict Mode.

- [ ] **Step 1: Add the manual acceptance checklist**

Include exact checks for: service starts at login; dashboard can close without
stopping a session; timed session auto-expires; timed early exit without the
toggle is rejected; timed early exit with the toggle needs a challenge;
indefinite exit always needs a challenge; backspace works; paste/drop do not;
wrong input gives no mismatch location; extension disable starts the 30-second
warning; reenabling cancels it; expiry quits Chrome gracefully; closed Chrome
does not warn; service restart restores a session; uninstall removes only
companion registrations.

- [ ] **Step 2: Add README scope and safety copy**

Document macOS/Chrome-only status, local-only IPC/storage, the user-level
LaunchAgent, the graceful-quit behavior, and the fact that Strict Mode is not
tamper-proof. Do not call the companion an AI agent or claim it prevents users
from disabling software.

- [ ] **Step 3: Run all automated verification commands**

Run:

```bash
npm run typecheck
npm test
npm run build
cd desktop && ./gradlew test build jpackage
```

Expected: all commands PASS after Java 21 is installed.

- [ ] **Step 4: Execute the manual checklist on macOS Chrome**

Run each checklist item with a locally installed app image and packed/loaded
extension. Record the observed warning time and whether Chrome quit gracefully.

- [ ] **Step 5: Commit docs and verification artifacts**

```bash
git add README.md docs desktop/docs
git commit -m "docs(strict): add macOS verification guide"
```

---

## Plan self-review

### Spec coverage

- macOS, Google Chrome, local-only scope: Tasks 1, 6, 8, 9, and 10.
- User-level LaunchAgent and durable service: Tasks 5 and 8.
- Timed/indefinite modes and optional timed early-exit challenge: Tasks 2, 3, 4, and 7.
- 500-character no-hint challenge: Tasks 3 and 7.
- Exact relay origin, authenticated local socket, and Native Messaging: Tasks 5, 6, 8, and 9.
- Thirty-second restore warning and graceful Chrome quit: Tasks 2, 5, 6, and 10.
- Restart behavior and fail-less-destructive policy: Tasks 4, 5, 6, and 10.
- Existing extension privacy and behavior preservation: Task 9 and Task 10.

### Placeholder scan

The plan contains no unresolved markers or undefined interface names. Every
introduced production interface has a defining task before a consumer task.

### Type consistency

`StrictSession`, `StrictAction`, `TypingChallenge`, `ProtocolMessage`, and
`ChromeController` are defined in their producer tasks before later tasks use
them. The native host name and LaunchAgent label are fixed consistently across
Tasks 6, 8, and 9.
