# Local Focus Coach

A private, local-first Chrome extension for macOS. It notices when you have
been passively scrolling one of three specific feeds for a sustained stretch,
then applies the intervention you configured — a notice, a pause screen,
closing the tab, or blocking that feed until tomorrow.

Everything happens on your machine. There is no account, no server, and no
network code in the extension at all.

## For users

Local Focus Coach ships as two pieces that talk to each other on your Mac:

- A **Chrome extension** that watches Instagram Reels, X/Twitter timeline,
  and YouTube Shorts.
- A **macOS companion app** where you set your rules. The Chrome Options
  page is a launcher; every rule lives in the app.

Neither piece does anything on its own. Install both.

### Install

1. Install the extension from the **[Chrome Web Store][cws]** *(pending
   approval — link goes live once Google reviews the submission)*.

2. Download **[Local Focus Coach for macOS][gh]** from GitHub Releases.
   Unarchive the `.zip` and drag `Local Focus Coach.app` into your
   `Applications` folder.

3. **Right-click** the app in Finder → **Open** → confirm the Gatekeeper
   dialog once. Double-clicking is silently blocked because the app is
   signed for direct distribution rather than through the App Store. This
   only happens the first time.

   The first launch registers the Chrome native-messaging host for you.
   No Terminal required. The app reopens itself on your next login.

4. Open the desktop app, enable a rule, set a session budget.

5. Visit a watched feed. The intent prompt appears. Done.

If the extension Options page reports **Local Focus Coach is unavailable**,
the app is not running or the native-messaging host is not registered.
Launch the app from Applications and retry.

[cws]: https://chrome.google.com/webstore/detail/llgkbdfkmgjpmlammmnidndocedopmol
[gh]: https://github.com/keshav-tyg/AI-Monitor/releases/latest

## What it watches

Only these three views, and nothing else:

| Feed | Route |
| --- | --- |
| Instagram Reels | `instagram.com/reels…` |
| X / Twitter timeline | `x.com/home`, `twitter.com/home` |
| YouTube Shorts | `youtube.com/shorts/…` |

Any other page — a profile, a search, a DM inbox, a normal YouTube video,
any other website — produces no events and no enforcement.

## How intent-aware sessions work

The extension treats an explicit start as the primary control, not a hidden
score. Entering a feed asks:

**"Doomscrolling — give me N minutes"** — grants the per-site doomscroll
budget you set in the desktop dashboard (five minutes by default). The
extension icon shows a live timer counting that budget up toward it.

That is the only button. There is no "just looking" escape hatch and no
free text: pressing Escape leaves the prompt where it is. Entering a feed
on purpose means starting a session on purpose.

The budget is spent against **foreground time on that feed**, not
wall-clock time. Switching tabs pauses it. Closing the tab, refreshing, or
reloading the extension does not reset it: the declaration and the
milliseconds it has already consumed live in `chrome.storage.local`, so
only its 30-minute cooldown expires it. That counter belongs to the
session rather than to the day, so a session declared at 23:59 keeps its
remaining budget through midnight.

A direct Reel/Short link or in-app search gets one item without a prompt.
The first advance past that item becomes a feed session. Ambiguous
arrivals are always treated as direct links — the less restrictive choice.

When a declared doomscroll budget is spent, the next feed advance raises a
full-page wall whose only action is Leave. This is an overlay rather than
a URL block, so a friend's direct link can still open. A wall stays up
for that declared session.

## Focus sensitivity

The score ladder is a secondary safety net for feeds where the person
never declared anything. Choose how quickly it should step in:

- **Mild** — score 10 — intervenes after more sustained passive scrolling
- **Medium** — score 5 — a balanced reminder
- **Aggressive** — score 1 — intervenes quickly after passive scrolling begins

Its transparent signals:

- **content advance** (+2) — the normalized YouTube `/shorts/<id>` route
  changed, the Instagram media source changed, or the X timeline moved a
  full viewport
- **scroll** (+1) — throttled to at most one event every 750 ms

Two rules keep it honest:

- **Nothing scores for the first 120 seconds** in a view. Watching one Reel
  or one Short can never trigger anything.
- **A purposeful action multiplies the score by 0.25.** Searching, opening
  a profile or post, commenting, saving, messaging, or following a link
  all say "I meant to be here", and confidence drops accordingly.

A 90-second gap with no events ends the session and resets the score.
When the internal score reaches the selected sensitivity threshold,
escalation begins — each step costs a full grace period, so stopping
means you never see the next one:

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

**Continue for 5 minutes** suppresses enforcement for that one tab only.
It does not change a declared session budget or touch any block.

## The optional on-device model

Chrome 138+ can host Google's on-device Gemini Nano model behind
`chrome://flags/#prompt-api-for-gemini-nano`. If it is enabled and
downloaded, the extension uses it as a second opinion — never as the
detector, never as the source of enforcement.

Once a declared session has at least five viewed items, the extension may
ask the local model at most once every 20 seconds whether the aggregate
behaviour matches what the person declared. The model receives only the
supported feed key, declared intent, entry type, session duration, item
count, median dwell and completion, fully-watched count, replay and
unmuted counts, purposeful-action count, and scroll-burst count. It never
sees titles, captions, post text, URLs, or identifiers. It returns a
schema-checked verdict with a confidence score and a short reason.

- For a **declared doomscroll** session whose budget has elapsed, a
  confident indication that the behaviour looks deliberate can prevent
  the wall.
- For a **declared purposeful** session, only a high-confidence indication
  that the behaviour looks like passive scrolling can raise a wall.
- It never touches a direct-link item, never decides Focus sensitivity,
  never decides Strict Mode, never decides browser-close behaviour.

If Gemini Nano is missing, unavailable, slow, or uncertain, the
deterministic rules run unchanged.

## Privacy, exactly

Nothing leaves this device. This extension does not record screenshots,
upload browsing history, or send behavioral data to a server.

Concretely, it does not:

- make any network request — there is no HTTP client anywhere in the
  source, and a test in `tests/privacy-boundary.test.ts` fails the build
  if one appears
- capture screenshots or read page content, post text, comments, or
  messages
- store URLs, scroll coordinates, DOM text, or browsing history
- watch any other application, browser tab, or website
- sync anything: storage is `chrome.storage.local`, never `sync`

What it **does** store locally in Chrome, in eight keys:

- `settings` — a retired browser-owned record, retained only for the
  one-time desktop import
- `desktop-settings-snapshot` — the newest valid desktop revision, used
  only as the last-known-good enforcement cache
- `usage` — foreground milliseconds per feed for the current local day
- `interventions` — up to 200 past interventions with their plain-language
  reasons, plus your accurate/inaccurate feedback
- `blocks` — which feed is blocked and until when
- `return-pauses` — a pause that should be shown again if the feed is
  reopened
- `declarations` — the current per-site intent and its consumed time
- `activity` — up to 200 timeline entries: session started, timer ended,
  wall shown, leave pressed

Uninstalling the extension removes these entries. The authoritative Focus
Rules record remains separately in the local desktop companion database.

Full policy: [docs/privacy-policy.md](docs/privacy-policy.md), published at
`https://keshav-tyg.github.io/AI-Monitor/privacy-policy`.

## Activity timeline

The Chrome Options page shows a plain local diary of every declared
session, newest first:

| Entry | When it is written |
| --- | --- |
| Session started | You answered the prompt |
| Timer ended | A doomscroll budget was spent |
| Wall shown | The wall went up, once per session, with the reason |
| Leave pressed | You left from the wall |

It holds no page content — only the site, the moment, and a sentence built
from your own settings.

## Architecture

```
content script  ──events──▶  service worker  ──commands──▶  content script
  provenance                   declarations                    prompt / wall
  engagement                   budget + wall                   pause / notice
  overlays                     activity log
                                    │
                                    ├─ chrome.storage.local (8 keys)
                                    ├─ offscreen document ─▶ on-device model
                                    └─ native messaging ─▶ macOS companion
```

| Layer | Files | Responsibility |
| --- | --- | --- |
| Content | `src/content/` | Detect the view, classify how you arrived, collect per-item engagement, render every overlay. Owns no decisions. |
| Engine | `src/engine/` | Pure functions: `declaration.ts` (budget + session state machine), `session-summary.ts` (aggregate payload), `score.ts`, `rules.ts`. No DOM, no storage, no clock of their own. |
| Worker | `src/background/` | The only place that decides or enforces. Owns declarations, per-tab arrival state, the wall, the activity log, and the native-messaging client. |
| Offscreen | `src/offscreen/` | Hosts the Prompt API, which cannot run in an MV3 service worker, and keeps one session warm. |
| Shared | `src/shared/` | Types, constants, and the local-only storage layer. |
| Desktop | `desktop/` | JavaFX rule editor and Strict Mode, packaged as `Local Focus Coach.app`. |

Per-tab state (arrival kind, advances, engagement) is deliberately
transient — it dies with the worker. Anything that must survive a teardown
is persisted, so an idle worker, an extension reload, or a browser restart
cannot hand back a budget you already spent.

## Limitations

Known and accepted, not bugs:

- **The wall is dismissible.** Leaving and coming back gets past it for
  one item. A hard block would break the friend's-link case, which
  matters more.
- **Provenance is best-effort.** Referrers are often empty, so some feed
  entries look like direct links and are not prompted. Under-prompting is
  the deliberate choice.
- **The model is optional and not always right.** It needs Chrome 138+ and
  a flag, and a small on-device model can misjudge. It can only *prevent*
  enforcement under a doomscroll declaration; it can end a purposeful
  session only at 0.8 confidence, and never touches a direct-link item.
- **Detection uses conservative site signals.** YouTube Shorts advances
  use the normalized `/shorts/<id>` route as their authoritative signal.
  Instagram media-source changes, X timeline movement, and recognized
  click targets still depend on current page behaviour and markup, so a
  redesign can silently stop those signals.
- **The macOS `.app` is not Developer ID signed or notarized.** First
  launch requires a right-click → Open. No auto-updates; every release is
  a manual re-download.
- **Three feeds only. macOS Chrome only. One profile, no sync.**

## When it stops working

Instagram, X, and YouTube change their markup and routing regularly. When
they do, detection may simply stop firing — Instagram's media-source probe
may find no `video`, an X or purposeful-click signal may no longer match,
or YouTube may stop exposing a valid `/shorts/<id>` route. A missing
Shorts route identifier is ignored; selector or media-source changes are
not used as a fallback guess.

**This fails open by design.** A missing selector, an unrecognised route,
a disabled rule, or an unreadable setting all produce *no* enforcement.
The extension will never close a tab or block a site because it got
confused. If interventions go quiet, assume a site redesign broke
detection rather than assuming you stopped scrolling.

## Local macOS companion and Strict Mode

The macOS app owns Focus Rules and optionally provides Strict Mode for an
active Chrome session. It is supported only with Google Chrome on macOS.
The extension, its Native Messaging relay, and the companion service
communicate only on this computer; session data and the installation
secret are stored locally. There is no account, cloud service, remote
control, or cross-device synchronization.

Strict Mode is deliberately not tamper-proof. A person who controls the
Mac can disable software, alter or remove registrations, or otherwise
bypass it. The companion does not claim to prevent that. Its purpose is
to make an opt-in commitment more deliberate while preserving a local,
user-controlled setup.

When an active Strict Mode session loses its extension connection while
Chrome is running, the service presents a local macOS warning even if the
dashboard is closed; the dashboard also shows a 30-second countdown when
open. Re-enable the extension before that deadline to cancel the warning.
If it expires, the companion asks Chrome to quit gracefully; it does not
force-kill Chrome. A closed Chrome instance does not start a warning, and
reopening Chrome without the extension starts a fresh warning cycle while
the session remains active.

## For developers

Building from source is only needed to work on the code, or to load an
unpacked development copy for testing. Users install from the store.

```bash
git clone https://github.com/keshav-tyg/AI-Monitor.git
cd AI-Monitor
npm install
```

Two build shapes:

```bash
npm run build             # development: "Local Focus Coach (Development)", no key, random ID
npm run build:production  # production: "Local Focus Coach", no key, ready for CWS upload
```

Load `dist/` in `chrome://extensions` with Developer mode on. A
development build only talks to the `_dev` native-messaging host — the
production host name is reserved for the CWS-installed extension.

To reproduce the store-assigned extension ID on an unpacked build (so it
can share the production native-messaging registration), set the
CWS-published public key at build time:

```bash
export LFC_EXTENSION_PUBLIC_KEY="<base64 DER SubjectPublicKeyInfo from CWS>"
LFC_EXTENSION_CHANNEL=production npm run build:production
```

CWS publishes the assigned public key on the item's Package tab. This is
only for local iteration — a keyed manifest cannot be uploaded back to
CWS.

### macOS app

From `desktop/`, with Java 21 installed:

```bash
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew jpackage
```

Writes `desktop/build/jpackage/Local Focus Coach.app`. Copy the identity
file that names the CWS-assigned extension ID into the bundle before
distributing:

```bash
cp dist/production-extension-identity.json \
   "desktop/build/jpackage/Local Focus Coach.app/Contents/Resources/production-extension-identity.json"
```

`LFC_EXTENSION_ID` on the production build populates that file.

### Tests

```bash
npm test              # vitest
npm run typecheck     # tsc --noEmit
cd desktop && JAVA_HOME=… ./gradlew test
```

Before releasing, work through
[`docs/manual-test-checklist.md`](docs/manual-test-checklist.md) — several
guarantees (tab closing, block expiry, no network traffic, the
first-launch bootstrap) can only be confirmed in a real browser and a
freshly-downloaded `.app`.

## License

MIT. See [LICENSE](LICENSE).
