# Local Focus Coach — privacy policy

**Effective date:** 2026-08-22

Local Focus Coach ("the extension") is a Chrome browser extension for macOS
that helps you notice sustained passive use of Instagram Reels, X/Twitter
timeline, and YouTube Shorts and applies rules you configured for yourself in
the companion Local Focus Coach for macOS application ("the desktop app").

## Data we collect

**None.** The extension makes no network requests. There is no server, no
account, no analytics, and no telemetry. `tests/privacy-boundary.test.ts`
fails the build if any HTTP client, WebSocket, `sendBeacon`, or
`captureVisibleTab` call appears in the extension source.

## Data stored on your device

The extension writes to `chrome.storage.local` only. It never uses
`chrome.storage.sync`. The keys it stores are:

- `settings` — the rules you set (per-site enable flag, budget in minutes,
  score threshold, grace period, and intervention ladder).
- `usage` — foreground milliseconds per site for the current local calendar
  day. Reset at your local midnight.
- `interventions` — up to 200 past interventions, each recording site,
  intervention kind, and a plain-language reason built from your own settings
  and counters, plus your accurate/inaccurate feedback.
- `blocks` — which site is currently blocked and when the block expires.
- `return-pauses` — a pending pause to show once when you re-enter a feed.
- `declarations` — the current per-site declaration, its consumed time in
  milliseconds, and whether the wall has been raised.
- `activity` — up to 200 timeline rows: session started, timer ended, wall
  shown, leave pressed. Details are strings built from your own settings.

Every one of these lives only on your device. Uninstalling the extension
removes all of them.

## What we do not store

The extension does not read, store, or transmit:

- Any content of a Reel, Short, tweet, post, comment, or message.
- URLs beyond the site key (`instagram-reels`, `x-timeline`,
  `youtube-shorts`).
- Screenshots or any visual capture of any page.
- Scroll coordinates, mouse coordinates, or any per-pixel data.
- Your browsing history from any other site.
- Cookies, session tokens, or authentication material.
- Personal information such as your name, email, or IP address.

## Optional on-device model

On Chrome 138 or later with `chrome://flags/#prompt-api-for-gemini-nano`
enabled, the extension can invoke Chrome's built-in on-device Gemini Nano
model as an honesty check on your own declaration. The model receives only
aggregate statistics — item dwell time, completion fraction, replay counts,
mute/pause flags, and aggregate action counts — never titles, captions,
URLs, or identifiers. The model runs entirely inside your copy of Chrome and
no data leaves your device. If the model is unavailable, your declaration
governs unchanged.

## Companion desktop app

The extension talks to the companion Local Focus Coach for macOS app over
Chrome Native Messaging on your machine. This channel is used only to read
the rules you configured and to open the desktop dashboard. Nothing crosses
the network.

## Permissions and why

- `storage` — persist the rules and state above.
- `declarativeNetRequest` — install temporary "Block until tomorrow" rules on
  the three watched feed paths only, when your rule selects that.
- `notifications` — surface the Chrome notification that accompanies "Notify
  me".
- `alarms` — clear an expired block after midnight even when nothing else
  wakes the service worker.
- `offscreen` — host the on-device Prompt API session (Manifest V3 service
  workers cannot).
- `nativeMessaging` — talk to the companion Local Focus Coach for macOS app.
- Host permissions on `instagram.com`, `x.com`, `twitter.com`, `youtube.com`
  — the three sites this extension watches; no other site is observed.

## Changes

If we change this policy, the effective date at the top of this document
changes with it. Prior versions remain in the source repository's git
history.

## Contact

File an issue at the extension's source repository. The extension collects no
contact information and therefore cannot notify you individually.
