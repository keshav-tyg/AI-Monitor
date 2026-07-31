# On-Device AI Behavior Classification — Design

**Status:** Approved, not yet planned.
**Supersedes nothing.** Extends the shipped extension described in `docs/superpowers/plans/2026-07-30-local-doomscrolling-coach.md`.

## Problem

The shipped detector is arithmetic, not AI:

```
content advance = +2,  scroll = +1,  purposeful action = ×0.25
nothing scores before 120s,  90s without activity resets the session
```

It cannot distinguish a deliberate four-minute watch of one Reel from four
minutes of passive swiping, because both reduce to the same counters. The
stated goal — software that reads how someone is actually behaving — is not met
by counting events.

## Goal

Use an on-device language model to judge whether a session is genuinely passive
before an intervention fires, without weakening the product's core promise that
nothing leaves the device.

## Constraints

- **Local only.** No network client may appear in `src/`.
  `tests/privacy-boundary.test.ts` enforces this and must keep passing
  untouched. Chrome's built-in `LanguageModel` is a browser API, not a network
  call, so it satisfies this.
- **How, never what.** The model may see interaction statistics. It may never
  see titles, captions, on-screen text, URLs beyond the site key, or any
  identifier.
- **Fail open.** Every failure path — unavailable model, download in progress,
  session error, timeout, malformed output — leaves the existing heuristic
  decision untouched.
- **The model may only subtract.** It can veto an intervention the heuristic
  wanted. It can never cause one.
- **Opt-in.** Off by default, consistent with every rule shipping disabled.

## Scope

Instagram Reels and YouTube Shorts only. Both are `<video>` feeds and share one
engagement collector. The X timeline needs different signals entirely — dwell
per post, scroll-back, reading pauses — and keeps the current heuristic
untouched until this approach proves out.

## Verified environment facts

Confirmed against Chrome documentation and the target machine on 2026-07-31:

- Requires **Chrome 138+**, and the flag
  `chrome://flags/#prompt-api-for-gemini-nano` set to Enabled.
- Hardware: >4GB VRAM, or 16GB RAM with 4+ CPU cores; ~22GB free disk; macOS
  13+. One-time ~2GB model download performed by Chrome, not by this code.
- **The API is unavailable in workers.** It cannot run in the MV3 service
  worker; an offscreen document is required.
- `responseConstraint` accepts a JSON Schema, so output can be schema-bound
  rather than parsed from free text.
- `create()` must declare an output language, or Chrome warns that output
  quality and safety attestation are degraded. Pass
  `expectedOutputs: [{ type: 'text', languages: ['en'] }]`.
- On the target machine `await LanguageModel.availability()` returns
  `'available'`.

## Architecture

```
content script (Reels / Shorts adapters)
  └─ engagement collector: dwell, played fraction, replays, unmute,
     manual pause, advance method
        │  (no page content, no URLs, no text)
        ▼
service worker
  ├─ heuristic scoring (unchanged) ── wants to intervene? ──┐
  │                                                          ▼
  │                                          offscreen document
  │                                            warm LanguageModel session
  │                                            responseConstraint JSON schema
  │                                            → { verdict, confidence, reason }
  │                                                          │
  └──── veto → drop.  intervene → enforce. ◄────────────────┘
             timeout / unavailable / malformed → heuristic decision stands
```

The model runs only when the heuristic already wants to act, so inference is
rare and its battery cost is negligible. A 1.5s timeout means a slow model
never delays enforcement.

## Data contract

Per-item engagement, collected in the content script:

| Field | Meaning |
| --- | --- |
| `dwellMs` | time the item was current |
| `playedFraction` | furthest playback point ÷ duration |
| `replayCount` | playhead jumping backwards |
| `unmuted` | audio was turned on |
| `manuallyPaused` | playback paused by hand |
| `advancedBy` | `scroll` \| `click` \| `auto` |

The worker aggregates a session into the **complete** prompt payload:

```json
{
  "site": "youtube-shorts",
  "sessionMinutes": 6.2,
  "itemCount": 24,
  "medianDwellSeconds": 7.4,
  "medianCompletion": 0.31,
  "fullyWatchedCount": 2,
  "unmutedCount": 1,
  "replayCount": 0,
  "purposefulActionCount": 0,
  "scrollBurstCount": 5
}
```

Nothing else is sent. A leaked prompt reveals scroll statistics and nothing
about what was watched.

Response, schema-constrained:

```json
{ "verdict": "intervene", "confidence": 0.0, "reason": "short string" }
```

`verdict` is one of `intervene` or `veto`. `reason` is capped at 120 characters
and stored with the intervention record so the review list shows when the model
overruled the heuristic.

A veto is honoured only when `confidence` is at least `0.5`. Below that the
model is treated as undecided and the heuristic decision stands — an uncertain
model must not be the reason an intervention silently disappears.

The payload above is the passive case: 24 items, 7s median dwell, 31% median
completion, nothing unmuted. Inverted — 4 items, 45s dwell, 95% completion,
unmuted — the model should veto, where the current counter still fires.

## Components

| Path | Responsibility |
| --- | --- |
| `src/content/engagement.ts` | Per-item engagement collection for video feeds |
| `src/engine/session-summary.ts` | Pure aggregation of records into the payload |
| `src/offscreen/index.html` | Offscreen host document |
| `src/offscreen/classifier.ts` | Owns the warm session, answers classify requests |
| `src/background/classifier-client.ts` | Worker side: ensure offscreen, timeout, fall back |

`src/background/service-worker.ts` gains one call between the heuristic
decision and `enforce`. `manifest.config.ts` gains the `offscreen` permission.

## Lifecycle

The worker creates the offscreen document lazily on first classification, and
only when `availability()` is not `'unavailable'`. The document creates one
session and keeps it warm across worker teardowns — the MV3 worker dies roughly
every 30 seconds, and re-creating a session per check would exceed the latency
budget. The document closes after several minutes idle.

## Failure modes

| Condition | Behaviour |
| --- | --- |
| `LanguageModel` undefined | Never create offscreen; heuristic only |
| `availability()` is `unavailable` | Same |
| Model downloading | Treated as unavailable until ready |
| `create()` throws | Classifier disabled for the session |
| Prompt exceeds 1.5s | Heuristic decision stands |
| Output fails the schema | Heuristic decision stands |

## Settings

One Options toggle, off by default: *"Use on-device AI to double-check
interventions."* It displays live availability, so an absent model is visible
rather than silent.

## Testing

- **Aggregation** — engagement records to payload, pure, no mocks.
- **Classifier client** — stubbed `LanguageModel`: verdict parsing, timeout
  fallback, malformed output fallback, and that `unavailable` never creates an
  offscreen document.
- **Worker integration** — a `veto` drops enforcement; a model error still
  enforces.
- **Privacy** — `tests/privacy-boundary.test.ts` passes unchanged, plus a new
  assertion that the prompt payload contains no page-derived strings, so the
  "how, never what" boundary is enforced by a test rather than by care.
- **Manual** — additions to `docs/manual-test-checklist.md`: model absent
  behaves exactly as today; a veto is visible in the review list; enforcement
  is never delayed perceptibly.

## Open risks

1. **Offscreen `reasons` enum has no AI value.** The closest existing reason
   will be chosen and verified during implementation rather than guessed here.
2. **A small on-device model may be unreliable at this judgment.** Veto-only
   means a bad model degrades to current behaviour rather than causing harm,
   but "the model is right more often than the counter" is a hypothesis. The
   existing accurate/inaccurate feedback buttons are how it gets tested.
3. **Setup burden.** The flag requirement makes this impractical to distribute
   to anyone else without instructions.

## Explicitly out of scope

X timeline classification; training or fine-tuning any model; using the stored
feedback to adjust behaviour automatically; cloud inference of any kind;
replacing the heuristic outright.
