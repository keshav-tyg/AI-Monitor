# Strict Mode macOS manual acceptance checklist

This checklist is for the optional Local Focus Coach Strict Mode companion.
It is a manual acceptance pass: it needs a macOS user session, Google Chrome,
a packed or loaded extension with a stable 32-letter ID, and a locally packaged
app image. It deliberately changes local Chrome and per-user LaunchAgent state.

## Scope and safety boundaries

- [ ] **Platform and browser.** Run this only on macOS with Google Chrome. It
      is not a mobile, cross-browser, cloud, or system-wide feature.
- [ ] **Local-only behavior.** Confirm the extension, Native Messaging relay,
      dashboard, and service stay on this Mac. Session state and the install
      secret are local; no account, server, remote control, or synchronization
      is involved.
- [ ] **User-level service.** Confirm the installer creates a user LaunchAgent,
      not a LaunchDaemon or administrator-level service:
      `~/Library/LaunchAgents/com.localfocuscoach.strict-service.plist`.
- [ ] **No tamper-proof claim.** Treat Strict Mode as an opt-in friction tool,
      not a way to stop a Mac owner from disabling or changing software.

## Preconditions and exact installation order

Do not perform this procedure against a production Chrome profile unless the
tester accepts the session and graceful-quit effects. Close or save unrelated
Chrome work first.

1. From the repository root, build the extension:

   ```sh
   npm run build
   ```

2. Open `chrome://extensions`, enable Developer mode, load `dist/`, and copy
   the extension's stable 32-letter ID. Keep this extension loaded; the Native
   Messaging registration allows exactly this ID.
3. From `desktop/`, package the app image with Java 21:

   ```sh
   JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
     ./gradlew jpackage
   ```

4. Copy `build/jpackage/Local Focus Coach.app` to its permanent absolute path.
   Do not move the app image after registration.
5. Register the companion, substituting the actual absolute app path and
   copied extension ID:

   ```sh
   ./installer/install-local-focus-coach.sh \
     --app-image "/absolute/path/Local Focus Coach.app" \
     --extension-id abcdefghijklmnopabcdefghijklmnop
   ```

   This creates only the user LaunchAgent and the Chrome user Native Messaging
   manifest. It needs no administrator access.
6. Launch `Local Focus Coach.app`, wait until the dashboard says the service is
   available, and only then start a Strict Mode session.

Record the test date, macOS version, Chrome version, extension ID used, and
the observed warning duration and Chrome quit result in the result table at
the end.

## Acceptance checks

Start each case from an idle dashboard unless it explicitly says to keep the
prior session. For a timed-expiry test, use a short duration that is long
enough to observe the status update.

- [ ] **LaunchAgent starts and restarts the service.** After registration,
      verify the dashboard reaches an available service. Restart the installed
      user service with `launchctl bootout gui/$(id -u) <plist>` followed by
      `launchctl bootstrap gui/$(id -u) <plist>` (or log out and back in), then
      confirm the dashboard reconnects. Do not test this against a system
      LaunchDaemon.
- [ ] **Dashboard may close during a session.** Start a session, close the
      dashboard window, wait briefly, relaunch the dashboard, and confirm the
      session is still active with its remaining state. The dashboard is a
      client; closing it must not stop the background session.
- [ ] **Timed mode expires automatically.** Start a timed session without
      early-exit challenge, let the countdown reach zero, and confirm it ends
      without an unlock challenge.
- [ ] **Timed early exit without the toggle is rejected.** Start a timed
      session with “Require a typing challenge for early exit” off. Confirm no
      unlock control is available and a request to exit early does not end the
      session.
- [ ] **Timed early exit with the toggle requires a challenge.** Start a timed
      session with that toggle on. Confirm the unlock control opens a challenge
      and the session remains active until the exact target is submitted.
- [ ] **Indefinite mode always requires a challenge.** Start an indefinite
      session. Confirm it has no automatic end and the only normal exit route
      is a successful challenge, regardless of the timed-mode toggle.
- [ ] **Challenge input behavior.** In a challenge, type and delete a character
      with Backspace; it must work. Try keyboard paste, context-menu paste, and
      drag/drop; none may add text. The target is 500 characters and submission
      stays unavailable until exactly 500 characters have been typed.
- [ ] **Challenge failure reveals no mismatch location.** Submit a 500-character
      value with one wrong character. Confirm the response is the generic
      “Challenge not complete,” with no index, highlighted character, prefix,
      suffix, or other mismatch hint. Retry and submit the exact target to
      confirm successful unlock.
- [ ] **Extension disconnect starts one 30-second warning.** During an active
      session with Chrome running, disable the extension in `chrome://extensions`.
      Confirm the dashboard shows “Restore the Chrome extension” and a countdown.
      Measure the interval from first warning to expiry; record it below. It
      should be 30 seconds, subject only to normal UI/clock observation delay.
- [ ] **Reconnect cancels the warning.** Before the deadline, re-enable the
      same extension. Confirm the warning disappears, the session remains
      active, and no Chrome quit is requested.
- [ ] **Expiry requests a graceful Chrome quit.** Repeat the disconnect and do
      not restore the extension. Save any test work first. Confirm Google Chrome
      receives a normal quit request at the end of the warning (rather than a
      force kill), then record whether it closed gracefully and any Chrome UI
      that intervened.
- [ ] **Closed Chrome does not warn.** With an active session, quit Chrome
      yourself while the extension connection is healthy. Confirm no restoration
      warning starts solely because Chrome is closed.
- [ ] **Service restart restores a session.** Start a session and note its
      status. Restart the user LaunchAgent as in the first check, reopen or
      refresh the dashboard, and confirm the active session is restored. For a
      timed session, confirm the original deadline—not a fresh duration—is used.
- [ ] **Ownership-safe uninstall removes only companion registrations.** With
      the installation still unchanged, run:

      ```sh
      ./installer/uninstall-local-focus-coach.sh
      ```

      Confirm it removes only the companion LaunchAgent and Native Messaging
      manifest. Confirm it leaves the app image, session database, install
      secret, logs, and unrelated LaunchAgent/native-host files. For an
      ownership-safety negative check, modify a copy of a registration first;
      uninstall must preserve that changed copy and report that it was preserved.

## Results to record

| Item | Observed result | Pass / fail |
| --- | --- | --- |
| macOS and Chrome version; extension ID | | |
| LaunchAgent service start/restart | | |
| Dashboard close/relaunch session persistence | | |
| Timed and indefinite exit rules | | |
| Challenge input and generic failure behavior | | |
| Disconnect warning duration (seconds) | | |
| Reconnect cancellation | | |
| Chrome graceful-quit result at warning expiry | | |
| Closed Chrome behavior | | |
| Session after service restart | | |
| Ownership-safe uninstall | | |
