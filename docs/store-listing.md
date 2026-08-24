# Chrome Web Store listing copy

Paste each field into the corresponding CWS submission field. Keep this file in
sync with the manifest so review, listing, and code all say the same thing.

## Extension name

```
Local Focus Coach
```

## Short description (max 132 characters)

```
Notices sustained passive feed use on Instagram, X, and YouTube Shorts and applies your rules. Nothing leaves this device.
```

## Category

`Productivity` (secondary: `Accessibility`).

## Detailed description

```
Local Focus Coach is a private, local-first tool for macOS Chrome. It watches
three specific views — Instagram Reels, X/Twitter timeline, YouTube Shorts —
and, once you land on one, asks whether you meant to. Declare a doomscroll
session and a timer starts against the budget you set. When the budget is
spent, the feed is covered by a wall whose only action is Leave.

Everything happens on your machine.

- No account, no server, no cloud sync, no analytics.
- No network requests. A test in the source (tests/privacy-boundary.test.ts)
  fails the build if a fetch, XHR, WebSocket, sendBeacon, or captureVisibleTab
  call ever appears.
- No screenshots, no page text, no post content, no comment content, no
  URLs beyond the site key, and no scroll coordinates ever leave the
  extension.
- No sync. Storage is chrome.storage.local, never chrome.storage.sync.

Local Focus Coach requires the companion Local Focus Coach for macOS app,
which is where you edit your rules. Chrome and the desktop app talk over
Chrome Native Messaging on your own machine.

Optional on-device model: on Chrome 138+ with
chrome://flags/#prompt-api-for-gemini-nano enabled, an on-device Gemini Nano
model can act as an honesty check on your declaration. It sees only aggregate
statistics (item dwell time, completion, replays, mute/pause flags, aggregate
action counts) — never titles, captions, or URLs. If the model is unavailable,
your declaration governs unchanged.

How the flow works, in one paragraph:

Open Reels/Shorts/timeline. A full-page overlay asks "Hey, what are we doing
here?" with one button, "Doomscrolling — give me N minutes", where N is the
budget you set. Click it and your session starts. The extension icon shows a
live timer. When the budget is spent, the next scroll raises a wall covering
the feed with a single Leave button. A direct link to a specific Reel or
Short still opens without a prompt — one item, no wall — and only becomes a
feed session if you advance past that item.

Uninstalling removes all local data.
```

## Screenshots

Required: at least one, up to five, at 1280×800 or 640×400 PNG/JPEG.

Suggested captures against the current build:

1. The intent prompt on Instagram Reels (dark overlay, one button quoting the
   configured budget).
2. The extension popup during a live session showing the timer counting.
3. The wall raised after the budget is spent, showing the reason and the Leave
   button.
4. The desktop dashboard rule editor with Instagram Reels toggled on.
5. The Options page's Recent activity list showing session started, timer
   ended, wall shown, leave pressed.

Capture with `⌘⇧5 → Options → Capture Selected Window` on macOS at 1× (native
retina). Downscale to 1280×800 if needed. Every screenshot should have real
content behind it, not the New Tab page.

## Promotional images (optional)

- Small tile: 440×280 PNG/JPEG.
- Marquee: 1400×560 PNG/JPEG.

Skip both for the initial submission; add later if the listing wants a bigger
presence.

## Privacy policy URL

The policy is checked into `docs/privacy-policy.md`. Before submission, publish
it at a stable URL — a GitHub Pages `docs/` build of the repository is enough
— and paste that URL into the CWS "Privacy" form.

## Single purpose

```
Interrupt sustained passive use of Instagram Reels, X/Twitter timeline, and
YouTube Shorts, using rules the person set for themselves in the companion
desktop app.
```

## Permission justifications (CWS asks per permission)

- **storage** — persists the user's per-site rules, the current declaration
  and its consumed time, blocks, activity timeline, and intervention history.
  All in `chrome.storage.local`.
- **declarativeNetRequest** — installs narrowly scoped, temporary "Block
  until tomorrow" rules on the three watched feed paths only, when the
  person's rule selects that intervention.
- **notifications** — surfaces the optional Chrome notification that
  accompanies the "Notify me" intervention.
- **alarms** — clears an expired "Block until tomorrow" rule after midnight
  even when no page loaded to wake the service worker.
- **offscreen** — hosts the on-device Prompt API session, which the Manifest
  V3 service worker cannot run itself.
- **nativeMessaging** — reads rules from and reports state to the companion
  Local Focus Coach for macOS app.

## Host permission justification

```
Instagram, X/Twitter, and YouTube are the three sites this extension watches.
Access is restricted to their domains. No other site is observed and no
network requests are made from any of these origins.
```

## Remote code

None. Load the extension with `Remote code use` set to **No**. This is
enforceable and true: `tests/privacy-boundary.test.ts` fails the build if a
fetch/XHR/WebSocket/sendBeacon/captureVisibleTab call appears in `src/`.

## Notes for the reviewer

The extension requires the companion Local Focus Coach for macOS app to edit
rules. The Options page in Chrome is a launcher only. Without the desktop app
running, the extension has no rules to enforce and does nothing — which is
the correct behaviour. Reviewers testing the extension in isolation will see
this. Download link for the desktop app goes in the store listing description.
