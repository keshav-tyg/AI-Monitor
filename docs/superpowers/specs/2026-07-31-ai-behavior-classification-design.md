# Intent-Aware Doomscroll Sessions — Design

**Status:** Approved, not yet planned.
**Replaces** the veto-only classifier design previously in this file (commit
`6c273c0`). Extends the shipped extension from
`docs/superpowers/plans/2026-07-30-local-doomscrolling-coach.md`.

## Problem

The shipped detector counts events:

```
content advance = +2,  scroll = +1,  purposeful action = ×0.25
nothing scores before 120s,  90s without activity resets the session
```

Two failures follow. It cannot tell a deliberate watch from passive swiping,
because both reduce to the same counters. And it cannot tell *why you are here*
— opening a reel a friend sent you is treated identically to opening the feed
out of habit.

The earlier design addressed only the first, by letting a model veto
interventions. It never asked what the person intended.

## Goal

Make the session's **declared intent** the primary control, with the model as
the honesty check behind it. Watching something specific should always work.
Doomscrolling should be bounded, by an amount you agreed to in advance.

## The model of a session

```
enter Reels
   │
   ├─ arrived from a link or search? ──→ one item free, no prompt
   │        └─ advance past it ──→ becomes a feed session
   │
   └─ feed entry ──→ "Hey, what are we doing here?"
            ├─ "Doomscrolling — give me 5 minutes" → budget starts
            └─ "Looking for something"             → no timer
                        │
                        ▼
              model watches either way
                        │
        behavior contradicts the declaration, or budget spent
                        │
                        ▼
                    feed wall
```

## Entry provenance

Deterministic, not AI. Two signals, read once on arrival:

- `document.referrer`
- whether the URL carried a specific item ID at arrival, before any advance

| Arrival | Classification | Treatment |
| --- | --- | --- |
| External referrer, or empty referrer with an item ID | `deep-link` | One item free, no prompt |
| Referrer within the site's search or explore routes | `in-app-search` | One item free, no prompt |
| No referrer and no item ID, or referrer is the feed itself | `feed-entry` | Prompt fires |

**Provenance fails toward "legitimate".** Referrer is frequently empty — links
opened from another application, or from a messaging app, often arrive with
nothing. An uncertain arrival is treated as a deep link and is not prompted.
This deliberately under-prompts; the model backstop is what catches the feed
entries that slip through.

A deep-link or in-app-search visit becomes a `feed-entry` session on the **first
advance past the arrival item**. That is the rule that keeps "a friend's link
still works" from becoming an unlimited bypass.

## The prompt

Two buttons, no free text, no duration picker:

- **"Doomscrolling — give me 5 minutes"** → starts a budget
- **"Looking for something"** → no timer, model keeps watching

Rationale for the constraint: free text costs a second of inference before the
person can proceed and can be misread; a duration picker invites negotiating
upward at the exact moment the limit is least wanted.

The prompt appears only on `feed-entry` arrivals, and at most once per cooldown
per site, so it stays something read rather than reflex-dismissed.

## Budget and the feed wall

A doomscroll declaration grants the configured budget, default 5 minutes,
counted against the same usage accounting already in place.

When the budget is spent, a **feed wall** covers the feed on the next advance:
a full-page overlay whose only action is Leave. It is behavioral rather than a
`declarativeNetRequest` block, because a URL block cannot distinguish a feed
swipe from a friend's link — both are `/reels/<id>`. That is an accepted
trade-off: the wall stops a reflex, not a determined person, and it is what
keeps deep links working.

A walled site still admits deep links, each getting its single item.

## The model's role

The model receives the same aggregate statistics as before, plus the
declaration, and answers one question: does the behavior match what was
declared?

- Declared **doomscrolling** → the budget governs. The model may *veto* an
  intervention when behavior looks genuinely deliberate.
- Declared **looking for something** → no timer, but the model may **end the
  session** and raise the wall when behavior clearly contradicts the
  declaration. This requires `confidence >= 0.8`, a deliberately higher bar
  than the 0.5 used for vetoes, because here the model causes enforcement
  rather than preventing it.
- **Deep-link single items are never touched by the model.**

## Data contract

Per-item engagement collected in the content script: `dwellMs`,
`playedFraction`, `replayCount`, `unmuted`, `manuallyPaused`, `advancedBy`
(`scroll` | `click` | `auto`).

The complete prompt payload:

```json
{
  "site": "instagram-reels",
  "declaredIntent": "purposeful",
  "entryKind": "feed-entry",
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

No titles, no captions, no on-screen text, no URLs beyond the site key, no
identifiers. A leaked prompt reveals scroll statistics and the button that was
pressed — nothing about what was watched.

Schema-constrained response:

```json
{ "verdict": "matches", "confidence": 0.0, "reason": "short string" }
```

`verdict` is one of `matches` or `contradicts`. `reason` is capped at 120
characters and stored with the intervention record, so the review list shows
when the model overrode a declaration.

## Components

| Path | Responsibility |
| --- | --- |
| `src/content/entry-provenance.ts` | Classify arrival from referrer and URL shape |
| `src/content/engagement.ts` | Per-item engagement collection |
| `src/content/intent-prompt.ts` | The two-button dialog and the feed wall |
| `src/engine/session-summary.ts` | Pure aggregation into the payload |
| `src/engine/declaration.ts` | Pure budget and session-type state machine |
| `src/offscreen/index.html` | Offscreen host document |
| `src/offscreen/classifier.ts` | Owns the warm session, answers classify requests |
| `src/background/classifier-client.ts` | Ensure offscreen, timeout, fall back |

`manifest.config.ts` gains the `offscreen` permission. Storage gains a sixth
key, `declarations`, holding `{ site, intent, entryKind, startedAt, expiresAt }`
so a declaration survives service-worker teardown.

## Environment facts

Verified against Chrome documentation and the target machine on 2026-07-31:

- Chrome 138+, with `chrome://flags/#prompt-api-for-gemini-nano` Enabled.
- Hardware: >4GB VRAM, or 16GB RAM with 4+ cores; ~22GB free disk; macOS 13+.
  One-time ~2GB download performed by Chrome, not by this code.
- **The Prompt API is unavailable in workers**, so it cannot run in the MV3
  service worker. An offscreen document is required, and it keeps one session
  warm across worker teardowns.
- `responseConstraint` accepts a JSON Schema, so output is schema-bound rather
  than parsed from free text.
- `create()` must declare an output language or Chrome degrades output quality
  and safety attestation. Pass
  `expectedOutputs: [{ type: 'text', languages: ['en'] }]`.
- On the target machine `await LanguageModel.availability()` returns
  `'available'`.

## Failure modes

| Condition | Behaviour |
| --- | --- |
| `LanguageModel` undefined or `unavailable` | Declarations and budgets still work; no model override |
| Model downloading | Treated as unavailable until ready |
| `create()` throws | Classifier disabled for the session |
| Prompt exceeds 1.5s | Declaration governs unchanged |
| Output fails the schema | Declaration governs unchanged |
| Provenance ambiguous | Treated as a deep link — no prompt, no wall |
| Prompt dismissed without an answer | Treated as "looking for something" |

Every path leaves the person less restricted, never more.

## Testing

- **Provenance** — referrer and URL-shape table, pure, covering the empty
  referrer case explicitly.
- **Declaration state machine** — budget expiry, deep link converting to a feed
  session on first advance, cooldown suppressing repeat prompts.
- **Aggregation** — engagement records to payload, pure.
- **Classifier client** — stubbed `LanguageModel`: verdict parsing, the 0.5 and
  0.8 confidence gates, timeout fallback, malformed output fallback, and that
  `unavailable` never creates an offscreen document.
- **Worker integration** — a `contradicts` at high confidence raises the wall; a
  model error never does; a deep-link item is never walled.
- **Privacy** — `tests/privacy-boundary.test.ts` passes unchanged, plus an
  assertion that the payload contains no page-derived strings.
- **Manual** — additions to `docs/manual-test-checklist.md`: prompt appears on
  feed entry and not on a pasted link; budget expiry raises the wall; a link
  still opens while walled; advancing past that item re-walls.

## Open risks

1. **Provenance is best-effort.** Empty referrers make some feed entries look
   like deep links. Under-prompting is the deliberate choice; the model
   backstop absorbs it.
2. **The wall is dismissible.** Leaving and returning gets past it. Accepted:
   a hard block would break the deep-link case.
3. **The model can now cause enforcement.** Mitigated by the 0.8 threshold, by
   never touching deep-link items, and by every failure path favouring the
   person.
4. **The offscreen `reasons` enum has no AI value.** The closest reason will be
   chosen and verified during implementation.
5. **A small on-device model may be unreliable at this judgment.** The
   accurate/inaccurate feedback buttons are how that gets measured.
6. **Setup burden.** The Chrome flag makes this impractical to distribute
   without instructions.

## Explicitly out of scope

X timeline; training or fine-tuning; using stored feedback to adjust behaviour
automatically; cloud inference; replacing the heuristic outright; any hard
network-level block of the feed.
