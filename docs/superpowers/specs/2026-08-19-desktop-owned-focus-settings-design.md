# Desktop-Owned Focus Settings Design

## Goal

Move every editable focus-protection setting from the Chrome extension into
the Local Focus Coach macOS dashboard. The desktop service becomes the sole
authoritative owner of settings; Chrome enforces a validated, read-only copy.

This covers the existing master protection switch and every per-site rule:

- enabled state;
- doomscroll session budget;
- warning score;
- grace period;
- ordered interventions.

Strict Mode remains a separate dashboard feature. It determines whether the
desktop can accept changes that would weaken currently protected rules.

## Current State

The extension currently stores `Settings` in `chrome.storage.local`. Its
Options page directly writes the master toggle and all site rule fields. The
service worker reads those settings while deciding interventions. The desktop
application currently presents only the Strict Mode UI, and the native
messaging link is already authenticated through the relay and local service.

## Architecture

### Authoritative storage

Add a versioned `FocusSettings` record to the desktop SQLite database. A
record contains the complete validated settings document plus a monotonically
increasing revision number. The service is the only writer.

The service exposes authenticated IPC requests to:

- read the current settings and revision;
- save a complete proposed settings document after validation;
- open the dashboard on an explicit user action from the extension.

Saving is atomic: invalid input returns an error and leaves the current record
unchanged. Each successful write increments the revision before the service
notifies connected extension relays.

### Desktop dashboard

The JavaFX dashboard gains a Focus Rules section alongside the existing Strict
Mode section. It contains a master protection switch and an expandable card
for every supported site (Instagram Reels, X timeline, and YouTube Shorts).
Each card presents the current rule controls and intervention order.

The dashboard reads and saves through `ServiceClient`; it never writes SQLite
directly. It displays save results and Chrome-sync status:

- **Synced with Chrome** when a connected extension has acknowledged the
  current revision;
- **Waiting for Chrome** when settings are saved but no extension is
  connected;
- **Could not save** when the service rejects a request or cannot be reached.

During an active Strict Mode session, changes that weaken an active protected
rule are rejected. Changes that keep a rule unchanged or make it stricter are
accepted. This preserves the user’s active commitment without preventing them
from strengthening protection.

### Extension and relay

The extension stops treating `chrome.storage.local` as a settings source of
truth. On native bridge startup and every reconnect it asks the service for the
latest settings. It validates the payload, applies it in memory, persists it
only as a last-known-good enforcement cache, and acknowledges the revision.

The service pushes a new settings message after each successful dashboard
save. The extension accepts only valid, newer revisions. If the connection is
temporarily unavailable, it continues to enforce its last-known-good cache and
requests an update when it reconnects.

The native relay forwards these settings requests, responses, updates, and
acknowledgements over the existing authenticated local channel. No browsing
content, screenshots, or behavioral records are sent to the desktop service.

### Chrome Options page

The extension Options page is replaced by a small read-only page explaining
that Local Focus Coach manages settings on this Mac. Its only action is **Open
Local Focus Coach**, which sends an authenticated `dashboard.open` request to
the service. The service opens the installed dashboard application using its
known packaged location. The page has no setting inputs and no extension-side
save path.

## First-Run Migration

On the first authenticated extension-to-service connection after this release,
the extension sends its existing local settings only when the desktop database
has no Focus Settings record. The service validates and saves that document as
revision 1, then returns it as the authoritative value.

If a desktop settings record already exists, it always wins and replaces the
extension’s cached settings. Migration is idempotent: a completed import is
recorded and cannot overwrite later dashboard edits.

## Error Handling

- Invalid schema, unknown sites, non-finite numbers, out-of-range values, and
  invalid intervention sequences are rejected by the service.
- A failed dashboard save leaves visible controls intact and reports the
  failure without overwriting the saved revision.
- The extension ignores invalid or stale updates and continues enforcing the
  last valid cache.
- A disconnected extension does not block normal dashboard edits; the app
  reports a pending sync state.
- The service only opens the dashboard after a user-initiated, authenticated
  extension command; it never interprets web content as an open request.

## Testing

Add focused tests for:

1. SQLite persistence, validation, revisions, and atomic rejected saves.
2. Service IPC authorization, reads, saves, desktop-open requests, and Strict
   Mode weakening checks.
3. Relay forwarding and acknowledgement of revisions.
4. Extension startup/reconnect reads, push updates, cache fallback, schema
   rejection, and first-run migration.
5. JavaFX Focus Rules rendering, field validation, save feedback, pending-sync
   status, and Strict Mode edit restrictions.
6. The browser Options page containing no editable controls and correctly
   requesting the dashboard open action.
7. End-to-end compatibility of the native message protocol and existing strict
   enforcement behavior.

## Non-Goals

- Synchronizing settings between devices.
- Sending any browsing data to a server or external AI service.
- Allowing the browser extension to edit desktop-owned settings.
- Changing the existing Strict Mode unlock challenge behavior.
