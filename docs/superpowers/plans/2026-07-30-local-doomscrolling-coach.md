# Local Doomscrolling Coach Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a private, local-first Chrome extension for macOS that detects sustained passive use of Instagram Reels, X/Twitter feeds, and YouTube Shorts, then applies the user's configured intervention.

**Architecture:** Manifest V3 content scripts identify only supported views and emit normalized local events. A background service worker owns persisted rules, daily allowances, per-tab scoring sessions, feedback tuning, and all enforcement decisions; it tells the originating content script to display a pause overlay, closes its tab, or installs narrowly scoped temporary declarative-network-request rules. The popup and options page read and update the same local settings through typed service-worker messages.

**Tech Stack:** Chrome Manifest V3, TypeScript, Vite, `@crxjs/vite-plugin`, Vitest, Chrome `storage`, `tabs`, and `declarativeNetRequest` APIs.

## Global Constraints

- Target Google Chrome on macOS; do not add desktop-app, mobile-app, account, cloud-sync, or analytics code.
- Support only `instagram.com/reels`, `x.com` / `twitter.com` timeline routes, and `youtube.com/shorts` in this prototype.
- Store all preferences, behavioral events, aggregate usage, intervention history, and feedback in `chrome.storage.local`; do not make network requests or capture screenshots.
- Fail open: an unidentified page, unsupported route, missing event data, missing permissions, or a disabled rule must produce no enforcement.
- Never close a tab or create a temporary block unless the applicable site's enabled rule explicitly selects that action.
- Do not persist raw scroll coordinates, DOM text, URLs beyond the supported site key, page content, browser history, or screenshots.
- Reset daily allowance accounting at the user's local calendar-day boundary, calculated from `new Date().toLocaleDateString('en-CA')`.
- Keep a maximum of 200 local intervention records, deleting only the oldest record when appending a new one above the cap.

---

## File Structure

| Path | Responsibility |
| --- | --- |
| `package.json` | Extension scripts and development dependencies. |
| `vite.config.ts` | Builds the Manifest V3 bundle. |
| `tsconfig.json` | TypeScript settings shared by source and tests. |
| `manifest.config.ts` | CRX manifest: local-only permissions, content-script matches, popup, and options page. |
| `src/shared/types.ts` | Site IDs, normalized events, configuration, session state, interventions, and messages. |
| `src/shared/constants.ts` | Defaults, detection weights, expiry constants, and supported site metadata. |
| `src/shared/time.ts` | Local-day and elapsed-time helpers. |
| `src/shared/storage.ts` | Typed, local-only persistence with schema defaults, bounded history, and daily usage reset. |
| `src/engine/score.ts` | Pure confidence-score state transition function. |
| `src/engine/rules.ts` | Pure rule evaluation and next-action selection. |
| `src/background/index.ts` | Service-worker message handling, per-tab sessions, usage ticker, DNR blocks, and tab closure. |
| `src/content/index.ts` | Detects supported view, selects adapter, forwards events, and renders commands. |
| `src/content/adapters/base.ts` | Common DOM observer lifecycle and typed adapter contract. |
| `src/content/adapters/instagram.ts` | Conservative Instagram Reels route and event adapter. |
| `src/content/adapters/x.ts` | Conservative X/Twitter timeline route and event adapter. |
| `src/content/adapters/youtube.ts` | Conservative YouTube Shorts route and event adapter. |
| `src/content/overlay.ts` | Accessible notification and deliberate full-page pause overlay. |
| `src/popup/*` | Compact status / toggle UI. |
| `src/options/*` | Per-site rule editor, local review, feedback controls, and privacy copy. |
| `tests/*.test.ts` | Unit tests for persistence, detection, rule evaluation, adapters, and message behavior. |

## Task 1: Scaffold the private Manifest V3 extension

**Files:**
- Create: `package.json`
- Create: `tsconfig.json`
- Create: `vite.config.ts`
- Create: `manifest.config.ts`
- Create: `src/shared/types.ts`
- Create: `src/shared/constants.ts`
- Create: `src/shared/time.ts`
- Create: `src/popup/index.html`
- Create: `src/options/index.html`

**Interfaces:**
- Produces: `SiteId`, `NormalizedEvent`, `SiteRule`, `Settings`, `SessionState`, `InterventionRecord`, `BackgroundRequest`, and `BackgroundResponse` types consumed by every later task.
- Produces: `SUPPORTED_SITES`, `DEFAULT_SETTINGS`, `DETECTION`, `BLOCK_RULE_ID_BASE`, `todayKey()`, and `elapsedMs()` constants/helpers.

- [ ] **Step 1: Write the failing configuration test**

Create `tests/manifest.test.ts`:

```ts
import manifest from '../manifest.config';

describe('manifest privacy boundary', () => {
  it('contains only the permissions required for local enforcement', () => {
    expect(manifest.manifest_version).toBe(3);
    expect(manifest.permissions).toEqual([
      'storage', 'tabs', 'declarativeNetRequest', 'notifications',
    ]);
    expect(manifest.host_permissions).toEqual([
      '*://*.instagram.com/*', '*://x.com/*', '*://twitter.com/*', '*://*.youtube.com/*',
    ]);
    expect(JSON.stringify(manifest)).not.toMatch(/https?:\/\/[^*]/);
  });
});
```

- [ ] **Step 2: Install the toolchain and run the test to verify it fails**

Run:

```bash
npm install
npm test -- --run tests/manifest.test.ts
```

Expected: FAIL because `manifest.config.ts` does not exist.

- [ ] **Step 3: Add the minimal project configuration and shared contracts**

Set `package.json` scripts to `dev: vite`, `build: vite build`, `test: vitest`, and `typecheck: tsc --noEmit`; add `@crxjs/vite-plugin`, `vite`, `typescript`, `vitest`, and `@types/chrome`. Configure Vite's CRX plugin with `manifest.config.ts` and configure Vitest for the Node environment.

Implement these exact core contracts in `src/shared/types.ts`:

```ts
export type SiteId = 'instagram-reels' | 'x-timeline' | 'youtube-shorts';
export type EventKind = 'view-entered' | 'view-left' | 'scroll' | 'content-advance' | 'purposeful-action';
export type InterventionKind = 'notify' | 'pause' | 'close-tab' | 'block';

export interface NormalizedEvent {
  site: SiteId;
  kind: EventKind;
  at: number;
  detail?: 'search' | 'profile' | 'post' | 'comment' | 'save' | 'message' | 'link';
}

export interface SiteRule {
  enabled: boolean;
  dailyAllowanceMinutes: number;
  warningScore: number;
  gracePeriodSeconds: number;
  interventions: InterventionKind[];
  blockUntil: 'tomorrow';
}

export interface Settings { enabled: boolean; rules: Record<SiteId, SiteRule>; }
export interface SessionState {
  site: SiteId; enteredAt: number; lastEventAt: number; lastPurposefulAt?: number;
  score: number; consecutiveAdvances: number; continuousScrolls: number;
  warnedAt?: number; pauseShownAt?: number;
}
```

Set all default rules disabled; use a 15-minute daily allowance, score threshold 10, 60-second grace period, and intervention sequence `['notify', 'pause', 'close-tab', 'block']` when a rule is enabled. Set `todayKey()` to return `new Date(at).toLocaleDateString('en-CA')`.

- [ ] **Step 4: Run the focused test, type check, and production build**

Run:

```bash
npm test -- --run tests/manifest.test.ts
npm run typecheck
npm run build
```

Expected: all commands pass and `dist/manifest.json` is generated.

- [ ] **Step 5: Commit the scaffold**

```bash
git add package.json package-lock.json tsconfig.json vite.config.ts manifest.config.ts src tests
git commit -m "chore: scaffold local focus extension"
```

## Task 2: Persist local-only settings, daily usage, feedback, and bounded history

**Files:**
- Create: `src/shared/storage.ts`
- Create: `tests/storage.test.ts`
- Modify: `src/shared/types.ts`

**Interfaces:**
- Consumes: `Settings`, `SiteId`, `SiteRule`, `InterventionKind`, `todayKey()` from Task 1.
- Produces: `getSettings(): Promise<Settings>`, `saveSettings(settings: Settings): Promise<void>`, `getUsage(site, now): Promise<number>`, `addUsage(site, milliseconds, now): Promise<number>`, `appendIntervention(record): Promise<void>`, `listInterventions(): Promise<InterventionRecord[]>`, and `setFeedback(id, feedback): Promise<void>`.

- [ ] **Step 1: Write failing storage tests with an in-memory `chrome.storage.local` stub**

Create `tests/storage.test.ts`:

```ts
import { addUsage, appendIntervention, getUsage, listInterventions } from '../src/shared/storage';

describe('local persistence', () => {
  it('resets one site usage when the local day changes', async () => {
    await addUsage('instagram-reels', 120_000, Date.parse('2026-07-30T23:59:00'));
    expect(await getUsage('instagram-reels', Date.parse('2026-07-31T00:01:00'))).toBe(0);
  });

  it('keeps the newest 200 intervention records', async () => {
    for (let index = 0; index < 201; index += 1) {
      await appendIntervention({ id: String(index), at: index, site: 'x-timeline', kind: 'notify', reason: 'test' });
    }
    const records = await listInterventions();
    expect(records).toHaveLength(200);
    expect(records[0].id).toBe('1');
  });
});
```

Define a reusable `installChromeStorageStub()` in `tests/chrome-storage.ts` that implements async `get`, `set`, and `remove` with a `Map`; call it in `beforeEach`.

- [ ] **Step 2: Run the test to verify it fails**

Run:

```bash
npm test -- --run tests/storage.test.ts
```

Expected: FAIL because the storage module is missing.

- [ ] **Step 3: Implement the local storage facade**

Add `InterventionRecord` with `{ id, at, site, kind, reason, feedback?: 'accurate' | 'inaccurate' }`, then implement `src/shared/storage.ts` with exactly four storage keys: `settings`, `usage`, `interventions`, and `blocks`. Use `chrome.storage.local`, never `sync` or `managed`. Represent usage as `Record<SiteId, { day: string; milliseconds: number }>`; `getUsage` returns zero when its stored `day` differs from `todayKey(now)`. `appendIntervention` must retain `records.slice(-199)` before appending a new record. `setFeedback` must update only the matching local record and leave unknown IDs unchanged.

- [ ] **Step 4: Run storage tests and the complete unit suite**

Run:

```bash
npm test -- --run tests/storage.test.ts
npm test -- --run
```

Expected: PASS.

- [ ] **Step 5: Commit the local persistence layer**

```bash
git add src/shared tests/storage.test.ts tests/chrome-storage.ts
git commit -m "feat: persist local focus rules and history"
```

## Task 3: Implement transparent doomscroll scoring and rule evaluation

**Files:**
- Create: `src/engine/score.ts`
- Create: `src/engine/rules.ts`
- Create: `tests/score.test.ts`
- Create: `tests/rules.test.ts`
- Modify: `src/shared/constants.ts`
- Modify: `src/shared/types.ts`

**Interfaces:**
- Consumes: `NormalizedEvent`, `SessionState`, `SiteRule`, `InterventionKind` from Task 1 and local usage milliseconds from Task 2.
- Produces: `initialSession(event): SessionState`, `applyEvent(session, event): SessionState`, `sessionReason(session): string`, and `nextIntervention(input): InterventionDecision`.

- [ ] **Step 1: Write failing scoring tests**

Create `tests/score.test.ts`:

```ts
import { applyEvent, initialSession } from '../src/engine/score';

const start = { site: 'youtube-shorts' as const, kind: 'view-entered' as const, at: 1_000 };

it('does not score a one-off short', () => {
  const state = applyEvent(initialSession(start), { ...start, kind: 'content-advance', at: 20_000 });
  expect(state.score).toBeLessThan(10);
});

it('raises confidence for sustained passive advances and scrolling', () => {
  let state = initialSession(start);
  for (let i = 1; i <= 8; i += 1) state = applyEvent(state, { ...start, kind: 'content-advance', at: i * 20_000 });
  for (let i = 9; i <= 14; i += 1) state = applyEvent(state, { ...start, kind: 'scroll', at: i * 20_000 });
  expect(state.score).toBeGreaterThanOrEqual(10);
});

it('materially reduces confidence after a purposeful action', () => {
  const state = applyEvent({ ...initialSession(start), score: 12 }, { ...start, kind: 'purposeful-action', detail: 'comment', at: 10_000 });
  expect(state.score).toBeLessThan(10);
});
```

Create `tests/rules.test.ts`:

```ts
import { nextIntervention } from '../src/engine/rules';

const rule = { enabled: true, dailyAllowanceMinutes: 15, warningScore: 10, gracePeriodSeconds: 60, interventions: ['notify', 'pause', 'close-tab', 'block'] as const, blockUntil: 'tomorrow' as const };

it('fails open below the score threshold', () => {
  expect(nextIntervention({ rule, score: 9, usageMs: 0, now: 10_000 })).toEqual({ kind: 'none' });
});

it('escalates after the warning grace period', () => {
  expect(nextIntervention({ rule, score: 12, usageMs: 0, now: 70_001, warnedAt: 10_000 })).toMatchObject({ kind: 'pause' });
});

it('enforces the configured action when daily allowance is exhausted', () => {
  expect(nextIntervention({ rule, score: 0, usageMs: 900_000, now: 1_000 })).toMatchObject({ kind: 'close-tab', reason: 'Daily allowance reached' });
});
```

- [ ] **Step 2: Run the engine tests to verify they fail**

Run:

```bash
npm test -- --run tests/score.test.ts tests/rules.test.ts
```

Expected: FAIL because the engine modules do not exist.

- [ ] **Step 3: Implement the deterministic model**

Use these constants in `DETECTION`: `advancePoints: 2`, `scrollPoints: 1`, `purposefulScoreMultiplier: 0.25`, `sessionIdleMs: 90_000`, and `minimumPassiveMs: 120_000`. `applyEvent` must reset to an initial state if the event is older than `lastEventAt` or follows a 90-second idle gap. Add points only after 120 seconds in the supported view; `purposeful-action` must set `lastPurposefulAt`, multiply score by 0.25, and reset consecutive advances and continuous scrolls. `sessionReason` must return a plain-language string such as `"8 content advances and 6 continuous scrolls over 4m 40s"` without page content.

Implement `nextIntervention` as a pure function. Return `{ kind: 'none' }` for disabled rules, scores below the threshold, and a rule whose next configured action is absent. Before the allowance is exhausted, return `notify` at threshold, then the next configured action only after `gracePeriodSeconds`. If allowance is exhausted, choose the final configured enforcement action in the ordered list (`block`, otherwise `close-tab`, otherwise `pause`, otherwise `notify`) with reason `Daily allowance reached`. It must not invent an action not in `rule.interventions`.

- [ ] **Step 4: Run all engine tests**

Run:

```bash
npm test -- --run tests/score.test.ts tests/rules.test.ts
npm run typecheck
```

Expected: PASS.

- [ ] **Step 5: Commit the detection engine**

```bash
git add src/engine src/shared tests/score.test.ts tests/rules.test.ts
git commit -m "feat: add transparent doomscroll detection"
```

## Task 4: Build conservative site adapters and the accessible intervention overlay

**Files:**
- Create: `src/content/adapters/base.ts`
- Create: `src/content/adapters/instagram.ts`
- Create: `src/content/adapters/x.ts`
- Create: `src/content/adapters/youtube.ts`
- Create: `src/content/overlay.ts`
- Create: `src/content/index.ts`
- Create: `tests/adapters.test.ts`
- Create: `tests/overlay.test.ts`

**Interfaces:**
- Consumes: `NormalizedEvent`, `SiteId`, and background message contracts from Tasks 1–3.
- Produces: `createAdapter(site, emit): PageAdapter | undefined`, `renderIntervention(command): void`, and content-to-background `event` messages.

- [ ] **Step 1: Write failing adapter and overlay tests**

Create `tests/adapters.test.ts`:

```ts
import { detectSite } from '../src/content/adapters/base';

it.each([
  ['https://www.instagram.com/reels/', 'instagram-reels'],
  ['https://x.com/home', 'x-timeline'],
  ['https://twitter.com/home', 'x-timeline'],
  ['https://www.youtube.com/shorts/abc123', 'youtube-shorts'],
])('identifies only supported routes', (url, site) => {
  expect(detectSite(new URL(url))).toBe(site);
});

it.each(['https://www.instagram.com/direct/inbox/', 'https://x.com/messages', 'https://www.youtube.com/watch?v=abc'])('fails open for unsupported routes', (url) => {
  expect(detectSite(new URL(url))).toBeUndefined();
});
```

Create `tests/overlay.test.ts`:

```ts
import { showPauseOverlay } from '../src/content/overlay';

it('requires a deliberate continue or leave choice', () => {
  showPauseOverlay({ site: 'youtube-shorts', reason: 'test', allowContinue: true });
  expect(document.querySelector('[role="dialog"]')).toHaveTextContent('You’re in a scrolling loop');
  expect(document.querySelectorAll('[role="dialog"] button')).toHaveLength(2);
});
```

Configure the Vitest `jsdom` environment and add `@testing-library/jest-dom` only if required for the matcher above.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run:

```bash
npm test -- --run tests/adapters.test.ts tests/overlay.test.ts
```

Expected: FAIL because the content modules do not exist.

- [ ] **Step 3: Implement fail-open adapters and overlay behavior**

Implement `detectSite(url)` with exact route guards: Instagram pathname starts `/reels`; X/Twitter pathname equals `/home` or `/i/flow/login` is excluded; YouTube pathname starts `/shorts/`. For each adapter, emit `view-entered` only after its route passes `detectSite`; emit `scroll` from a throttled `scroll` listener (one event per 750 ms); emit `content-advance` only after a conservative DOM signal that identifies a changed Reel/Short or a feed scroll that moved at least one viewport; emit purposeful actions for search, profile/post navigation, commenting, saving, messages, and normal links based on click targets / route changes. Every observer must disconnect on `stop()` and emit `view-left`; if a selector is absent, emit no event rather than guessing.

Implement `showNotification` and `showPauseOverlay` using a shadow-root host with `role="dialog"`, keyboard focus on the first button, and two explicit choices: `Leave` (send `dismiss-pause`) and `Continue for 5 minutes` (send `temporary-continue`). Do not use page text, screenshots, remote CSS, or analytics. `src/content/index.ts` should start an adapter only on a supported route, use `chrome.runtime.sendMessage({ type: 'event', event })`, and render only a validated background command for its matching `SiteId`.

- [ ] **Step 4: Run content tests and production build**

Run:

```bash
npm test -- --run tests/adapters.test.ts tests/overlay.test.ts
npm run build
```

Expected: PASS.

- [ ] **Step 5: Commit adapters and overlay**

```bash
git add src/content tests/adapters.test.ts tests/overlay.test.ts package.json package-lock.json vite.config.ts
git commit -m "feat: observe supported feeds and show pause overlay"
```

## Task 5: Connect background evaluation, allowance tracking, blocking, and safe enforcement

**Files:**
- Create: `src/background/index.ts`
- Create: `tests/background.test.ts`
- Modify: `manifest.config.ts`
- Modify: `src/shared/types.ts`
- Modify: `src/shared/storage.ts`

**Interfaces:**
- Consumes: adapters' `event` messages, `applyEvent`, `nextIntervention`, and storage functions from Tasks 2–4.
- Produces: `handleEvent(tabId, event, now): Promise<InterventionDecision>`, `installBlock(site, expiresAt): Promise<void>`, `removeExpiredBlocks(now): Promise<void>`, and UI query/update messages.

- [ ] **Step 1: Write failing background tests with Chrome API spies**

Create `tests/background.test.ts`:

```ts
import { handleEvent } from '../src/background/index';

it('does not enforce when a page event is unsupported or the rule is disabled', async () => {
  await expect(handleEvent(1, { site: 'x-timeline', kind: 'scroll', at: 10_000 }, 10_000)).resolves.toEqual({ kind: 'none' });
  expect(chrome.tabs.remove).not.toHaveBeenCalled();
  expect(chrome.declarativeNetRequest.updateDynamicRules).not.toHaveBeenCalled();
});

it('closes only the originating tab for an enabled close-tab decision', async () => {
  // Install an enabled X rule and feed enough normalized events to pass the configured threshold.
  await handleEvent(72, { site: 'x-timeline', kind: 'view-entered', at: 0 }, 0);
  for (let i = 1; i <= 20; i += 1) await handleEvent(72, { site: 'x-timeline', kind: 'content-advance', at: i * 20_000 }, i * 20_000);
  expect(chrome.tabs.remove).toHaveBeenCalledWith(72);
});
```

Extend the Chrome test stub with `runtime.onMessage`, `tabs.remove`, `tabs.sendMessage`, `notifications.create`, `declarativeNetRequest.getDynamicRules`, and `declarativeNetRequest.updateDynamicRules` spies.

- [ ] **Step 2: Run the background test to verify it fails**

Run:

```bash
npm test -- --run tests/background.test.ts
```

Expected: FAIL because the service worker is missing.

- [ ] **Step 3: Implement service-worker ownership and enforcement**

Keep sessions in `Map<number, SessionState>` keyed by sender tab ID. Reject events with no `sender.tab?.id`, events whose site does not match a supported ID, and events from a disabled extension / disabled rule. On every accepted event, calculate elapsed foreground-view time since the prior event (capped at 30 seconds), call `addUsage`, apply the score transition, save only aggregate usage/history, and ask `nextIntervention` for a decision.

For `notify`, send an overlay command and create a Chrome notification with the local reason. For `pause`, send a pause command. For `close-tab`, call `chrome.tabs.remove(tabId)` only after `appendIntervention`. For `block`, create a dynamic DNR block rule restricted to the selected site paths: `*://*.instagram.com/reels/*`, `*://x.com/home*`, `*://twitter.com/home*`, or `*://*.youtube.com/shorts/*`; store expiry as the next local midnight. Keep rule IDs deterministic as `BLOCK_RULE_ID_BASE + supported-site index`, removing an existing ID before replacing it. Run `removeExpiredBlocks(Date.now())` at service-worker startup and before every block operation. A `temporary-continue` message must set a per-tab, non-persisted 5-minute suppression; it must not modify daily allowance or block data.

Register `chrome.runtime.onMessage` routes for `event`, `get-status`, `save-settings`, `get-interventions`, `set-feedback`, `dismiss-pause`, and `temporary-continue`. Return structured errors only to the extension UI, never page content.

- [ ] **Step 4: Run background tests, full tests, type check, and build**

Run:

```bash
npm test -- --run tests/background.test.ts
npm test -- --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 5: Commit the enforcement engine**

```bash
git add src/background src/shared manifest.config.ts tests/background.test.ts tests/chrome-storage.ts
git commit -m "feat: enforce local focus rules safely"
```

## Task 6: Create setup, status, rules, and private-review interfaces

**Files:**
- Create: `src/popup/main.ts`
- Create: `src/popup/style.css`
- Create: `src/options/main.ts`
- Create: `src/options/style.css`
- Modify: `src/popup/index.html`
- Modify: `src/options/index.html`
- Create: `tests/options.test.ts`
- Modify: `manifest.config.ts`

**Interfaces:**
- Consumes: `get-status`, `save-settings`, `get-interventions`, and `set-feedback` message routes from Task 5.
- Produces: a popup with enabled state and current allowance, plus an options page that edits every `SiteRule`, displays reasons, accepts feedback, and states the local-only privacy guarantee.

- [ ] **Step 1: Write the failing options UI tests**

Create `tests/options.test.ts`:

```ts
import { renderOptions } from '../src/options/main';

it('shows the local-only privacy promise and all supported site controls', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);
  expect(document.body.textContent).toContain('Nothing leaves this device');
  expect(document.querySelectorAll('[data-site-rule]')).toHaveLength(3);
});

it('requires an explicit save before an edited rule is activated', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);
  const checkbox = document.querySelector<HTMLInputElement>('[data-site-rule="instagram-reels"] input[type="checkbox"]')!;
  checkbox.click();
  expect(chrome.runtime.sendMessage).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'save-settings' }));
  document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
  expect(chrome.runtime.sendMessage).toHaveBeenCalledWith(expect.objectContaining({ type: 'save-settings' }));
});
```

- [ ] **Step 2: Run the UI test to verify it fails**

Run:

```bash
npm test -- --run tests/options.test.ts
```

Expected: FAIL because the options UI is missing.

- [ ] **Step 3: Implement compact, explicit UI flows**

Configure `action.default_popup` for the popup and `options_page` for the settings page. The popup must show whether protection is enabled, three site rows with used/allowed minutes, active session status only when a session exists, and a link to Options. The options page must contain a global toggle; a `data-site-rule` form section for each supported site; inputs for enablement, daily allowance, warning score, grace seconds, checkbox intervention sequence, and block duration fixed as `Until tomorrow`; a visible structured rule summary before Save; a review list with timestamp, site, reason, intervention, and `Accurate` / `Inaccurate` buttons; and this exact copy: `Nothing leaves this device. This extension does not record screenshots, upload browsing history, or send behavioral data to a server.`

Validate before save: allowance must be an integer from 1–240, warning score an integer from 1–50, grace seconds an integer from 0–600, and each enabled rule needs at least one intervention. Show inline errors and keep the old persisted settings if validation fails. Use text content / DOM builders, never unsafe `innerHTML` with dynamic data. Disable a rule immediately only after a valid saved settings response. Feedback controls send only the record ID and classification to the service worker.

- [ ] **Step 4: Run UI test, full suite, typecheck, and production build**

Run:

```bash
npm test -- --run tests/options.test.ts
npm test -- --run
npm run typecheck
npm run build
```

Expected: PASS.

- [ ] **Step 5: Commit the extension interfaces**

```bash
git add src/popup src/options manifest.config.ts tests/options.test.ts
git commit -m "feat: add local focus controls and review"
```

## Task 7: Document installation, manual safety checks, and production readiness

**Files:**
- Create: `README.md`
- Create: `docs/manual-test-checklist.md`
- Create: `.gitignore`
- Modify: `package.json`
- Create: `tests/privacy-boundary.test.ts`

**Interfaces:**
- Consumes: the built `dist/` extension and every user-visible flow from Tasks 1–6.
- Produces: reproducible installation instructions and a release gate that demonstrates local-only behavior and fail-open enforcement.

- [ ] **Step 1: Write the failing privacy boundary test**

Create `tests/privacy-boundary.test.ts`:

```ts
import { readFileSync } from 'node:fs';
import { globSync } from 'glob';

it('contains no fetch clients, remote URLs, telemetry, or screenshot API usage in extension source', () => {
  const source = globSync('src/**/*.{ts,css,html}').map((path) => readFileSync(path, 'utf8')).join('\n');
  expect(source).not.toMatch(/\b(fetch|XMLHttpRequest|WebSocket|sendBeacon|captureVisibleTab)\b/);
  expect(source).not.toMatch(/analytics|telemetry|sentry/i);
});
```

Add `glob` as a development dependency.

- [ ] **Step 2: Run the privacy test to verify its starting state**

Run:

```bash
npm test -- --run tests/privacy-boundary.test.ts
```

Expected: PASS if the earlier work respected the local-only boundary; otherwise remove the violating code before proceeding.

- [ ] **Step 3: Write user documentation and a manual test checklist**

In `README.md`, document `npm install`, `npm run build`, Chrome's `chrome://extensions` developer-mode unpacked load of `dist/`, the three supported routes, each intervention, and exact privacy limits. State that the extension does not monitor other apps, page content, messages, screenshots, or unsupported routes. Explain that Instagram/X/YouTube DOM changes can disable detection and that this fails open.

In `docs/manual-test-checklist.md`, add checkboxes for: unsupported routes generate no action; a single Reel/Short causes no warning; a search, profile visit, comment, save, message, or link lowers confidence; sustained scrolling produces a reasoned warning; pause `Leave` exits, while `Continue for 5 minutes` suppresses enforcement only for that tab; allowance exhaustion follows the configured action; enabled DNR blocks expire next local day; emergency rule disable takes effect immediately; reload preserves only local settings/history; Chrome DevTools Network has no requests from the extension; and a missing selector causes no action.

Add `dist/`, `node_modules/`, and `.DS_Store` to `.gitignore`.

- [ ] **Step 4: Run the complete automated verification and manual build check**

Run:

```bash
npm test -- --run
npm run typecheck
npm run build
git status --short
```

Expected: all automated commands pass; `dist/` is ignored; only intended source, tests, docs, and lockfile changes remain.

- [ ] **Step 5: Commit the documentation and verification gate**

```bash
git add README.md docs/manual-test-checklist.md .gitignore package.json package-lock.json tests/privacy-boundary.test.ts
git commit -m "docs: add local extension setup and safety checks"
```

## Self-Review

### Spec coverage

- macOS Chrome extension and the three named experiences: Tasks 1, 4, and 7.
- Fully local interaction analysis without screenshots or uploads: Global Constraints, Tasks 1, 2, and 7.
- Per-site daily allowances, warnings, pause screen, tab closing, and until-tomorrow block: Tasks 2, 3, 5, and 6.
- Transparent detection based on passive advances/scrolling and reduced confidence after purposeful actions: Tasks 3 and 4.
- Explicit local feedback and private review: Tasks 2 and 6.
- Local setup, rules, status, and privacy copy: Task 6.
- Fail-open behavior for uncertainty, missing selectors, unsupported routes, and disabled rules: Global Constraints, Tasks 4, 5, and 7.
- Automated and manual regression coverage for all requested behaviors: Tasks 1–7, especially Task 7.

### Placeholder scan

The plan contains no `TODO`, `TBD`, "implement later", or generic testing placeholders. Each implementation task names its concrete files, inputs/outputs, test command, expected outcome, and commit command.

### Type consistency

All adapters emit `NormalizedEvent`; background receives those events and evaluates `SessionState` with `SiteRule`; storage persists `Settings`, aggregate usage, and `InterventionRecord`; popup/options use the named service-worker messages. `SiteId` remains the same three-value union throughout.
