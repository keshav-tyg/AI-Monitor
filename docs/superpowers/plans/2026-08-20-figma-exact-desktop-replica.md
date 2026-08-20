# Figma-Exact Desktop Replica Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the native macOS JavaFX dashboard as a faithful replica of the approved Figma Focus Rules and Strict Mode screens while preserving Local Focus Coach's real safety behavior.

**Architecture:** Keep JavaFX, the existing `ServiceClient`, and all dashboard request contracts. Add small native presentation controls that preserve existing JavaFX control types and IDs, then compose them in the existing dashboard views. The CSS owns visual tokens and custom skins; `FocusRulesView`, `StrictModeView`, and `UnlockChallengeView` retain their real asynchronous state machines.

**Tech Stack:** Java 21, JavaFX, JUnit 5, Gradle, CSS, existing Unix-socket dashboard service.

**Spec:** `docs/superpowers/specs/2026-08-20-figma-exact-desktop-replica-design.md`

## Global Constraints

- Keep production JavaFX; do not add React, Tailwind, a WebView, or a browser runtime.
- Do not change service, relay, browser extension, persistence, native messaging, or Gemini/model code.
- Reference visual target is 1100 × 760; title bar is 40 px and sidebar is 208 px.
- Use Figma tokens: canvas `#F0EFE9`, sidebar `#E8E7E1`, primary `#2F6B4A`, foreground `#1C1C1E`, muted `#6E6E73`, radius 12–16 px, and restrained card shadows.
- Preserve all accessible control IDs except the already removed `#saveFocusRules`; retain current service payloads and numeric `warningScore` behavior.
- Retain the exact 700 ms auto-save delay, valid-only requests, latest-draft queue, refresh-generation guard, disposal guards, and Chrome sync polling.
- Only a response for the current draft generation may show `Saved` or `Synced with Chrome`; invalid/newer drafts must not show stale success.
- Preserve timed/indefinite semantics, warning facts/countdowns, actual 500-character challenge, backspace, hidden mismatches, paste/drop/context-menu/clipboard protections, and all existing request behavior.
- Use Homebrew Java 21 for all desktop verification and `jpackage`.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `DashboardApp.java` | 1100 × 760 launch target, macOS title bar, Figma sidebar, main content shell. |
| `DashboardControls.java` | Package-private native builders for switch, stepper, segment container, card, site badge, and status indicator. |
| `FocusRulesView.java` | Figma Focus Rules hierarchy bound to existing real settings and auto-save state. |
| `StrictModeView.java` | Figma Strict Mode idle and active-session presentation bound to current service state. |
| `UnlockChallengeView.java` | Figma challenge presentation while retaining secure input handling. |
| `dashboard.css` | Shared Figma tokens, custom control skins, visual state classes, layout breakpoints. |
| `DashboardAppTest.java` | Shell/title bar/sidebar/viewport behavior. |
| `DashboardControlsTest.java` | Native control state, bounds, and keyboard/accessibility-preserving tests. |
| `FocusRulesViewTest.java` | Real settings payload, auto-save/stale-status, and Figma card/grid behavior. |
| `StrictModeViewTest.java` | Strict Mode visual state and unmodified session behavior. |
| `UnlockChallengeViewTest.java` | Challenge visual hooks and retained security safeguards. |
| `DashboardVisualRegressionTest.java` | 1100 × 760 scene bounds, visual-token, and reference-layout assertions. |

## Task 1: Create the Native Figma Shell and Reusable Controls

**Files:**
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardControls.java`
- Create: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardControlsTest.java`
- Create: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardVisualRegressionTest.java`
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardApp.java`
- Modify: `desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardAppTest.java`

**Interfaces:**
- `DashboardControls.switchBox(CheckBox control)` returns a `Region` styled as a Figma green/off switch while retaining the supplied `CheckBox` and its ID in the scene graph.
- `DashboardControls.stepper(String fieldId, TextField field, int minimum, int maximum, String unit)` returns a `HBox` with `fieldId + "Decrease"` and `fieldId + "Increase"` button IDs and updates the existing field only within the supplied range.
- `DashboardControls.segmented(ToggleGroup group, Map<FocusSensitivity, RadioButton> buttons)` returns a three-child `HBox` retaining the supplied radio-button IDs.
- `DashboardControls.card(String id, Node... children)` returns a `VBox` with `figmaCard` style class and the given ID.

- [ ] **Step 1: Write failing shell/control tests**

Add tests that attach a `DashboardView` to an 1100 × 760 scene and assert the Figma shell and control contracts:

```java
assertEquals(1100, scene.getWidth());
assertEquals(760, scene.getHeight());
assertNotNull(dashboard.lookup("#macosTitleBar"));
assertEquals(208.0, ((Region) dashboard.lookup("#dashboardSidebar")).prefWidth(-1), 0.1);
assertNotNull(dashboard.lookup("#dashboardPrivacy"));

var stepper = DashboardControls.stepper("budget", new TextField("10"), 1, 60, "min");
((Button) stepper.lookup("#budgetIncrease")).fire();
assertEquals("11", ((TextField) stepper.lookup("#budget")).getText());
```

Also assert the switch wrapper leaves the original `CheckBox` discoverable and the segment container retains exactly three radio buttons.
Create `DashboardVisualRegressionTest` with a 1100 × 760 `Scene.snapshot` assertion for the title bar, 208 px sidebar, and warm canvas. The test must fail until the Figma shell is present.

- [ ] **Step 2: Run the focused tests to prove RED**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test \
  --tests com.localfocuscoach.strict.dashboard.DashboardAppTest \
  --tests com.localfocuscoach.strict.dashboard.DashboardControlsTest \
  --tests com.localfocuscoach.strict.dashboard.DashboardVisualRegressionTest
```

Expected: FAIL because there is no macOS title-bar node or native control builder.

- [ ] **Step 3: Implement the shell and controls**

Set the dashboard scene to 1100 × 760 while retaining a sensible minimum size. In `DashboardView`, add `#macosTitleBar` with three decorative traffic-light nodes and centered title, then mount a 208 px `#dashboardSidebar` matching the Figma logo/navigation/privacy layout. Make the center viewport and bottom status area independently scroll/pin capable.

Implement `DashboardControls` without new dependencies. Switches must be `CheckBox`-backed; segmented options must be `RadioButton`-backed; steppers must update existing `TextField` values and never emit an out-of-range number. Apply `figma*` style classes only; put exact color, radius, and shadow definitions in `dashboard.css`.

- [ ] **Step 4: Run the focused tests to prove GREEN**

Run Step 2. Expected: the shell renders at the reference geometry, original control IDs remain usable, and steppers enforce their bounds.

- [ ] **Step 5: Commit Task 1**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardControls.java \
  desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardApp.java \
  desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardControlsTest.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardVisualRegressionTest.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardAppTest.java
git commit -m "feat(dashboard): add native Figma shell"
```

## Task 2: Rebuild Focus Rules with Real Native Figma Controls

**Files:**
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java`
- Modify: `desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardVisualRegressionTest.java`

**Interfaces:**
- Produces `#focusRulesHeader`, `#focusRulesStrictMode`, `#focusProtectionCard`, `#focusRulesCards`, and `#focusRulesStatusBar`.
- Every site produces `prefix + "Badge"`, `prefix + "Rule"`, `prefix + "BudgetDecrease"`, `prefix + "BudgetIncrease"`, `prefix + "GracePeriodDecrease"`, `prefix + "GracePeriodIncrease"`, and preserves its existing IDs.
- Uses `DashboardControls.switchBox`, `stepper`, `segmented`, and `card` from Task 1.

- [ ] **Step 1: Write failing Focus Rules replica tests**

Add tests that load real settings through the existing fake `ServiceClient` and assert Figma hierarchy plus real controls:

```java
assertNotNull(view.lookup("#focusProtectionCard"));
assertNotNull(view.lookup("#instagramReelsBadge"));
assertNotNull(view.lookup("#youtubeShortsBadge"));
assertNotNull(view.lookup("#xTimelineBadge"));
assertEquals(2, ((GridPane) view.lookup("#focusRulesCards")).getColumnConstraints().size());

fire(view, "#instagramReelsBudgetIncrease");
waitForFxDelay(Duration.millis(20));
assertEquals("11", ((TextField) view.lookup("#instagramReelsBudget")).getText());
```

Add an asynchronous regression: hold save A, edit to invalid text while A is in flight, complete A, and assert `#focusSaveStatus` is empty while the validation feedback remains visible. Add equivalent assertions for current-draft `Saved`, Chrome `Synced with Chrome`, and `Waiting for Chrome` visual state classes.

Extend `DashboardVisualRegressionTest` with an 1100 × 760 Focus Rules scene snapshot/bounds assertion for the full-width `#focusProtectionCard`, two card columns, and visible pinned `#focusRulesStatusBar`.

- [ ] **Step 2: Run the focused tests to prove RED**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test --tests com.localfocuscoach.strict.dashboard.FocusRulesViewTest
```

Expected: FAIL because the current Focus Rules view uses generic fields, radio controls, and checkbox layout instead of the referenced card hierarchy and stepper IDs.

- [ ] **Step 3: Implement the Focus Rules replica**

Replace each generic rule-card section with the Figma order: badge/name/route/switch header, divider, two-column Session budget and Grace period steppers, segmented sensitivity row and hint, then ordered checkboxes. Keep Instagram, YouTube, X ordering and their actual route labels. Use the existing values/payload conversion; the Figma sample values must never replace service values.

Render the master protection card with active badge and switch, the dark compact Strict Mode CTA, and a pinned `#focusRulesStatusBar`. At usable widths at least 720 px, keep two equal card columns; below it render one column.

Change the successful response path so `setStatus(saveStatus, "Saved", "successState")` runs only when `draftGeneration == submittedDraftGeneration`. For a stale response, retain blank/validation/pending status and only update safe Chrome-sync facts. This resolves the known misleading stale-success edge without changing save payloads, the 700 ms delay, or queue behavior.

- [ ] **Step 4: Run Focus Rules tests to prove GREEN**

Run Step 2. Expected: existing auto-save, validation, strict weakening, legacy score, stale-refresh, threading, disposal, and Chrome-sync tests pass with the new hierarchy tests.

- [ ] **Step 5: Commit Task 2**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java \
  desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardVisualRegressionTest.java
git commit -m "feat(dashboard): replicate Figma focus rules"
```

## Task 3: Rebuild Strict Mode, Active Session, and Unlock Presentation

**Files:**
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/StrictModeView.java`
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/UnlockChallengeView.java`
- Modify: `desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/StrictModeViewTest.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/UnlockChallengeViewTest.java`

**Interfaces:**
- Idle Strict Mode produces `#strictModeHeader`, `#sessionTypeCard`, `#timedSessionOption`, `#indefiniteSessionOption`, `#durationStepper`, `#unlockPreparationCard`, `#strictSafetyCard`, and `#startSession`.
- Active Strict Mode produces `#activeSessionCard`, retains `#activeTitle`, `#sessionCountdown`, `#warningCountdown`, and `#unlockSession`.
- Unlock retains `#unlockHeader`, `#challengeTarget`, `#challengeCandidate`, `#submitChallenge`, `#retryChallenge`, and `#backToDashboard` within `#unlockChallengeCard`.

- [ ] **Step 1: Write failing Strict Mode and unlock replica tests**

Extend existing state-machine tests with Figma layout assertions:

```java
assertNotNull(view.lookup("#sessionTypeCard"));
assertNotNull(view.lookup("#timedSessionOption"));
assertNotNull(view.lookup("#indefiniteSessionOption"));
assertNotNull(view.lookup("#unlockPreparationCard"));
assertNotNull(view.lookup("#strictSafetyCard"));
assertNotNull(unlock.lookup("#challengeTarget"));
assertTrue(unlock.lookup("#challengeTarget").getStyleClass().contains("figmaSequencePanel"));
```

Keep and extend the existing tests to assert: a timed session still sends `mode=TIMED`, ISO `endsAt`, and the actual early-exit choice; an indefinite session always sends `mode=INDEFINITE` plus a challenge; ordinary/restore-warning active states retain their existing IDs; paste, drop, context menu, clipboard shortcuts, backspace, full-length enablement, and hidden mismatch behavior remain unchanged.

- [ ] **Step 2: Run the focused tests to prove RED**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test \
  --tests com.localfocuscoach.strict.dashboard.StrictModeViewTest \
  --tests com.localfocuscoach.strict.dashboard.UnlockChallengeViewTest
```

Expected: FAIL because the Figma session/preparation/safety layout IDs do not exist.

- [ ] **Step 3: Implement the native Strict Mode and unlock replica**

Use Figma cards for timed and indefinite session options, selected green state, hours/minutes stepper presentation, unlock preparation panel, amber safety card, and full-width dark start action. Translate the currently stored minutes into hours/minutes for display and serialize the resulting total back through the existing `startSession` path. Keep the current maximum-duration validation and per-session early-exit policy.

For active sessions, reuse the same card system for truthful session facts and preserve the warning card/countdown semantics. For unlock, style the real 500-character target as a wrapping/scrollable monospace sequence panel; retain secure `ChallengeTextArea` filtering and do not copy Figma's demo-only 24-character sequence or demo end-session action.

- [ ] **Step 4: Run the focused tests to prove GREEN**

Run Step 2. Expected: all current Strict Mode and unlock security/state tests pass with the new replica hierarchy tests.

- [ ] **Step 5: Commit Task 3**

```bash
git add desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/StrictModeView.java \
  desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/UnlockChallengeView.java \
  desktop/strict-dashboard/src/main/resources/com/localfocuscoach/strict/dashboard/dashboard.css \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/StrictModeViewTest.java \
  desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/UnlockChallengeViewTest.java
git commit -m "feat(dashboard): replicate Figma strict mode"
```

## Task 4: Verify the Reference Layout and Package the App

**Files:**
- Review only: dashboard source, stylesheet, and tests changed in Tasks 1–3.

**Interfaces:**
- `DashboardVisualRegressionTest` already renders a dashboard scene at exactly 1100 × 760 without calling the service on the JavaFX thread.
- This task produces no runtime API and adds no image-comparison dependency.

- [ ] **Step 1: Run the reference-layout regression suite**

Run the visual regression and all dashboard behavior tests together:

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew :strict-dashboard:test --rerun-tasks
```

Expected: PASS, covering reference-size shell geometry, Focus Rules native controls/save state, Strict Mode state transitions, and unlock safeguards.

- [ ] **Step 2: Perform a visual reference comparison**

Launch the freshly packaged app at 1100 × 760 and compare Focus Rules and Strict Mode to the two supplied Figma reference images. Confirm the title bar, 208 px sidebar, card grid, site badges, custom switches/steppers/segments, session cards, preparation panel, safety card, and pinned footer are present. If a difference is intentional, document whether it is required by native window behavior or the 500-character security requirement.

- [ ] **Step 3: Run full verification and inspect the packaged app**

```bash
cd desktop
JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" \
  ./gradlew test build jpackage --rerun-tasks
test -d "build/jpackage/Local Focus Coach.app"
git diff --check main...HEAD
```

Expected: all tests pass, a `Local Focus Coach.app` image exists, and there are no whitespace errors.

- [ ] **Step 4: Commit only a verification-discovered corrective fix, if required**

```bash
git commit -m "fix(dashboard): align Figma reference layout"
```

Before this commit, add a focused failing regression that captures the visual or behavioral mismatch, then rerun Steps 1 and 3. Do not commit generated screenshots, build output, or the packaged app image.
