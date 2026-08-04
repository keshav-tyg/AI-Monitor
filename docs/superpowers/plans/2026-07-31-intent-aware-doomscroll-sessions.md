# Intent-Aware Doomscroll Sessions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** `docs/superpowers/specs/2026-07-31-ai-behavior-classification-design.md`.
**Extends:** `docs/superpowers/plans/2026-07-30-local-doomscrolling-coach.md` — the shipped
event/score/rule pipeline stays exactly as it is; this plan layers a declared-intent
session on top of it.

**Goal:** Make the session's declared intent the primary control. A feed entry asks what
you are doing, a doomscroll declaration grants a bounded budget, and an on-device model
is only the honesty check behind the declaration.

**Architecture:** The content script classifies *how you arrived* deterministically
(referrer plus URL shape), collects per-item engagement, and renders two new surfaces —
the two-button intent prompt and the feed wall. The service worker owns declarations
(persisted), per-tab arrival state (transient), and every enforcement decision. The
Prompt API cannot run in an MV3 worker, so the model lives in an offscreen document that
keeps one session warm; the worker talks to it through a client that times out and falls
back.

## Global Constraints

- Every constraint from the previous plan still holds: local-only storage, no network,
  no page-derived strings, fail-open on any unknown state.
- **The wall is the only new enforcement.** It never closes a tab and never installs a
  DNR rule, so it needs no entry in a rule's `interventions` ladder.
- **The model may only cause enforcement under a declared `purposeful` intent, at
  `confidence >= 0.8`.** Under `doomscroll` it may only *prevent* enforcement, at
  `confidence >= 0.5`.
- **Deep-link items are never walled and never classified.**
- Any classifier failure — unavailable, downloading, thrown, timed out, schema-invalid —
  leaves the declaration governing unchanged.
- The payload sent to the model carries no titles, captions, on-screen text, URLs, or
  identifiers; only the site key, the declaration, and aggregate statistics.

## File Structure

| Path | Responsibility |
| --- | --- |
| `src/shared/types.ts` | Adds `EntryKind`, `DeclaredIntent`, `EngagementRecord`, `ClassifierPayload`, `ClassifierResult`, `DeclarationEntry`, and the new messages. |
| `src/shared/constants.ts` | Budget default, cooldown, confidence gates, classify cadence, prompt/wall copy. |
| `src/shared/storage.ts` | Sixth key: `declarations`. |
| `src/content/entry-provenance.ts` | Pure arrival classification from referrer and URL shape. |
| `src/content/engagement.ts` | Per-item engagement collection. |
| `src/content/intent-prompt.ts` | Two-button dialog and the feed wall. |
| `src/content/content-script.ts` | Wires provenance, engagement, and the two new commands. |
| `src/engine/declaration.ts` | Pure budget and session-type state machine. |
| `src/engine/session-summary.ts` | Pure aggregation of engagement records into the payload. |
| `src/offscreen/index.html` | Offscreen host document. |
| `src/offscreen/classifier.ts` | Owns the warm `LanguageModel` session; answers classify requests. |
| `src/background/classifier-client.ts` | Ensures the offscreen document, times out, falls back. |
| `src/background/service-worker.ts` | Arrival state, declarations, wall, classifier calls. |
| `manifest.config.ts` | Gains the `offscreen` permission. |
| `vite.config.ts` | Builds the offscreen document. |

---

## Task 1: Shared vocabulary and persistence

**Files:** `src/shared/types.ts`, `src/shared/constants.ts`, `src/shared/storage.ts`,
`tests/storage.test.ts`

**Interfaces produced:** `EntryKind`, `DeclaredIntent`, `AdvanceSource`,
`EngagementRecord`, `ClassifierPayload`, `ClassifierResult`, `DeclarationEntry`;
`getDeclaration()`, `saveDeclaration()`, `clearDeclaration()`.

- [ ] **Step 1:** Failing storage tests — a declaration round-trips; an expired
      declaration reads as `undefined` and is pruned; declarations are per-site.
- [ ] **Step 2:** Add the types, the constants (`DECLARATION`, `CLASSIFIER`), and the
      three storage helpers. `SiteRule` gains `doomscrollBudgetMinutes`, default 5,
      merged through the existing defaults path so stored settings stay readable.
- [ ] **Step 3:** `npm test` green.

## Task 2: Entry provenance

**Files:** `src/content/entry-provenance.ts`, `tests/entry-provenance.test.ts`

**Interface:** `classifyEntry({ site, href, referrer }): EntryKind`.

- [ ] **Step 1:** Failing table test covering: external referrer → `deep-link`; empty
      referrer with an item id → `deep-link`; empty referrer without one → `feed-entry`;
      same-site search/explore referrer → `in-app-search`; the feed itself as referrer →
      `feed-entry`; unparseable input → `deep-link`.
- [ ] **Step 2:** Implement. Every uncertain branch returns `deep-link`.
- [ ] **Step 3:** `npm test` green.

## Task 3: Declaration state machine

**Files:** `src/engine/declaration.ts`, `tests/declaration.test.ts`

**Interface:** `shouldPrompt()`, `budgetSpentMs()`, `isBudgetSpent()`,
`confidenceGate()`, `nextDeclarationAction()`.

- [ ] **Step 1:** Failing tests — no declaration plus `feed-entry` prompts; an active
      declaration inside its cooldown does not re-prompt; a `deep-link` arrival does not
      prompt until it has advanced once; a doomscroll budget measured against *usage*
      (not wall clock) walls only once spent; a `purposeful` declaration never walls on
      time alone.
- [ ] **Step 2:** Implement as pure functions over `DeclarationEntry` plus the caller's
      `usageMs`, `entryKind`, `advancesSinceEntry`, and `now`.
- [ ] **Step 3:** `npm test` green.

## Task 4: Session summary aggregation

**Files:** `src/engine/session-summary.ts`, `tests/session-summary.test.ts`

**Interface:** `summarizeSession(input): ClassifierPayload`.

- [ ] **Step 1:** Failing tests — medians over even and odd counts, `fullyWatchedCount`
      threshold, empty record list producing zeros rather than `NaN`, and an assertion
      that every payload value is a number or one of the three enum strings.
- [ ] **Step 2:** Implement, pure. Round: minutes and dwell to one decimal, completion
      to two.
- [ ] **Step 3:** `npm test` green.

## Task 5: Engagement collection

**Files:** `src/content/engagement.ts`, `tests/engagement.test.ts`

**Interface:** `createEngagementTracker({ now, getVideo })` with `noteGesture()`,
`sample()`, `finishItem()`.

- [ ] **Step 1:** Failing tests — dwell accumulates between items; `playedFraction` keeps
      the maximum seen; a backwards `currentTime` counts a replay; a gesture within the
      attribution window sets `advancedBy`, and none leaves it `auto`; a missing video
      element degrades to dwell-only without throwing.
- [ ] **Step 2:** Implement against injected `now`/`getVideo` so no test needs a real
      media element.
- [ ] **Step 3:** `npm test` green.

## Task 6: Intent prompt and feed wall

**Files:** `src/content/intent-prompt.ts`, `tests/intent-prompt.test.ts`

**Interface:** `showIntentPrompt({ site })`, `showFeedWall({ site, reason })`,
`dismissIntentSurfaces()`.

- [ ] **Step 1:** Failing jsdom tests — the prompt renders exactly two buttons; each
      sends the matching `declare-intent`; dismissal without an answer resolves to
      `purposeful`; the wall's only action is Leave; both surfaces are removable.
- [ ] **Step 2:** Implement in the style of `overlay.ts` (`all: initial` reset, ids,
      optional-chained runtime).
- [ ] **Step 3:** `npm test` green.

## Task 7: Offscreen classifier and its client

**Files:** `src/offscreen/index.html`, `src/offscreen/classifier.ts`,
`src/background/classifier-client.ts`, `tests/classifier-client.test.ts`

**Interface:** `classify(payload): Promise<ClassifierResult | undefined>`;
`resetClassifierClient()`.

- [ ] **Step 1:** Failing tests with a stubbed `chrome.offscreen` and a stubbed
      responder — a well-formed verdict parses; `unavailable` never creates a document;
      a timeout past 1.5s resolves `undefined`; malformed output resolves `undefined`; a
      second call reuses the existing document.
- [ ] **Step 2:** Implement. The client must never reference `window` — the build test
      asserts the worker bundle does not. The offscreen document owns the warm session,
      declares `expectedOutputs` with an output language, and passes the JSON Schema as
      `responseConstraint`.
- [ ] **Step 3:** `npm test` green.

## Task 8: Worker and content-script integration

**Files:** `src/background/service-worker.ts`, `src/content/content-script.ts`,
`src/shared/types.ts`, `tests/intent-session.test.ts`

- [ ] **Step 1:** Failing integration tests — a `feed-entry` arrival returns a
      `prompt-intent` command; a `deep-link` arrival returns nothing; the first advance
      past a deep-link item converts the session and prompts; a spent doomscroll budget
      walls on the next advance; a `contradicts` at 0.85 under `purposeful` walls; the
      same verdict at 0.6 does not; a classifier error never walls; a deep-link item is
      never walled and never classified.
- [ ] **Step 2:** Implement. Arrival state is a per-tab map that dies with the worker;
      declarations persist. Wall records an intervention of kind `pause` whose reason
      names the cause, so the existing review list shows model overrides without a new
      enum member.
- [ ] **Step 3:** `npm test` green.

## Task 9: Manifest, build, and documentation

**Files:** `manifest.config.ts`, `vite.config.ts`, `tests/manifest.test.ts`,
`docs/manual-test-checklist.md`, `README.md`

- [ ] **Step 1:** Update the manifest permission assertion to include `offscreen`.
- [ ] **Step 2:** Add the permission, add the offscreen document to the build inputs,
      and document the Chrome flag prerequisite plus the new manual checks.
- [ ] **Step 3:** `npm test`, `npm run typecheck`, and `npm run build` green.

---

## Verification

```bash
npm run typecheck
npm test
npm run build
```

## Deviations from the spec, and why

- **No new `InterventionKind`.** The wall is recorded as `pause` with a distinct reason.
  A new enum member would have to be rendered by the options ladder, where it is not a
  configurable step.
- **Budget is measured against foreground usage,** not wall-clock time, because the spec
  says it is "counted against the same usage accounting already in place" — which only
  advances while the feed is actually in front of you.
- **`DeclarationEntry` carries `spentMs` and `walledAt`** beyond the five fields
  the spec lists. The first attempt stored a `usageAtStartMs` baseline and
  subtracted it from the daily usage counter; that counter resets at local
  midnight, so a session declared at 23:59 got its whole budget back plus every
  minute already spent that day. The session now owns its own consumed-time
  counter. `walledAt` is there so a model-raised wall survives worker teardown.
- **Declaration writes are serialized per site.** Message handlers interleave at
  every `await`, so two tabs advancing the same feed could both claim the same
  wall crossing and write duplicate records. A per-site promise chain makes each
  read-modify-write atomic.
- **Offscreen reason is `WORKERS`** (spec open risk 4): the enum has no AI value, and
  this is the member that actually describes the situation — the work cannot run in the
  service worker.
