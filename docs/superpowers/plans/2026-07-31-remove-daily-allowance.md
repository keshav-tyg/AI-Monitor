# Remove Daily Allowance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the daily allowance as a product concept while preserving local foreground usage for doomscroll session budgets.

**Architecture:** `SiteRule`, the score engine, and status response lose daily-allowance fields. The service worker continues storing foreground usage only to compare it with `DeclarationEntry.usageAtStartMs`. The popup becomes a state-only dashboard; Options exposes the session budget and score ladder controls.

**Tech Stack:** TypeScript, Manifest V3 service worker, Vite, Vitest.

## Global Constraints

- Session-budget usage remains local in `chrome.storage.local`; no network or new data collection.
- Existing settings that contain `dailyAllowanceMinutes` must be readable and ignored.
- Intent declarations remain the primary control; a spent session budget walls only on the next advance.
- Score-based warnings, pauses, close-tab actions, and blocks continue to use their configured threshold and grace period before an active intent declaration.

---

### Task 1: Remove allowance-triggered rule decisions

**Files:**
- Modify: `src/engine/rules.ts`, `src/shared/types.ts`, `src/shared/constants.ts`, `src/background/service-worker.ts`
- Modify: `tests/rules.test.ts`, `tests/background.test.ts`, `tests/pause-actions.test.ts`, `tests/intent-session.test.ts`

**Interfaces:**
- `RuleLike` consumes `enabled`, `warningScore`, `gracePeriodSeconds`, `interventions`, and `blockUntil`.
- `nextIntervention({ rule, score, now, warnedAt, pauseShownAt, reason })` returns an `InterventionDecision` from score only.

- [ ] **Step 1: Write failing tests**

```ts
it('does not enforce a legacy daily allowance field when score is below threshold', () => {
  expect(nextIntervention({ rule: { ...rule, dailyAllowanceMinutes: 1 }, score: 0, now: 60_000 }))
    .toEqual({ kind: 'none' });
});
```

Update worker tests so settings omit `dailyAllowanceMinutes`; assert a persisted legacy field does not change a below-threshold decision.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `npm test -- tests/rules.test.ts tests/background.test.ts tests/pause-actions.test.ts tests/intent-session.test.ts`

Expected: the legacy field still triggers `Daily allowance reached`, or TypeScript reports obsolete required fields.

- [ ] **Step 3: Implement score-only rules**

```ts
export interface RuleInput {
  rule: RuleLike;
  score: number;
  now: number;
  warnedAt?: number;
  pauseShownAt?: number;
  reason?: string;
}

if (score < rule.warningScore) return { kind: 'none' };
```

Remove `dailyAllowanceMinutes`, `ALLOWANCE_REASON`, and `usageMs` from the score-rule contract. Remove rule-engine allowance evaluation and status allowance fields. Keep `getUsage` and `addUsage` only for the declaration budget.

- [ ] **Step 4: Run focused tests and verify pass**

Run: `npm test -- tests/rules.test.ts tests/background.test.ts tests/pause-actions.test.ts tests/intent-session.test.ts`

Expected: PASS with no daily-allowance reason or required field.

- [ ] **Step 5: Commit**

```bash
git add src/engine/rules.ts src/shared/types.ts src/shared/constants.ts src/background/service-worker.ts \
  tests/rules.test.ts tests/background.test.ts tests/pause-actions.test.ts tests/intent-session.test.ts
git commit -m "refactor: remove daily allowance enforcement"
```

### Task 2: Remove daily allowance UI and preserve legacy settings reads

**Files:**
- Modify: `src/options/main.ts`, `src/popup/main.ts`, `src/shared/storage.ts`
- Modify: `tests/options.test.ts`, `tests/popup.test.ts`, `tests/popup-refresh.test.ts`, `tests/review-regressions.test.ts`

**Interfaces:**
- `SiteStatus` consumes `site`, `enabled`, and `active` only.
- `getSettings()` returns a current `Settings` shape even when persisted objects contain extra legacy properties.

- [ ] **Step 1: Write failing UI and storage tests**

```ts
expect(document.querySelector('[data-field="dailyAllowanceMinutes"]')).toBeNull();
expect(document.body.textContent).not.toMatch(/used today|of \d+ min/);
```

Add a storage-stub test that writes a settings object with `dailyAllowanceMinutes` and expects `getSettings()` to return its current fields without that property.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `npm test -- tests/options.test.ts tests/popup.test.ts tests/popup-refresh.test.ts tests/review-regressions.test.ts tests/storage.test.ts`

Expected: existing controls and popup usage text make the new assertions fail.

- [ ] **Step 3: Implement the simplified UI**

```ts
const NUMBER_FIELDS = [
  { field: 'doomscrollBudgetMinutes', label: 'Doomscroll session budget (minutes)', min: 1, max: 60 },
  { field: 'warningScore', label: 'Warning score', min: 1, max: 50 },
  { field: 'gracePeriodSeconds', label: 'Grace period (seconds)', min: 0, max: 600 },
] as const;
```

Render `rule disabled` or `rule active` plus the live session indicator in the popup. When reading stored settings, construct each rule only from default-known fields, dropping unknown legacy fields before return and next save.

- [ ] **Step 4: Run focused tests and verify pass**

Run: `npm test -- tests/options.test.ts tests/popup.test.ts tests/popup-refresh.test.ts tests/review-regressions.test.ts tests/storage.test.ts`

Expected: PASS with no daily allowance copy or UI control.

- [ ] **Step 5: Commit**

```bash
git add src/options/main.ts src/popup/main.ts src/shared/storage.ts \
  tests/options.test.ts tests/popup.test.ts tests/popup-refresh.test.ts \
  tests/review-regressions.test.ts tests/storage.test.ts
git commit -m "feat: simplify focus controls"
```

### Task 3: Update documentation and verify the extension

**Files:**
- Modify: `README.md`, `docs/manual-test-checklist.md`
- Test: all existing suites and production build

- [ ] **Step 1: Remove daily-allowance explanations and manual checks**

Replace them with the session-budget explanation: it is counted while the feed
is foregrounded and walls on the next advance. Keep score-ladder language
separate from the time budget.

- [ ] **Step 2: Verify no current-source references remain**

Run: `rg -n "dailyAllowance|Daily allowance|used today|allowedMinutes" src README.md docs/manual-test-checklist.md`

Expected: no matches.

- [ ] **Step 3: Run all verification**

Run: `npm run typecheck && npm test && npm run build && git diff --check`

Expected: all commands exit 0.

- [ ] **Step 4: Commit**

```bash
git add README.md docs/manual-test-checklist.md
git commit -m "docs: explain session-only budgets"
```
