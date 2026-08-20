# Figma-Inspired Desktop Dashboard Refresh Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved Figma-inspired macOS dashboard, with Focus Rules automatic saving first and matching Strict Mode and unlock screens second.

**Architecture:** Keep the existing JavaFX views and authenticated dashboard/service protocol. `DashboardApp.DashboardView` becomes the shared sidebar shell. `FocusRulesView` owns responsive cards and a lifecycle-safe debounced latest-draft save state machine. `StrictModeView` and `UnlockChallengeView` receive presentation-only card/layout changes.

**Tech Stack:** Java 21, JavaFX, JUnit 5, Gradle, CSS, and the existing local Unix-socket `ServiceClient`.

**Spec:** `docs/superpowers/specs/2026-08-20-figma-dashboard-refresh-design.md`

## Global Constraints

- No service, relay, browser-extension, persistence, protocol, or model behavior changes; retain `dashboard.focusSettings.save` and numeric `warningScore`.
- Keep Focus Rules as default and every accessible test/control ID except removed `#saveFocusRules`.
- Sensitivity remains Mild, Medium, Aggressive, mapping to 10, 5, 1; untouched legacy scores remain exact.
- Auto-save waits 700 ms, coalesces valid edits, queues exactly one latest-draft save after an in-flight request, and does not lock form controls.
- Invalid numeric input sends no request. Strict Mode weakening errors preserve the visible draft.
- Dispose/navigation stops the auto-save and polling timers and ignores late callbacks.
- Strict Mode/unlock retain timed/indefinite behavior, warning facts, the 500-character challenge, backspace, paste/drop blocking, hidden mismatch behavior, and clipboard protections.
- Final verification uses Homebrew Java 21 for desktop tests, build, and macOS `jpackage`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `DashboardApp.java` | Shared app shell, sidebar navigation, scrolling, and selected state. |
| `dashboard.css` | Shared colors, typography, spacing, cards, navigation, and feedback styles. |
| `DashboardAppTest.java` | Shell, default route, sidebar, and navigation tests. |
| `FocusRulesView.java` | Responsive site cards and debounced auto-save state machine. |
| `FocusRulesViewTest.java` | Rendering, debounce, validation, queue, sync, and disposal tests. |
| `StrictModeView.java` | Strict Mode card presentation. |
| `UnlockChallengeView.java` | Matching unlock presentation with existing protections. |
| `StrictModeViewTest.java` | Strict Mode visual-hook and behavior coverage. |
| `UnlockChallengeViewTest.java` | Unlock visual-hook and security coverage. |

## Task 1: Create the Shared Desktop Shell

**Files:**
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardApp.java`
- Modify: `desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardAppTest.java`

**Interfaces:**
- Produces `#dashboardSidebar`, `#dashboardBrand`, `#dashboardPrivacy`, `#focusRulesNavigation`, and `#strictModeNavigation`.
- Produces the `activeNavigation` CSS class on exactly one navigation button. Selected navigation stays enabled and is visually active.

- [ ] **Step 1: Write failing shell tests**

Add assertions to `DashboardAppTest` after CSS has been applied:

```java
assertNotNull(dashboard.lookup("#dashboardSidebar"));
assertNotNull(dashboard.lookup("#dashboardBrand"));
assertNotNull(dashboard.lookup("#dashboardPrivacy"));
assertTrue(((Button) dashboard.lookup("#focusRulesNavigation"))
        .getStyleClass().contains("activeNavigation"));
assertFalse(((Button) dashboard.lookup("#strictModeNavigation")).isDisable());
```

Extend the current navigation test to fire Strict Mode and assert `activeNavigation` moves to `#strictModeNavigation` while both buttons remain enabled.

- [ ] **Step 2: Run the test to prove RED**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test --tests com.localfocuscoach.strict.dashboard.DashboardAppTest
```

Expected: FAIL because current navigation is a top `HBox`, has no sidebar IDs, and disables the selected button.

- [ ] **Step 3: Implement the sidebar shell**

In `DashboardApp.DashboardView`, replace the top `HBox` navigation with a left `VBox`: compact brand block, the existing navigation buttons, a growing spacer, and a plain-language privacy label. Keep the existing callbacks/IDs. Use a single style-state helper:

```java
private void setActiveNavigation(Button active) {
    for (var navigation : List.of(focusRulesNavigation, strictModeNavigation)) {
        navigation.getStyleClass().remove("activeNavigation");
    }
    active.getStyleClass().add("activeNavigation");
}
```

Use that helper from every route method instead of disabling a button. Wrap all destinations in a transparent fit-to-width `ScrollPane`. Add stylesheet classes for the warm off-white canvas, white cards, green active navigation, charcoal type, amber pending state, and red error state.

- [ ] **Step 4: Run the test to prove GREEN**

Run Step 2. Expected: new shell assertions and all existing default-route/unlock-return assertions pass.

- [ ] **Step 5: Commit Task 1**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardApp.java \
  desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardAppTest.java
git commit -m "feat(dashboard): add Figma-inspired shell"
```

## Task 2: Replace Manual Save with Focus Rules Auto-Save

**Files:**
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java`

**Interfaces:**
- Remove `#saveFocusRules`; add `#focusRulesHeader`, `#focusRulesStrictMode`, `#focusRulesCards`, and `#focusSaveStatus`.
- Add the package-visible test seam `FocusRulesView(ServiceClient, Runnable, Duration chromeSyncPollInterval, Duration autoSaveDelay)`; existing constructors retain their current behavior.

- [ ] **Step 1: Write failing auto-save and layout tests**

Use a 10 ms `autoSaveDelay` with the existing controllable `ServiceClient` test double. Add coverage that:

```java
assertNotNull(view.lookup("#focusRulesHeader"));
assertNotNull(view.lookup("#focusRulesStrictMode"));
assertNotNull(view.lookup("#focusRulesCards"));
assertNotNull(view.lookup("#focusSaveStatus"));
assertNull(view.lookup("#saveFocusRules"));
```

- A valid budget change sends one `dashboard.focusSettings.save` request after the delay.
- Changing `4` then `6` within one delay produces one request containing `6`.
- Editing while a first async save is held sends exactly one follow-up request after it completes, containing the latest `6` value.
- Invalid numbers and an enabled rule with no interventions send no request and show the existing validation message.
- A strict-mode weakening rejection preserves the current controls/draft.
- Disposing the view before the delay expires sends no save; a delayed callback cannot overwrite a newer draft.

- [ ] **Step 2: Run the focused tests to prove RED**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test --tests com.localfocuscoach.strict.dashboard.FocusRulesViewTest
```

Expected: FAIL because the view has only `#saveFocusRules`, uses a disabling manual save path, and has no debounce or latest-draft queue.

- [ ] **Step 3: Implement cards and the serial auto-save state machine**

Render the header and site cards in a responsive `FlowPane` (`#focusRulesCards`) with the Figma-inspired spacing and card classes. Preserve all existing per-rule control IDs and ordered intervention controls. Replace the manual button with concise status text in `#focusSaveStatus`.

Add a `PauseTransition autoSaveDebounce`, `boolean renderingSnapshot`, and `boolean saveQueuedAfterInFlight`. All user-edit listeners call:

```java
private void changedDraft() {
    if (renderingSnapshot || disposed) return;
    draftGeneration++;
    autoSaveDebounce.playFromStart();
}
```

When the delay expires, validate the complete draft before sending. If a request is in flight, record a single queued save. Do not disable form controls. When a request settles, render its returned snapshot only if its submitted draft generation still matches; if a queued save exists, immediately validate/send the newest draft. Errors keep the draft and show error text, without retrying on a loop. Snapshot rendering sets `renderingSnapshot` so model updates do not count as edits. `dispose()` stops both the auto-save and existing Chrome-sync transitions and prevents future callbacks.

Use status text: `Saving changes…`, `Saved`, `Synced with Chrome`, `Waiting for Chrome`, and specific validation/error text.

- [ ] **Step 4: Run the focused tests to prove GREEN**

Run Step 2. Expected: all existing polling, stale-response, strict-weakening, and disposal tests plus new auto-save tests pass.

- [ ] **Step 5: Commit Task 2**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java
git commit -m "feat(dashboard): auto-save focus rules"
```

## Task 3: Restyle Strict Mode and the Unlock Challenge

**Files:**
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/StrictModeView.java`
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/UnlockChallengeView.java`
- Modify: `desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/StrictModeViewTest.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/UnlockChallengeViewTest.java`

**Interfaces:**
- Produce `#strictModeHeader`, `.strictModeCard`, `.strictModeWarningCard`, `#unlockHeader`, and `.unlockChallengeCard`.
- Preserve all existing functional IDs, callbacks, error text, and challenge safeguards.

- [ ] **Step 1: Write failing visual-hook and non-regression tests**

Add assertions alongside current behavioral coverage:

```java
assertNotNull(strictModeView.lookup("#strictModeHeader"));
assertEquals(1, strictModeView.lookupAll(".strictModeCard").size());
assertNotNull(unlockChallengeView.lookup("#unlockHeader"));
assertEquals(1, unlockChallengeView.lookupAll(".unlockChallengeCard").size());
unlockChallengeView.onPaste();
assertEquals("", unlockChallengeView.currentCandidate());
```

Keep existing tests for timed/indefinite start, warning countdown/facts, successful unlock, rejected mismatch, paste/drop blocking, and backspace.

- [ ] **Step 2: Run the focused tests to prove RED**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test \
  --tests com.localfocuscoach.strict.dashboard.StrictModeViewTest \
  --tests com.localfocuscoach.strict.dashboard.UnlockChallengeViewTest
```

Expected: FAIL because neither view exposes the new presentation hooks.

- [ ] **Step 3: Implement presentation-only layouts**

Use the shared sidebar content area and card styles. In `StrictModeView`, add a concise header, mode cards, duration/early-exit controls, and an amber warning card only while a warning is active. In `UnlockChallengeView`, add a matching header and a focused challenge card. Do not alter `ServiceClient` calls, state-transition conditions, clipboard access, event consumption, character comparison, or timing calculations.

- [ ] **Step 4: Run focused tests to prove GREEN**

Run Step 2. Expected: visual hooks pass with all existing security and state-machine tests unchanged.

- [ ] **Step 5: Commit Task 3**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/StrictModeView.java \
  desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/UnlockChallengeView.java \
  desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/StrictModeViewTest.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/UnlockChallengeViewTest.java
git commit -m "feat(dashboard): restyle strict mode screens"
```

## Task 4: Perform Full Package Verification

**Files:**
- Review only: all changed dashboard sources and tests.

- [ ] **Step 1: Run the complete desktop verification gate**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew test build jpackage --rerun-tasks
test -d "build/jpackage/Local Focus Coach.app"
```

Expected: all dashboard and dependent desktop tests pass, the app builds, and the macOS app image exists.

- [ ] **Step 2: Inspect final scope and whitespace**

```bash
git diff --check main...HEAD
git status --short
```

Expected: only planned dashboard source, stylesheet, and test files are included; no generated app/build artifact is staged.

- [ ] **Step 3: Commit any verification-only corrective fix, if required**

Only if a test exposes a defect in this implementation, add a regression test first, fix it, rerun Step 1, then commit:

```bash
git commit -m "fix(dashboard): preserve refreshed view behavior"
```
