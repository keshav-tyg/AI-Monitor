# Focus Sensitivity Levels and YouTube Shorts Intent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dashboard warning-score entry with Mild/Medium/Aggressive focus-sensitivity choices and reliably show the intent prompt after advancing to a second YouTube Short.

**Architecture:** Keep `warningScore` as the existing numeric persistence and IPC field; introduce a dashboard-only `FocusSensitivity` mapping that preserves untouched legacy values. Add a pure YouTube Shorts route helper and use its item-ID transition as the authoritative YouTube advance signal, while disabling the duplicate player-source advance probe.

**Tech Stack:** Java 21, JavaFX, JUnit 5, TypeScript, Chrome MV3 APIs, Vitest, Vite.

**Spec:** `docs/superpowers/specs/2026-08-20-focus-sensitivity-youtube-design.md`

## Global Constraints

- Dashboard displays exactly Mild (`10`), Medium (`5`), and Aggressive (`1`), in that order.
- Mild/`10` is the new-rule default; raw numeric warning-score entry is not exposed to users.
- SQLite, service, relay, and extension payloads retain the numeric `warningScore` field and accept legacy integers `1..50`.
- Non-preset legacy values are displayed as a nearest level but preserve their exact integer until a sensitivity level is deliberately selected and saved.
- Strict Mode continues comparing the numeric value: lower is stricter and higher is weaker.
- A directly opened `/shorts/<id>` remains a free item; the next distinct valid Short URL emits exactly one content advance.
- A malformed or unsupported URL emits no YouTube advance; the player mutation must not duplicate route-driven advances.
- No titles, captions, URLs, or identifiers may reach the on-device model.
- Run the TypeScript suite, Java suite, extension build, and macOS `jpackage` build before completion.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusSensitivity.java` | Package-private canonical levels, score mapping, and legacy display mapping. |
| `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusSensitivityTest.java` | Pure mapping and legacy-preservation tests. |
| `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java` | JavaFX preset controls, draft tracking, and exact save behavior. |
| `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java` | Dashboard rendering and save-payload regressions. |
| `desktop/strict-core/src/test/java/com/localfocuscoach/strict/focus/FocusSettingsValidatorTest.java` | Strict Mode numeric comparison regressions using the canonical levels. |
| `src/content/youtube-shorts-route.ts` | Pure URL parsing and one-per-ID advance decision for Shorts. |
| `tests/youtube-shorts-route.test.ts` | URL parsing, initial item, changed item, duplicate, and unsupported-route tests. |
| `src/content/content-script.ts` | Route helper integration and emission of a route-driven content advance. |
| `src/content/adapters/youtube.ts` | Removes the competing media-source advance probe. |
| `tests/intent-session.test.ts` | Confirms a YouTube direct-link arrival prompts on the first advance. |
| `README.md` and `docs/manual-test-checklist.md` | Plain-language sensitivity and YouTube manual-test documentation. |

## Task 1: Define Canonical Focus Sensitivity

**Files:**
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusSensitivity.java`
- Create: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusSensitivityTest.java`

**Interfaces:**
- Produces `enum FocusSensitivity` with `MILD`, `MEDIUM`, and `AGGRESSIVE`.
- Produces `int warningScore()` and `static FocusSensitivity forStoredScore(int warningScore)`.
- `forStoredScore` maps `1..3` to Aggressive, `4..7` to Medium, and `8..50` to Mild; it throws `IllegalArgumentException` outside `1..50`.

- [ ] **Step 1: Write the failing mapping tests**

```java
@Test
void exposesLevelsInGentlestToStrictestOrder() {
    assertEquals(List.of(FocusSensitivity.MILD, FocusSensitivity.MEDIUM,
                    FocusSensitivity.AGGRESSIVE),
            List.of(FocusSensitivity.values()));
    assertEquals(10, FocusSensitivity.MILD.warningScore());
    assertEquals(5, FocusSensitivity.MEDIUM.warningScore());
    assertEquals(1, FocusSensitivity.AGGRESSIVE.warningScore());
}

@Test
void mapsLegacyScoresWithoutNormalizingTheirStoredValues() {
    assertEquals(FocusSensitivity.AGGRESSIVE, FocusSensitivity.forStoredScore(2));
    assertEquals(FocusSensitivity.MEDIUM, FocusSensitivity.forStoredScore(6));
    assertEquals(FocusSensitivity.MILD, FocusSensitivity.forStoredScore(10));
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run:

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test --tests com.localfocuscoach.strict.dashboard.FocusSensitivityTest
```

Expected: compilation failure because `FocusSensitivity` does not exist.

- [ ] **Step 3: Implement the smallest canonical enum**

```java
enum FocusSensitivity {
    MILD(10),
    MEDIUM(5),
    AGGRESSIVE(1);

    private final int warningScore;

    FocusSensitivity(int warningScore) {
        this.warningScore = warningScore;
    }

    int warningScore() {
        return warningScore;
    }

    static FocusSensitivity forStoredScore(int warningScore) {
        if (warningScore < 1 || warningScore > 50) {
            throw new IllegalArgumentException("Warning score must be 1 to 50");
        }
        if (warningScore <= 3) return AGGRESSIVE;
        if (warningScore <= 7) return MEDIUM;
        return MILD;
    }
}
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run the command from Step 2.

Expected: `FocusSensitivityTest` passes.

- [ ] **Step 5: Commit the mapping unit**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusSensitivity.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusSensitivityTest.java
git commit -m "feat(dashboard): define focus sensitivity levels"
```

## Task 2: Replace Dashboard Numeric Input with Presets

**Files:**
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java`
- Modify: `desktop/strict-core/src/test/java/com/localfocuscoach/strict/focus/FocusSettingsValidatorTest.java`

**Interfaces:**
- `RuleControls` changes from an immutable record into a private mutable helper class. It replaces `TextField warningScore` with a `ToggleGroup` plus one `RadioButton` per `FocusSensitivity`, and owns `loadedWarningScore` plus `sensitivityChanged`.
- Every radio button uses the stable ID `<sitePrefix>Sensitivity<Mild|Medium|Aggressive>`.
- `RuleControls.warningScoreForSave()` returns the original legacy score until a user selection changes it; otherwise it returns the selected level's `warningScore()`.
- Existing service/relay payload remains `{ "warningScore": number }`.

- [ ] **Step 1: Write failing dashboard interaction tests**

Add tests that load a settings response with score `10`, assert the Mild radio button is selected and `#instagramReelsWarningScore` is absent, then select Medium, save, and assert the outgoing payload contains `warningScore: 5`.

```java
assertNotNull(view.lookup("#instagramReelsSensitivityMild"));
assertNull(view.lookup("#instagramReelsWarningScore"));
assertTrue(((RadioButton) view.lookup("#instagramReelsSensitivityMild")).isSelected());

((RadioButton) view.lookup("#instagramReelsSensitivityMedium")).fire();
fire(view, "#saveFocusRules");
assertEquals(5, instagramRule(savedRequest).get("warningScore"));
```

Add a second test that loads score `8`, changes only `#instagramReelsBudget`, saves, and asserts the outgoing payload still contains `warningScore: 8`.

Add a third test that loads score `1`, selects Mild, receives the existing
`error.focusSettingsWeakening` response, and asserts the selected Aggressive-to-Mild
draft is still visible with the Strict Mode restriction message.

- [ ] **Step 2: Run the focused tests to verify they fail**

Run:

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test --tests com.localfocuscoach.strict.dashboard.FocusRulesViewTest
```

Expected: failures because the view still renders a numeric warning-score text field and has no sensitivity controls.

- [ ] **Step 3: Replace the warning-score field with radio controls**

In `FocusRulesView`:

1. Import `RadioButton` and `ToggleGroup` and remove warning-score field creation and its integer validation.
2. Render `new Label("Focus sensitivity")` followed by Mild, Medium, and Aggressive radio buttons in `FocusSensitivity.values()` order. Give each button the stable IDs defined above.
3. Store `loadedWarningScore` and `sensitivityChanged` in each `RuleControls`. `renderSnapshot` calls `controls.renderSensitivity(rule.warningScore())`; that method selects `FocusSensitivity.forStoredScore(rule.warningScore())`, records the exact score, then resets `sensitivityChanged` to `false`.
4. Set `sensitivityChanged` only from a user action listener, not from programmatic selection during render.
5. Build each proposed `FocusRule` using `controls.warningScoreForSave()`.
6. Include every sensitivity radio button in draft-generation tracking and `setFormDisabled`.
7. Replace the old visible label with the three explanations from the spec. Do not change protocol serialization, `FocusRule`, validation, or database schema.

The core save decision must follow this shape:

```java
int warningScoreForSave() {
    return sensitivityChanged
            ? selectedSensitivity().warningScore()
            : loadedWarningScore;
}
```

- [ ] **Step 4: Add strict numeric regression coverage**

In `FocusSettingsValidatorTest`, construct an existing rule at `10` and assert a proposed rule at `5` is accepted by strict validation. Construct an existing rule at `5` and assert proposed `10` throws the existing weakening exception. Use `FocusRule` values directly so the test proves the persisted numeric contract rather than JavaFX behavior.

- [ ] **Step 5: Run dashboard and core tests to verify they pass**

Run:

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test :strict-core:test
```

Expected: all focused dashboard and core tests pass, including the legacy score `8` preservation and strict numeric comparisons.

- [ ] **Step 6: Commit the dashboard preset unit**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java \
  desktop/strict-core/src/test/java/com/localfocuscoach/strict/focus/FocusSettingsValidatorTest.java
git commit -m "feat(dashboard): replace warning score with levels"
```

## Task 3: Emit YouTube Shorts Advances from Route IDs

**Files:**
- Create: `src/content/youtube-shorts-route.ts`
- Create: `tests/youtube-shorts-route.test.ts`
- Modify: `src/content/content-script.ts`
- Modify: `src/content/adapters/youtube.ts`
- Modify: `tests/intent-session.test.ts`

**Interfaces:**
- Produces `youtubeShortId(href: string): string | undefined`.
- Produces `nextYouTubeShort(previousId: string | undefined, href: string): { id: string; advanced: boolean } | undefined`.
- `nextYouTubeShort(undefined, firstShortHref)` returns `{ id, advanced: false }`; a different valid ID returns `{ id, advanced: true }`; the same ID returns `{ id, advanced: false }`.
- The content script invokes this helper only while `activeSite === 'youtube-shorts'` and emits a normal `content-advance` event only when `advanced` is true.

- [ ] **Step 1: Write failing pure route tests**

```ts
import { nextYouTubeShort, youtubeShortId } from '../src/content/youtube-shorts-route';

it('uses the first valid Short as the initial item, not an advance', () => {
  expect(nextYouTubeShort(undefined, 'https://www.youtube.com/shorts/first')).toEqual({
    id: 'first', advanced: false,
  });
});

it('emits exactly one advance when the Short identifier changes', () => {
  expect(nextYouTubeShort('first', 'https://www.youtube.com/shorts/second')).toEqual({
    id: 'second', advanced: true,
  });
  expect(nextYouTubeShort('second', 'https://www.youtube.com/shorts/second')).toEqual({
    id: 'second', advanced: false,
  });
});

it('fails open for non-Shorts and malformed URLs', () => {
  expect(youtubeShortId('https://www.youtube.com/watch?v=first')).toBeUndefined();
  expect(youtubeShortId('not a URL')).toBeUndefined();
});
```

- [ ] **Step 2: Run the new test to verify it fails**

Run:

```bash
npm test -- --run tests/youtube-shorts-route.test.ts
```

Expected: test fails because `youtube-shorts-route.ts` does not exist.

- [ ] **Step 3: Implement the pure route helper**

Parse URLs with `new URL`, normalize an optional `www.` prefix, require host
`youtube.com`, and accept only a non-empty first path segment after `/shorts/`.
Do not return or transmit the original URL. Implement the state transition with
the exact `nextYouTubeShort` result shape above.

- [ ] **Step 4: Run the helper test to verify it passes**

Run the command from Step 2.

Expected: all route-helper tests pass.

- [ ] **Step 5: Integrate route-driven advances and remove the duplicate probe**

In `content-script.ts`:

1. Pass the changed href from `createRouteWatcher` into `syncToRoute(href)` and use that href for site detection and arrival classification.
2. Store the active YouTube Short ID after starting a YouTube adapter.
3. When a same-site route change supplies a new valid YouTube Short ID, finish the current engagement item, send it first, then send exactly one `content-advance` event, matching the existing adapter event ordering.
4. Update the active Short ID before returning so repeated router callbacks for the same item emit nothing.
5. Clear the active Short ID in `stopAdapter`.

In `adapters/youtube.ts`, remove `createMediaAdvanceProbe` and give the base
adapter `advanced: () => false`; the route is now YouTube's only item-advance
source.

The emission must retain existing ordering:

```ts
send({ type: 'engagement', site, record: tracker.finishItem() });
sendEvent({ site, kind: 'content-advance', at: Date.now() });
```

- [ ] **Step 6: Add and run the declaration-flow regression**

In `tests/intent-session.test.ts`, add a YouTube case that sends:

```ts
await routeForTest({ type: 'arrive', site: 'youtube-shorts', entryKind: 'deep-link' }, 72);
await routeForTest({ type: 'event', event: {
  site: 'youtube-shorts', kind: 'content-advance', at: 2_000,
}}, 72);
```

Assert that the tab command is `prompt-intent` for `youtube-shorts`. This is a
post-integration contract check: the pure-helper RED/GREEN cycle above proves
the new router decision, while this test proves the resulting normal event
reaches the existing declaration path without adding a special worker rule.

Run:

```bash
npm test -- --run tests/youtube-shorts-route.test.ts tests/intent-session.test.ts tests/adapters.test.ts tests/route-watcher.test.ts
```

Expected: a first Short remains free, a changed Short advances once and prompts,
repeated routes do nothing, and unsupported YouTube routes fail open.

- [ ] **Step 7: Commit the YouTube route unit**

```bash
git add src/content/youtube-shorts-route.ts tests/youtube-shorts-route.test.ts \
  src/content/content-script.ts src/content/adapters/youtube.ts \
  tests/intent-session.test.ts
git commit -m "fix(youtube): prompt after Short advances"
```

## Task 4: Document the Simplified Control and Verify the Product

**Files:**
- Modify: `README.md`
- Modify: `docs/manual-test-checklist.md`

**Interfaces:**
- README describes Focus sensitivity as Mild/`10`, Medium/`5`, Aggressive/`1` and keeps score engine mechanics as implementation detail.
- Manual checklist names YouTube's first-direct-link and second-Short prompt behavior.

- [ ] **Step 1: Write the documentation changes**

Replace end-user references to editing a numeric warning score with this exact
mapping:

```text
Mild — score 10 — intervenes after more sustained passive scrolling
Medium — score 5 — a balanced reminder
Aggressive — score 1 — intervenes quickly after passive scrolling begins
```

Add a manual check: directly open one `youtube.com/shorts/<id>` URL, confirm no
intent prompt on that first item, swipe to a second distinct Short, and confirm
the intent prompt appears once.

- [ ] **Step 2: Check documentation consistency**

Run:

```bash
rg -n "Warning score|warning score|1–50" README.md docs/manual-test-checklist.md desktop/strict-dashboard/src/main/java
```

Expected: dashboard source has no `Warning score` label or `1–50` warning-score text field; the README and manual checklist use Focus sensitivity labels for end-user setup. Internal numeric compatibility references remain allowed in source and documentation that explains the protocol.

- [ ] **Step 3: Run complete verification**

Run:

```bash
npm run typecheck
npm test
npm run build
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew test jpackage --rerun-tasks
```

Expected: TypeScript typecheck passes, all Vitest files pass, the extension build succeeds, all Java tests pass, and `desktop/build/jpackage/Local Focus Coach.app` is created.

- [ ] **Step 4: Commit documentation**

```bash
git add README.md docs/manual-test-checklist.md
git commit -m "docs: explain focus sensitivity levels"
```
