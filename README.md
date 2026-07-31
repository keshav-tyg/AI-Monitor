# Local Focus Coach

A private, local-first Chrome extension for macOS. It notices when you have
been passively scrolling one of three specific feeds for a sustained stretch,
and then applies the intervention *you* configured — a notice, a pause screen,
closing the tab, or blocking that feed until tomorrow.

Everything happens on your machine. There is no account, no server, and no
network code in the extension at all.

## Install

```bash
npm install
npm run build
```

Then load it into Chrome:

1. Open `chrome://extensions`.
2. Turn on **Developer mode** (top right).
3. Click **Load unpacked**.
4. Select the `dist/` folder produced by `npm run build`.

Open the extension's **Options** page and turn on protection. Every rule ships
disabled — the extension enforces nothing until you ask it to.

For the optional on-device behaviour check, use Chrome 138+ and enable
`chrome://flags/#prompt-api-for-gemini-nano`. Chrome may download its local
model once. If it is unavailable, the declared-intent budget still works; the
model simply cannot override it or catch a contradictory declaration.

## What it watches

Only these three views, and nothing else:

| Feed | Route |
| --- | --- |
| Instagram Reels | `instagram.com/reels…` |
| X / Twitter timeline | `x.com/home`, `twitter.com/home` |
| YouTube Shorts | `youtube.com/shorts/…` |

Any other page — a profile, a search, a DM inbox, a normal YouTube video, any
other website — produces no events and no enforcement.

## How intent-aware sessions work

The extension treats your answer to a two-button question as the primary
control, not a hidden score. Entering a feed asks:

- **Doomscrolling — give me N minutes** — grants the per-site doomscroll budget
  you set in Options (five minutes by default).
- **Looking for something** — starts no timer.

A direct Reel/Short link or in-app search gets one item without a prompt. The
first advance past that item becomes a feed session. Ambiguous arrivals are
always treated as direct links, which is the less restrictive choice.

When a declared doomscroll budget is spent, the next feed advance raises a
full-page wall whose only action is Leave. This is an overlay rather than a URL
block, so a friend's direct link can still open. A wall stays up for that
declared session.

The optional local Chrome model sees only aggregate behaviour statistics —
item dwell time, completion, replays, mute/pause state, and aggregate action
counts. It never sees titles, captions, post text, URLs, or identifiers. It can
veto a doomscroll wall when behaviour looks deliberate, or stop a declared
purposeful session only at high confidence. Any model failure leaves you less
restricted: your declaration governs unchanged.

The original score ladder still provides the configurable notice/pause/close
and block interventions for sustained passive use. Its transparent signals are:

- **content advance** (+2) — the media source changed, or the timeline moved a
  full viewport
- **scroll** (+1) — throttled to at most one event every 750 ms

Two rules keep it honest:

- **Nothing scores for the first 120 seconds** in a view. Watching one Reel or
  one Short can never trigger anything.
- **A purposeful action multiplies the score by 0.25.** Searching, opening a
  profile or post, commenting, saving, messaging, or following a link all say
  "I meant to be here", and confidence drops accordingly.

A 90-second gap with no events ends the session and resets the score.

When the score crosses your warning threshold, escalation begins — and each
step costs a full grace period, so stopping means you never see the next one:

```
notify  →  (grace)  →  pause screen  →  (grace)  →  close tab
```

## Interventions

| Intervention | What happens |
| --- | --- |
| `notify` | A small banner in the corner, plus a Chrome notification. Nothing is blocked. |
| `pause` | A full-page overlay with the reason and two explicit choices: **Leave**, or **Continue for 5 minutes**. |
| `close-tab` | The offending tab closes. Only that tab. |
| `block` | A narrow rule blocks that feed's paths until midnight tonight. |

"Continue for 5 minutes" suppresses enforcement for that one tab only. It does
not change a declared session budget or touch any block.

## Privacy, exactly

Nothing leaves this device. This extension does not record screenshots, upload
browsing history, or send behavioral data to a server.

Concretely, it **does not**:

- make any network request — there is no HTTP client anywhere in the source,
  and a test in `tests/privacy-boundary.test.ts` fails the build if one appears
- capture screenshots or read page content, post text, comments, or messages
- store URLs, scroll coordinates, DOM text, or browsing history
- watch any other application, browser tab, or website
- sync anything: storage is `chrome.storage.local`, never `sync`

What it **does** store locally, in six keys:

- `settings` — your rules
- `usage` — minutes per feed for the current local day
- `interventions` — up to 200 past interventions with their plain-language
  reasons, plus your accurate/inaccurate feedback
- `blocks` — which feed is blocked and until when
- `return-pauses` — a pause that should be shown again if the feed is reopened
- `declarations` — the current per-site intent and its local budget state

Uninstalling the extension removes all of it.

## When it stops working

Instagram, X, and YouTube change their markup regularly. When they do,
detection may simply stop firing — the media-source probe finds no `video`
element, or a click no longer matches a known pattern.

**This fails open by design.** A missing selector, an unrecognised route, a
disabled rule, or an unreadable setting all produce *no* enforcement. The
extension will never close a tab or block a site because it got confused. If
interventions go quiet, assume a site redesign broke detection rather than
assuming you stopped scrolling.

## Scope

macOS Chrome only. This is a prototype: no mobile app, no desktop app, no
account, no cloud sync, no usage reporting of any kind, and no support for
feeds beyond the three listed above.

## Development

```bash
npm test          # vitest
npm run typecheck # tsc --noEmit
npm run build     # production bundle into dist/
```

Before releasing, work through `docs/manual-test-checklist.md` — several
guarantees here (tab closing, block expiry, no network traffic) can only be
confirmed in a real browser.
