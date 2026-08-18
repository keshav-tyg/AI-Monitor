# macOS Strict Mode Companion — Design

**Status:** Approved for planning

## Goal

Add an opt-in macOS companion to Local Focus Coach. During a Strict Mode
session, it verifies that the Google Chrome extension remains enabled. If
Chrome is open and the extension connection disappears, it gives the user a
30-second warning to restore the extension, then gracefully quits Chrome if
the connection is still absent.

This is a commitment device, not a security boundary. A person who controls
their Mac can stop a user-level service or change local files. The product must
describe that limitation plainly and never imply tamper-proof enforcement.

## Scope

The first release supports:

- macOS only;
- Google Chrome only;
- the existing Manifest V3 extension;
- a user-level background service managed by `launchd`;
- timed and indefinite Strict Mode sessions;
- local-only storage and communication.

It does not add accounts, cloud sync, other browsers, mobile support, or a
root-level system daemon.

## Session modes

At Strict Mode activation, the person chooses one mode:

| Mode | End condition | Early unlock |
| --- | --- | --- |
| Timed | The selected end time arrives. | Optional 500-character typing challenge when the user enabled it for that session. |
| Indefinite | The user completes the typing challenge. | Always requires the typing challenge. |

Timed sessions end automatically at their selected end time; no challenge is
required at normal expiry. The typing challenge is deliberate friction for an
early exit, not a punishment for a typo.

## Typing challenge

The Java service creates a cryptographically random 500-letter target whenever
an unlock attempt begins. The dashboard displays the target and accepts normal
typing and backspace corrections.

The dashboard blocks paste, drag-and-drop, and common clipboard shortcuts. It
does not highlight a wrong character, reveal the first mismatch, or otherwise
identify a mistake. It enables the final unlock only when the full submitted
string matches exactly. The challenge is discarded after a successful unlock
or a service restart.

This raises the effort of an impulsive exit but does not claim to defeat input
automation or a user with control of the operating system.

## Components

```text
Chrome extension (TypeScript)
  <-> Chrome Native Messaging
Java relay (Chrome-spawned native host)
  <-> authenticated Unix-domain socket
Java Strict Mode service (launchd LaunchAgent)
  <-> authenticated Unix-domain socket
JavaFX dashboard
  <-> SQLite
Java Strict Mode service
```

### Java Strict Mode service

The service is the durable source of truth. A user-level LaunchAgent starts it
at login and relaunches it after an unexpected exit. It owns:

- active-session state and expiry scheduling;
- extension connection health;
- the 30-second restore warning;
- graceful Google Chrome quit requests;
- challenge creation and verification;
- SQLite persistence;
- authenticated local IPC for the dashboard and relay.

The service runs in the logged-in user session and requires no administrator
privileges. It is separate from the JavaFX process so closing the dashboard
does not disable an active session.

### JavaFX dashboard

The dashboard starts timed or indefinite sessions, shows active status and a
connection-loss countdown, and hosts the early-unlock challenge. It does not
directly control Chrome or own enforcement state; every action is an
authenticated command to the service.

### Chrome native-messaging relay

Chrome starts the relay as its native-messaging host. The relay validates the
Chrome-provided extension origin and forwards only a small protocol to the
service: connect, heartbeat, disconnect, and service-state acknowledgement.

The extension gains the `nativeMessaging` permission and keeps one minimal
`runtime.connectNative()` port open while Chrome is running. This lets the
service see an already-healthy extension immediately when a Strict Mode session
starts, rather than confusing a normal start-up delay with a disabled extension.
A native connection keeps the Manifest V3 service worker alive. The extension
reconnects after a port disconnect. The host manifest permits only the
production extension ID. Outside Strict Mode this connection is informational;
it carries no browsing or feed data.

### Existing extension

The existing feed detectors, local scoring rules, intent sessions, overlays,
and optional Gemini Nano classifier remain TypeScript and local-first. Strict
Mode adds only the extension-integrity bridge; it does not move browsing data
to the Java service.

## Connection health and enforcement

The service distinguishes Chrome being closed from the extension being absent:

1. With no active Strict Mode session, relay state is informational only.
2. During an active session, a healthy relay connection resets the connection
   deadline.
3. If the relay disconnects and Google Chrome is not running, the service does
   not warn or attempt a quit.
4. If Chrome is running without a healthy connection, the service presents a
   30-second restore warning through the dashboard/notification layer.
5. A reconnect cancels that warning immediately.
6. If the countdown expires while Chrome is still running and disconnected, the
   service sends a graceful quit request specifically to Google Chrome.
7. The Strict Mode session remains active after Chrome closes. Reopening Chrome
   without the extension starts the same warning cycle.

The first version does not force-kill Chrome. This avoids treating a temporary
application delay as permission to discard unrelated browser work.

## Data and IPC

SQLite lives in the app-support directory and records only local Strict Mode
state: session ID, mode, start time, timed end time when present,
early-unlock-challenge setting, current status, and audit timestamps. It stores
no URLs, feed content, screenshots, browsing history, or Gemini prompt data.

The service creates a Unix-domain socket in a user-only runtime directory. The
dashboard and relay authenticate with a per-install secret stored with
restrictive file permissions. IPC messages are versioned and limited to:

- dashboard: start session, request status, begin unlock, submit unlock, stop
  a timed session after successful challenge;
- relay: connect, heartbeat, disconnect;
- service: session status, warning state, challenge result.

## Packaging and installation

The macOS package bundles the Java service, JavaFX dashboard, relay launcher,
and a private Java runtime. Installation registers:

- a per-user `launchd` LaunchAgent for the service;
- a Google Chrome native-host manifest under the user-specific Chrome
  `NativeMessagingHosts` directory;
- an exact extension ID in that host manifest.

The production extension must use a stable ID so the native-host allowlist does
not change between installations. Development builds use a separate native-host
configuration and never share the production allowlist.

## Failure behavior

- Service crash: `launchd` restarts it; the persisted session is reloaded.
- Dashboard crash or quit: no effect on service enforcement.
- Relay crash: connection-loss warning starts only when Chrome is running;
  the extension reconnects when its port closes.
- Extension disabled: native connection disappears; normal 30-second warning
  and Chrome quit sequence applies.
- Chrome closed: no warning; the active session remains stored.
- Bad or unknown IPC input: reject it and leave the session unchanged.

Every uncertain condition fails toward less destructive behavior: there is
always a warning period, quitting is limited to Google Chrome, and no force
kill occurs.

## Testing

- Unit-test the strict-session state machine with an injected clock.
- Unit-test timed expiry, early-unlock requirements, the 500-character
  challenge, and no-mismatch-feedback behavior.
- Test SQLite restart recovery and idempotent session changes.
- Test relay framing, authentication, origin validation, disconnect handling,
  and protocol-version rejection.
- Test the extension native client for reconnect and no-network guarantees.
- Run a manual macOS checklist: install, LaunchAgent start/restart, extension
  enable/disable warning, reconnect cancellation, Chrome quit, timed expiry,
  indefinite unlock, and dashboard closure while Strict Mode stays active.

## Sources

- [Chrome Native Messaging](https://developer.chrome.com/docs/extensions/develop/concepts/native-messaging)
- [Chrome extension service-worker lifecycle](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)
- [Apple Service Management](https://developer.apple.com/documentation/servicemanagement/)
