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

## What it watches

Only these three views, and nothing else:

| Feed | Route |
| --- | --- |
| Instagram Reels | `instagram.com/reels…` |
| X / Twitter timeline | `x.com/home`, `twitter.com/home` |
| YouTube Shorts | `youtube.com/shorts/…` |

Any other page — a profile, a search, a DM inbox, a normal YouTube video, any
other website — produces no events and no enforcement.

## How detection works

There is no model and no guesswork you cannot inspect. Confidence is a running
score built from two signals:

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

Spending your daily allowance for a feed opens the same ladder, with "Daily
allowance reached" as the reason. It does not skip ahead — you are always told
before anything drastic happens.

Note that a very short allowance makes the score path unreachable: scoring
cannot begin until 120 seconds in a view, so an allowance under two minutes
will always trigger first.

## Interventions

| Intervention | What happens |
| --- | --- |
| `notify` | A small banner in the corner, plus a Chrome notification. Nothing is blocked. |
| `pause` | A full-page overlay with the reason and two explicit choices: **Leave**, or **Continue for 5 minutes**. |
| `close-tab` | The offending tab closes. Only that tab. |
| `block` | A narrow rule blocks that feed's paths until midnight tonight. |

"Continue for 5 minutes" suppresses enforcement for that one tab only. It does
not change your daily allowance and does not touch any block.

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

What it **does** store locally, in four keys:

- `settings` — your rules
- `usage` — minutes per feed for the current local day
- `interventions` — up to 200 past interventions with their plain-language
  reasons, plus your accurate/inaccurate feedback
- `blocks` — which feed is blocked and until when

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
