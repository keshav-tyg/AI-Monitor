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

Focus Rules are configured in the Local Focus Coach desktop dashboard. The
extension's **Options** page is read-only; its **Open Local Focus Coach**
button opens that dashboard on this Mac.

For the optional on-device behaviour check, use Chrome 138+ and enable
`chrome://flags/#prompt-api-for-gemini-nano`. Chrome may download its local
model once. If it is unavailable, the declared-intent budget still works; the
model simply cannot veto a budget wall that looks deliberate.

## Desktop-managed Focus Rules

The Local Focus Coach macOS companion is the only editor for the master
protection switch and all per-site rules. Its dashboard saves a complete,
validated settings revision locally; Chrome receives that read-only revision
through the local Native Messaging relay. A dashboard save reaches a connected
Chrome extension on its next five-second sync heartbeat.

On the first authenticated connection after upgrading, an existing browser
settings record is imported only when the desktop database has no Focus Rules
record. That one-time import becomes revision 1. If the desktop already has a
record, it wins; a completed import cannot overwrite later dashboard edits.

Chrome retains the newest valid desktop revision only as a last-known-good
enforcement cache. If the companion is temporarily unavailable, cached rules
continue to enforce and Chrome asks for the newest revision when it reconnects.
The cache is never an editable browser settings surface.

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

The extension treats an explicit start as the primary control, not a hidden
score. Entering a feed asks:

- **Doomscrolling — give me N minutes** — grants the per-site doomscroll budget
  you set in the desktop dashboard (five minutes by default). The popup then shows a live
  timer counting that budget up toward it.

That is the only button. There is no "just looking" escape hatch and no free
text: pressing Escape leaves the prompt where it is. Entering a feed on purpose
means starting a session on purpose.

The budget is spent against **foreground time on that feed**, not wall-clock
time. Switching tabs pauses it. Closing the tab, refreshing, or reloading the
extension does not reset it: the declaration and the milliseconds it has already
consumed live in `chrome.storage.local`, so only its 30-minute cooldown expires
it. That counter belongs to the session rather than to the day, so a session
declared at 23:59 keeps its remaining budget through midnight.

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
veto a doomscroll wall when behaviour looks deliberate. Any model failure
leaves you less restricted: your declaration governs unchanged.

### What the on-device model does

Gemini Nano is an optional **second opinion**, not the detector or the source
of enforcement. The deterministic session budget and score ladder remain in
charge. Once a declared session has at least five viewed items, the extension
may ask the local model at most once every 20 seconds whether the aggregate
behaviour matches the person's declared intent.

The model receives only the supported feed, declared intent, entry type,
session duration, item count, median dwell time and completion, fully watched
items, replay and unmuted counts, purposeful-action count, and scroll-burst
count. It returns a schema-checked verdict, confidence, and short reason.

- For a declared doomscroll session whose budget has elapsed, a confident
  indication that the behaviour looks deliberate can prevent the wall.
- For a declared purposeful session, only a high-confidence indication that the
  behaviour looks like passive scrolling can raise a wall.
- It never decides the warning score, Strict Mode, browser-close behavior, or
  any direct-link item. If Gemini Nano is missing, unavailable, slow, or
  uncertain, the normal local rules apply unchanged.

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

What it **does** store locally in Chrome:

- `settings` — a retired browser-owned record, retained only for the one-time
  desktop import
- `desktop-settings-snapshot` — the newest valid desktop revision, used only
  as the last-known-good enforcement cache
- `usage` — minutes per feed for the current local day
- `interventions` — up to 200 past interventions with their plain-language
  reasons, plus your accurate/inaccurate feedback
- `blocks` — which feed is blocked and until when
- `return-pauses` — a pause that should be shown again if the feed is reopened
- `declarations` — the current per-site intent and its local budget state
- `activity` — up to 200 timeline entries: session started, timer ended, wall
  shown, leave pressed

Uninstalling the extension removes these Chrome-local entries. The authoritative
Focus Rules record remains separately in the local desktop companion database.

## Activity timeline

The extension records this plain local diary of a declared session, newest
first:

| Entry | When it is written |
| --- | --- |
| Session started | You answered the prompt (either answer) |
| Timer ended | A doomscroll budget was spent |
| Wall shown | The wall went up, once per session, with the reason |
| Leave pressed | You left from the wall |

It holds no page content — only the site, the moment, and a sentence built from
your own settings.

## Architecture

```
content script  ──events──▶  service worker  ──commands──▶  content script
  provenance                   declarations                    prompt / wall
  engagement                   budget + wall                   pause / notice
  overlays                     activity log
                                    │
                                    ├─ chrome.storage.local (7 keys)
                                    └─ offscreen document ─▶ on-device model
```

| Layer | Files | Responsibility |
| --- | --- | --- |
| Content | `src/content/` | Detect the view, classify how you arrived, collect per-item engagement, render every overlay. Owns no decisions. |
| Engine | `src/engine/` | Pure functions: `declaration.ts` (budget + session state machine), `session-summary.ts` (aggregate payload), `score.ts`, `rules.ts`. No DOM, no storage, no clock of their own. |
| Worker | `src/background/` | The only place that decides or enforces. Owns declarations, per-tab arrival state, the wall, and the activity log. |
| Offscreen | `src/offscreen/` | Hosts the Prompt API, which cannot run in an MV3 service worker, and keeps one session warm. |
| Shared | `src/shared/` | Types, constants, and the local-only storage layer. |

Per-tab state (arrival kind, advances, engagement) is deliberately transient —
it dies with the worker. Anything that must survive a teardown is persisted, so
an idle worker, an extension reload, or a browser restart cannot hand back a
budget you already spent.

## Limitations

Known and accepted, not bugs:

- **The wall is dismissible.** Leaving and coming back gets past it for one
  item. A hard block would break the friend's-link case, which matters more.
- **Provenance is best-effort.** Referrers are often empty, so some feed entries
  look like direct links and are not prompted. Under-prompting is the deliberate
  choice.
- **The model is optional and not always right.** It needs Chrome 138+ and a
  flag, and a small on-device model can misjudge. It can only *prevent*
  enforcement under a doomscroll declaration; it can end a *purposeful* session
  only at 0.8 confidence, and never touches a direct-link item.
- **Detection is selector-based.** A site redesign can silently stop it.
- **Three feeds only**, macOS Chrome only, one profile, no sync.

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

macOS Chrome only. This is a prototype: no mobile app, no account, no cloud
sync, no usage reporting of any kind, and no support for feeds beyond the three
listed above. The local desktop companion owns Focus Rules; Strict Mode is
optional.

## Local macOS companion and optional Strict Mode

The local macOS companion owns Focus Rules and optionally provides Strict Mode
for an active Chrome session. It is supported only with Google Chrome on macOS.
The extension, its Native Messaging relay, and the companion service
communicate only on this computer; session data and the installation secret are
stored locally. There is no account, cloud service, remote control, or
cross-device synchronization.

Strict Mode is deliberately **not tamper-proof**. A person who controls the
Mac can disable software, alter or remove registrations, or otherwise bypass
it. The companion does not claim to prevent that. Its purpose is to make an
opt-in commitment more deliberate, while preserving a local, user-controlled
setup.

### Install in this order

Do these steps in order. A release build needs the base64 DER public key that
belongs to the production extension identity. Keep the corresponding private
key in protected release infrastructure; do not put it in this repository or
pass it to these build scripts. The public key is the remaining release input
and is safe to embed in the extension manifest.

1. Build the production extension. The build validates the public key, embeds
   it as the manifest `key`, derives the stable 32-letter Chrome ID, and writes
   `dist/production-extension-identity.json`:

   ```sh
   LFC_EXTENSION_PUBLIC_KEY='<base64 DER SubjectPublicKeyInfo>' \
     npm run build:production
   ```

   A plain `npm run build` is deliberately a development build. It has no
   manifest key, its unpacked ID is not promised to be stable across machines,
   and it connects only to `com.localfocuscoach.strict_mode_dev`.
2. Load `dist/` in `chrome://extensions`. For a release, confirm Chrome shows
   the ID recorded in `dist/production-extension-identity.json`.
3. Package the macOS app image from `desktop/` with Java 21:

   ```sh
   JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
     ./gradlew jpackage
   ```

4. Move or copy `desktop/build/jpackage/Local Focus Coach.app` to its permanent
   location. Do not move it after the next step.
5. Install the user-level LaunchAgent and Chrome Native Messaging registration:

   ```sh
   cd desktop
   ./installer/install-local-focus-coach.sh \
     --app-image "/absolute/path/Local Focus Coach.app" \
     --production-identity-file ../dist/production-extension-identity.json
   ```

   This needs no administrator access. It installs only
   `~/Library/LaunchAgents/com.localfocuscoach.strict-service.plist` and
   `~/Library/Application Support/Google/Chrome/NativeMessagingHosts/com.localfocuscoach.strict_mode.json`.
   The LaunchAgent starts the local service at login and restarts it after an
   unexpected exit.
   For local development, use
   `--development-extension-id <copied-unpacked-id>` instead. That writes only
   the `_dev` host configuration; a raw development ID cannot enter the
   production allowlist.
6. Launch the dashboard from the app image, confirm that it can see the local
   service, then enable Strict Mode from the dashboard.

When an active session loses its extension connection while Google Chrome is
running, the service presents a local macOS warning even if the dashboard is
closed; the dashboard also shows the 30-second countdown when open. Re-enable
the extension before that deadline to cancel the warning. If it expires, the
companion asks Chrome to quit gracefully; it does not force-kill Chrome. A
closed Chrome instance does not start a warning, and reopening Chrome without
the extension starts a fresh warning cycle while the session remains active.

For the full real-machine acceptance procedure, see
[`desktop/docs/manual-strict-mode-checklist.md`](desktop/docs/manual-strict-mode-checklist.md).
Use the installer’s ownership-safe uninstall command when removing the
registrations. It deletes only unchanged registrations it created, and leaves
the app image, local session data, secret, logs, and unrelated registrations in
place.

## Development

```bash
npm test          # vitest
npm run typecheck # tsc --noEmit
npm run build     # production bundle into dist/
```

Before releasing, work through `docs/manual-test-checklist.md` — several
guarantees here (tab closing, block expiry, no network traffic) can only be
confirmed in a real browser.
