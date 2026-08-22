# Desktop-Owned Focus Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the macOS service and dashboard the sole owner of Focus Rules settings, with Chrome enforcing an authenticated, cached read-only copy.

**Architecture:** Store one validated, revisioned settings document in the existing local SQLite database. The dashboard reads and writes it through authenticated service IPC. The native relay returns the latest revision during its existing five-second heartbeat, so no new unsolicited socket channel is needed; the extension applies only valid newer revisions and retains a last-known-good cache during reconnection.

**Tech Stack:** Java 21, JavaFX, SQLite JDBC, Jackson, Unix-domain sockets, Chrome Manifest V3, TypeScript, Vite, Vitest, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-08-19-desktop-owned-focus-settings-design.md`

## Global Constraints

- Support macOS and Java 21 only; retain the packaged service, relay, and dashboard launchers.
- Keep all data local: do not add remote endpoints, analytics, synced storage, screenshots, browsing history, or page content to the desktop protocol.
- The service is the sole settings writer; extension storage is a validated last-known-good cache only.
- Preserve the existing native-host authentication, exact JSON envelope validation, bounded frames, and two-second service-client timeouts.
- Keep native protocol version `1`; add exact allowlisted message types and payload schemas rather than accepting arbitrary maps.
- Dashboard saves must be atomic: invalid input cannot replace the prior persisted revision.
- While Strict Mode is active, allow only non-weakening changes: protection/site enablement may change false→true, budget/warning/grace may decrease, and the existing intervention sequence must be unchanged.
- A new desktop revision reaches Chrome on the next existing heartbeat (maximum five seconds); do not introduce an unsolicited server-to-relay push channel.
- Do not stage or modify the user-owned `.idea/` directory.

---

## File Structure

| File | Responsibility |
| --- | --- |
| `desktop/strict-core/.../focus/FocusSettings.java` | Immutable desktop representation of the complete settings document and revision. |
| `desktop/strict-core/.../focus/FocusRule.java` | Immutable per-site rule with supported-site and intervention enums. |
| `desktop/strict-core/.../focus/FocusSite.java` | The exact three supported feed identifiers shared by desktop persistence and protocol validation. |
| `desktop/strict-core/.../focus/FocusIntervention.java` | The exact ordered intervention values shared by desktop persistence and protocol validation. |
| `desktop/strict-core/.../focus/FocusSettingsValidator.java` | Exact range/schema validation and Strict Mode non-weakening comparison. |
| `desktop/strict-core/.../focus/FocusSettingsPayload.java` | Explicit Java↔protocol map conversion; rejects unknown/missing fields. |
| `desktop/strict-store/.../FocusSettingsRepository.java` | Persistence boundary for the one settings record. |
| `desktop/strict-store/.../SqliteFocusSettingsRepository.java` | SQLite implementation and schema migration V2. |
| `desktop/strict-store/src/main/resources/db/migration/V2__focus_settings.sql` | Table for revisioned settings and first-import marker. |
| `desktop/strict-service/.../StrictModeService.java` | Authenticated settings reads/saves/sync acknowledgements and Strict Mode guard. |
| `desktop/strict-service/.../DashboardLauncher.java` | Small abstraction for user-requested opening of the packaged dashboard. |
| `desktop/strict-service/.../MacDashboardLauncher.java` | macOS `open` implementation with bounded process handling. |
| `desktop/strict-relay/.../NativeMessagingRelay.java` | Narrow mapping between extension sync/open messages and service requests. |
| `src/shared/desktop-settings.ts` | TypeScript schema validation, cache keys, migration snapshot, and revision handling. |
| `src/background/native-bridge.ts` | Native sync messages, settings snapshot delivery, acknowledgement, and open-dashboard command. |
| `src/background/service-worker.ts` | Reads validated desktop-owned settings rather than extension-owned settings. |
| `src/options/main.ts` | Read-only managed-settings page with the Open Local Focus Coach action. |
| `desktop/strict-dashboard/.../FocusRulesView.java` | JavaFX Focus Rules form, status, validation feedback, and save flow. |
| `desktop/strict-dashboard/.../DashboardApp.java` | Navigation between Focus Rules, Strict Mode, and unlock challenge. |

## Protocol Contract

All service messages keep the existing `{version, secret, type, payload}` envelope. Native messages keep `{version, type, payload}`.

| Origin | Type | Exact payload | Response |
| --- | --- | --- | --- |
| Dashboard | `dashboard.focusSettings.get` | `{}` | `service.focusSettings` |
| Dashboard | `dashboard.focusSettings.save` | `{settings}` | `service.focusSettings` or `error.focusSettingsWeakening` / `error.invalidRequest` |
| Relay | `relay.focusSettings.sync` | `{appliedRevision, legacySettings?}` | `service.focusSettings` |
| Relay | `relay.focusSettings.openDashboard` | `{}` | `service.ack` |
| Service | `service.focusSettings` | `{revision, settings, chromeAppliedRevision}` | forwarded unchanged apart from the native envelope |
| Extension | `extension.focusSettings.sync` | `{appliedRevision, legacySettings?}` | relay maps to `relay.focusSettings.sync` |
| Extension | `extension.openDashboard` | `{}` | relay maps to `relay.focusSettings.openDashboard` |

`legacySettings` is included only when the extension has old `settings` data and no desktop cache. The service imports it only when no database record exists, records the import, and otherwise returns the desktop value. The extension sends its `appliedRevision` on every heartbeat after applying a valid snapshot. The service status reports synced only if that value equals the current database revision.

### Task 1: Define and validate the shared desktop settings model

**Files:**
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/focus/FocusSettings.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/focus/FocusRule.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/focus/FocusSite.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/focus/FocusIntervention.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/focus/FocusSettingsValidator.java`
- Create: `desktop/strict-core/src/main/java/com/localfocuscoach/strict/focus/FocusSettingsPayload.java`
- Create: `desktop/strict-core/src/test/java/com/localfocuscoach/strict/focus/FocusSettingsValidatorTest.java`
- Create: `desktop/strict-core/src/test/java/com/localfocuscoach/strict/focus/FocusSettingsPayloadTest.java`

**Interfaces:**
- Produces `FocusSettings(long revision, boolean enabled, Map<FocusSite, FocusRule> rules)`.
- Produces `FocusRule(boolean enabled, int doomscrollBudgetMinutes, int warningScore, int gracePeriodSeconds, List<FocusIntervention> interventions)`.
- Produces `FocusSettingsValidator.parse(Map<String, Object>): FocusSettings` and `isWeakening(FocusSettings current, FocusSettings candidate): boolean`.
- Produces `FocusSettingsPayload.toPayload(FocusSettings): Map<String, Object>` and `fromPayload(Map<String, Object>): FocusSettings`.
- Consumed by storage, service, relay tests, and dashboard tasks.

- [ ] **Step 1: Write failing core validation tests**

```java
@Test
void acceptsExactlyThreeSupportedRulesAndConfiguredRanges() {
    var parsed = validator.parse(Map.of("enabled", true, "rules", validRules()));
    assertEquals(0L, parsed.revision());
    assertEquals(5, parsed.rules().get(FocusSite.INSTAGRAM_REELS).doomscrollBudgetMinutes());
}

@Test
void rejectsUnknownSitesAndOutOfRangeNumbers() {
    assertThrows(IllegalArgumentException.class,
        () -> validator.parse(Map.of("enabled", true, "rules", Map.of("tiktok", Map.of()))));
    assertThrows(IllegalArgumentException.class,
        () -> validator.parse(settingsWith("warningScore", 51)));
}

@Test
void identifiesOnlyDefinedWeakeningChangesDuringStrictMode() {
    assertTrue(validator.isWeakening(enabledRule(5, 10, 60), enabledRule(6, 10, 60)));
    assertTrue(validator.isWeakening(enabledRule(5, 10, 60), enabledRule(5, 11, 60)));
    assertFalse(validator.isWeakening(enabledRule(5, 10, 60), enabledRule(4, 9, 30)));
}
```

- [ ] **Step 2: Run the core tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-core:test --tests '*FocusSettings*'`

Expected: FAIL because the focus settings model and validator do not exist.

- [ ] **Step 3: Implement immutable records and exact validation**

```java
public record FocusSettings(long revision, boolean enabled, Map<FocusSite, FocusRule> rules) {
    public FocusSettings {
        if (revision < 0 || !rules.keySet().equals(EnumSet.allOf(FocusSite.class))) {
            throw new IllegalArgumentException("Focus settings are incomplete");
        }
        rules = Map.copyOf(rules);
    }
    public FocusSettings withRevision(long value) { return new FocusSettings(value, enabled, rules); }
}
```

Implement exact object keys and limits from the existing Options page: budget `1..60`, warning score `1..50`, grace `0..600`; use only the three existing sites and `notify`, `pause`, `close-tab`, `block` with no duplicates. Implement the conservative Strict Mode comparison described in Global Constraints; do not infer a strength ordering from a reordered intervention list.

- [ ] **Step 4: Add payload round-trip and unknown-field tests**

```java
@Test
void payloadRoundTripPreservesRevisionAndRuleOrder() {
    var original = settings(7L, List.of(NOTIFY, PAUSE, CLOSE_TAB, BLOCK));
    assertEquals(original, FocusSettingsPayload.fromPayload(FocusSettingsPayload.toPayload(original)));
}
```

- [ ] **Step 5: Run the core test module**

Run: `cd desktop && ./gradlew :strict-core:test --rerun-tasks`

Expected: PASS.

- [ ] **Step 6: Commit the model**

```bash
git add desktop/strict-core
git commit -m "feat(settings): define focus settings model"
```

### Task 2: Persist the revisioned document in the local database

**Files:**
- Create: `desktop/strict-store/src/main/java/com/localfocuscoach/strict/store/FocusSettingsRepository.java`
- Create: `desktop/strict-store/src/main/java/com/localfocuscoach/strict/store/SqliteFocusSettingsRepository.java`
- Create: `desktop/strict-store/src/main/resources/db/migration/V2__focus_settings.sql`
- Create: `desktop/strict-store/src/test/java/com/localfocuscoach/strict/store/SqliteFocusSettingsRepositoryTest.java`
- Modify: `desktop/strict-store/src/main/java/com/localfocuscoach/strict/store/SqliteStrictSessionRepository.java`

**Interfaces:**
- Consumes `FocusSettings` and `FocusSettingsValidator` from Task 1.
- Produces `Optional<FocusSettings> load()`, `FocusSettings save(FocusSettings candidate)`, and `FocusSettings importIfAbsent(FocusSettings legacy)`.
- Uses the existing `schema_migration` table with version `2`, without changing migration V1.
- Consumed by Task 3 and bootstrap wiring.

- [ ] **Step 1: Write failing SQLite repository tests**

```java
@Test
void saveAssignsAndPersistsMonotonicRevisions() {
    assertEquals(1L, repository.save(defaultSettings()).revision());
    assertEquals(2L, repository.save(changedSettings()).revision());
    assertEquals(2L, repository.load().orElseThrow().revision());
}

@Test
void firstImportNeverOverwritesDesktopOwnedSettings() {
    assertEquals(1L, repository.importIfAbsent(legacy()).revision());
    assertEquals(1L, repository.importIfAbsent(otherLegacy()).revision());
}
```

- [ ] **Step 2: Run the repository tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-store:test --tests '*FocusSettingsRepositoryTest'`

Expected: FAIL because the repository and migration V2 do not exist.

- [ ] **Step 3: Implement schema V2 and the focused repository**

```sql
CREATE TABLE focus_settings (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  revision INTEGER NOT NULL CHECK (revision >= 1),
  settings_json TEXT NOT NULL,
  imported_from_extension INTEGER NOT NULL DEFAULT 0,
  updated_at TEXT NOT NULL
);
```

Use a transaction for read-current/revision-increment/upsert. Keep JSON serialization inside `SqliteFocusSettingsRepository` through `FocusSettingsPayload`, never by concatenating field values. Factor only the migration-history helpers needed by both SQLite repositories; retain V1 behavior and tests unchanged.

- [ ] **Step 4: Add atomic-failure and migration compatibility tests**

```java
@Test
void invalidCandidateLeavesTheLastSavedDocumentUntouched() {
    repository.save(defaultSettings());
    assertThrows(IllegalArgumentException.class, () -> repository.save(invalidSettings()));
    assertEquals(defaultSettings().withRevision(1), repository.load().orElseThrow());
}
```

- [ ] **Step 5: Run store and existing session tests**

Run: `cd desktop && ./gradlew :strict-store:test --rerun-tasks`

Expected: PASS, including the existing strict-session migration tests.

- [ ] **Step 6: Commit persistence**

```bash
git add desktop/strict-store
git commit -m "feat(settings): persist revisioned focus rules"
```

### Task 3: Add authenticated service operations and dashboard launching

**Files:**
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/DashboardLauncher.java`
- Create: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/MacDashboardLauncher.java`
- Modify: `desktop/strict-service/src/main/java/com/localfocuscoach/strict/service/StrictModeService.java`
- Modify: `desktop/strict-service/src/test/java/com/localfocuscoach/strict/service/StrictModeServiceTest.java`
- Modify: `desktop/strict-service/src/test/java/com/localfocuscoach/strict/service/UnixSocketServerTest.java`
- Modify: `desktop/build.gradle.kts`

**Interfaces:**
- Consumes `FocusSettingsRepository`, validator/model types, and `DashboardLauncher`.
- Extends `StrictModeService.handle` with the five protocol types in the Protocol Contract.
- Produces `service.focusSettings` payload `{revision, settings, chromeAppliedRevision}`.
- The generated `ServiceMain` constructs both SQLite repositories against `strict-mode.sqlite3` and injects `new MacDashboardLauncher()`.
- Consumed by relay and dashboard tasks.

- [ ] **Step 1: Write failing authenticated service tests**

```java
@Test
void dashboardSavePersistsSettingsAndReturnsTheNewRevision() {
    var response = service.handle(message("dashboard.focusSettings.save", Map.of("settings", validPayload())), NOW);
    assertEquals("service.focusSettings", response.type());
    assertEquals(1L, response.payload().get("revision"));
}

@Test
void activeStrictModeRejectsAWeakerBudgetButAcceptsASmallerBudget() {
    startIndefiniteSession();
    assertEquals("error.focusSettingsWeakening", saveSettings(budget(6)).type());
    assertEquals("service.focusSettings", saveSettings(budget(4)).type());
}

@Test
void syncImportsLegacyOnlyOnceAndTracksAppliedRevision() {
    assertEquals(1L, sync(0L, legacyPayload()).payload().get("revision"));
    assertEquals(1L, sync(1L, otherLegacyPayload()).payload().get("revision"));
}
```

- [ ] **Step 2: Run the focused service tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-service:test --tests '*StrictModeServiceTest'`

Expected: FAIL because the types are not allowlisted and no settings repository is injected.

- [ ] **Step 3: Implement service routing, state, and guarded saves**

```java
case "dashboard.focusSettings.get" -> focusSettings(payload);
case "dashboard.focusSettings.save" -> saveFocusSettings(payload, now);
case "relay.focusSettings.sync" -> syncFocusSettings(payload, now);
case "relay.focusSettings.openDashboard" -> openDashboard(payload);
```

Require exact payload keys. `dashboard.focusSettings.save` parses the candidate before any write. If `repository.loadActive()` contains an active strict session and `isWeakening(current, candidate)` is true, return `error.focusSettingsWeakening` with no write. Track the highest `appliedRevision` reported by the currently connected relay, reset it on `relay.disconnected`, and include it in every `service.focusSettings` response.

`DashboardLauncher.open()` must be invoked only for the authenticated, exact empty `relay.focusSettings.openDashboard` request. Implement macOS opening through a fixed app launcher command with bounded wait/cleanup, following the existing `MacChromeController` process-safety pattern; inject a fake in unit tests.

- [ ] **Step 4: Add malformed socket and launcher safety tests**

```java
@Test
void unauthenticatedOrMalformedOpenDashboardNeverInvokesLauncher() {
    service.handle(new ProtocolMessage(1, "wrong", "relay.focusSettings.openDashboard", Map.of()), NOW);
    assertEquals(0, launcher.openCalls());
}
```

- [ ] **Step 5: Wire application bootstrap and run service tests**

Run: `cd desktop && ./gradlew :strict-service:test --rerun-tasks`

Expected: PASS.

- [ ] **Step 6: Commit service protocol**

```bash
git add desktop/strict-service desktop/build.gradle.kts
git commit -m "feat(settings): add service configuration IPC"
```

### Task 4: Extend the native relay for settings synchronization

**Files:**
- Modify: `desktop/strict-relay/src/main/java/com/localfocuscoach/strict/relay/NativeMessagingRelay.java`
- Modify: `desktop/strict-relay/src/test/java/com/localfocuscoach/strict/relay/NativeMessagingRelayTest.java`

**Interfaces:**
- Consumes Task 3 service request/response types.
- Accepts only `extension.hello`, `extension.heartbeat`, `extension.focusSettings.sync`, and `extension.openDashboard` native message types.
- Produces native `service.focusSettings`, `service.ack`, and existing relay error responses; never emits the install secret.
- Consumed by Task 5.

- [ ] **Step 1: Write failing relay contract tests**

```java
@Test
void syncForwardsOnlyRevisionAndOptionalLegacySettings() {
    relay.run(origin, nativeInput(sync(4L)), output, diagnostics);
    assertEquals("relay.focusSettings.sync", serviceRequests.get(1).path("type").textValue());
    assertEquals(4L, serviceRequests.get(1).path("payload").path("appliedRevision").longValue());
}

@Test
void rejectsExtraFieldsAndNeverForwardsPageContent() {
    relay.run(origin, nativeInput(raw("extension.focusSettings.sync", "{\\\"text\\\":\\\"page\\\"}")), output, diagnostics);
    assertThat(serviceRequests).hasSize(1); // only relay.connected
}
```

- [ ] **Step 2: Run the relay tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-relay:test --tests '*NativeMessagingRelayTest'`

Expected: FAIL because the relay accepts only empty hello/heartbeat payloads.

- [ ] **Step 3: Implement exact native schemas and forwarding**

Keep `extension.hello` and heartbeat health handling. For `extension.focusSettings.sync`, require exactly `appliedRevision` (non-negative integer) and optional `legacySettings` (object validated structurally by the service). Map it to `relay.focusSettings.sync`; for `extension.openDashboard`, require `{}` and map it to `relay.focusSettings.openDashboard`. Do not create a persistent server-side subscriber: each extension heartbeat gets one authenticated service response, which carries the current revision.

- [ ] **Step 4: Add response allowlist and secret-stripping tests**

```java
@Test
void forwardsSettingsSnapshotWithoutServiceSecret() {
    var response = nativeOutput.readMessage();
    assertEquals("service.focusSettings", response.path("type").textValue());
    assertFalse(response.has("secret"));
}
```

- [ ] **Step 5: Run relay and service integration tests**

Run: `cd desktop && ./gradlew :strict-relay:test :strict-service:test --rerun-tasks`

Expected: PASS.

- [ ] **Step 6: Commit relay changes**

```bash
git add desktop/strict-relay
git commit -m "feat(settings): sync configuration through relay"
```

### Task 5: Make the extension consume desktop-owned settings

**Files:**
- Create: `src/shared/desktop-settings.ts`
- Modify: `src/shared/storage.ts`
- Modify: `src/shared/types.ts`
- Modify: `src/background/native-bridge.ts`
- Modify: `src/background/service-worker.ts`
- Modify: `tests/chrome-storage.ts`
- Modify: `tests/native-bridge.test.ts`
- Modify: `tests/storage.test.ts`
- Modify: `tests/background.test.ts`
- Create: `tests/desktop-settings.test.ts`

**Interfaces:**
- Produces `DesktopSettingsSnapshot { revision: number; settings: Settings }` after strict TypeScript validation.
- Produces `loadEnforcementSettings(): Promise<Settings>` and `legacySettingsForImport(): Promise<Settings | undefined>`.
- `startNativeBridge(onSettingsSnapshot)` calls the callback only with a valid newer `service.focusSettings` message.
- Produces `requestOpenDashboard(): void`, which posts only `{ version: 1, type: 'extension.openDashboard', payload: {} }` to a connected native port.
- The service worker reads `loadEnforcementSettings`, never `getSettings` as an authoritative source.

- [ ] **Step 1: Write failing extension schema/cache tests**

```ts
it('keeps the last valid cached desktop snapshot when a newer payload is invalid', async () => {
  await saveDesktopSettingsSnapshot({ revision: 4, settings: validSettings });
  expect(await acceptDesktopSnapshot({ revision: 5, settings: { rules: {} } })).toBe(false);
  expect((await loadDesktopSettingsSnapshot())?.revision).toBe(4);
});

it('returns old extension settings for a one-time import but never treats them as authoritative', async () => {
  await chrome.storage.local.set({ settings: legacySettings });
  expect(await legacySettingsForImport()).toEqual(legacySettings);
});
```

- [ ] **Step 2: Run the focused Vitest files to verify they fail**

Run: `npm test -- --run tests/desktop-settings.test.ts tests/native-bridge.test.ts`

Expected: FAIL because desktop settings cache and native snapshots do not exist.

- [ ] **Step 3: Implement cache, migration snapshot, and bridge delivery**

```ts
export interface DesktopSettingsSnapshot { revision: number; settings: Settings }

export async function acceptDesktopSnapshot(value: unknown): Promise<boolean> {
  const snapshot = parseDesktopSettingsSnapshot(value);
  const cached = await loadDesktopSettingsSnapshot();
  if (!snapshot || (cached && snapshot.revision <= cached.revision)) return false;
  await saveDesktopSettingsSnapshot(snapshot);
  return true;
}
```

Extend bridge messages so startup and each five-second heartbeat send `extension.focusSettings.sync` with the applied revision and optional first-run legacy settings. On a valid `service.focusSettings`, cache it, send the next acknowledgement with that revision, and notify the service worker. Keep the current reconnect behavior and never include event, activity, classifier, or page content in a native message.

- [ ] **Step 4: Switch enforcement reads and remove browser settings writes**

Replace service-worker `getSettings()` reads with the desktop cache accessor. Retain old `settings` only for the one-time import path. Remove the `save-settings` background request and response variant, and update popup status to display the cached configuration without exposing editable control paths.

- [ ] **Step 5: Add reconnect, stale revision, and enforcement-fallback tests**

```ts
it('continues to enforce cached settings while the native bridge is disconnected', async () => {
  await saveDesktopSettingsSnapshot({ revision: 2, settings: enabledInstagramRule });
  resetNativeBridgeForTest();
  await expect(handleEvent(7, passiveScroll)).resolves.toMatchObject({ kind: 'notify' });
});
```

- [ ] **Step 6: Run all extension tests, typecheck, and build**

Run: `npm run typecheck && npm test && npm run build`

Expected: PASS.

- [ ] **Step 7: Commit extension ownership changes**

```bash
git add src tests manifest.config.ts
git commit -m "feat(settings): enforce desktop-owned focus rules"
```

### Task 6: Build the dashboard Focus Rules screen

**Files:**
- Create: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/FocusRulesView.java`
- Create: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/FocusRulesViewTest.java`
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/ServiceClient.java`
- Modify: `desktop/strict-dashboard/src/main/java/com/localfocuscoach/strict/dashboard/DashboardApp.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/ServiceClientTest.java`
- Modify: `desktop/strict-dashboard/src/test/java/com/localfocuscoach/strict/dashboard/DashboardAppTest.java`

**Interfaces:**
- Consumes `service.focusSettings` from Task 3 through `ServiceClient.requestAsync`.
- Produces a JavaFX `FocusRulesView(ServiceClient, Runnable showStrictMode)` with `dispose()`.
- `ServiceClient` exposes `getFocusSettingsAsync` and `saveFocusSettingsAsync` wrappers using the exact IPC names from Task 3.
- `DashboardApp` presents navigation to Focus Rules and Strict Mode without breaking the existing unlock view.

- [ ] **Step 1: Write failing JavaFX behavior tests**

```java
@Test
void rendersMasterToggleAndAllThreeSiteCardsFromServiceSettings() {
    var view = FxTestSupport.call(() -> new FocusRulesView(client, showStrictMode));
    assertNotNull(view.lookup("#focusProtectionEnabled"));
    assertEquals(3, view.lookupAll(".focusSiteRule").size());
}

@Test
void invalidBudgetDoesNotSendSaveAndReportsTheExactRange() {
    setText(view, "#instagramReelsBudget", "61");
    fire(view, "#saveFocusRules");
    assertEquals(0, client.requests().size());
    assertEquals("Doomscroll session budget must be 1 to 60 minutes", text(view, "#focusSettingsFeedback"));
}
```

- [ ] **Step 2: Run the focused JavaFX tests to verify they fail**

Run: `cd desktop && ./gradlew :strict-dashboard:test --tests '*FocusRulesViewTest'`

Expected: FAIL because the Focus Rules view and client methods do not exist.

- [ ] **Step 3: Implement the non-blocking Focus Rules UI**

Use the existing `StrictModeView` lifecycle pattern: all service work uses `requestAsync`, callbacks are generation-guarded, and `dispose()` prevents a detached view from updating JavaFX controls. Render master toggle, cards for the three exact supported sites, integer fields with the existing ranges, fixed “Block until tomorrow” text, ordered intervention checkboxes, a single Save button, feedback label, and Chrome sync status.

```java
client.requestAsync("dashboard.focusSettings.save", Map.of("settings", payload),
    (response, failure) -> {
        if (failure != null || !"service.focusSettings".equals(response.type())) {
            feedback.setText("Could not save Focus Rules");
            return;
        }
        renderSnapshot(response.payload());
    });
```

Interpret `chromeAppliedRevision == revision` as “Synced with Chrome”; otherwise display “Waiting for Chrome.” Display `error.focusSettingsWeakening` as “Strict Mode is active, so settings cannot be made less protective.”

- [ ] **Step 4: Add save, sync-status, error, and disposal tests**

```java
@Test
void rendersWaitingUntilChromeAcknowledgesTheCurrentRevision() {
    client.reply(focusSettingsResponse(3L, 2L));
    view.refresh();
    assertEquals("Waiting for Chrome", text(view, "#chromeSyncStatus"));
}
```

- [ ] **Step 5: Run dashboard tests and application build**

Run: `cd desktop && ./gradlew :strict-dashboard:test :strict-dashboard:build --rerun-tasks`

Expected: PASS.

- [ ] **Step 6: Commit dashboard Focus Rules**

```bash
git add desktop/strict-dashboard
git commit -m "feat(dashboard): add focus rules controls"
```

### Task 7: Replace Chrome Options with a managed read-only page

**Files:**
- Modify: `src/options/main.ts`
- Modify: `src/options/style.css`
- Modify: `src/options/index.html`
- Modify: `tests/options.test.ts`
- Modify: `src/popup/main.ts`
- Modify: `tests/popup.test.ts`

**Interfaces:**
- Consumes a bridge-level `requestOpenDashboard(): void` helper from Task 5.
- Produces no editable `<input>`, `<select>`, or settings-save request from the Options page.
- Keeps the privacy promise and presents a user-initiated Open Local Focus Coach button.

- [ ] **Step 1: Replace Options expectations with failing read-only tests**

```ts
it('has no editable Focus Rule controls and asks the desktop app to open', async () => {
  await renderOptions(mount);
  expect(document.querySelectorAll('input, select')).toHaveLength(0);
  document.querySelector<HTMLButtonElement>('[data-open-desktop]')!.click();
  expect(nativePort.postMessage).toHaveBeenCalledWith({
    version: 1, type: 'extension.openDashboard', payload: {},
  });
});
```

- [ ] **Step 2: Run Options tests to verify they fail**

Run: `npm test -- --run tests/options.test.ts tests/popup.test.ts`

Expected: FAIL because the Options page still renders and saves form controls.

- [ ] **Step 3: Implement the managed page and revised popup copy**

Render a heading, local-only privacy copy, “Focus Rules are managed in Local Focus Coach on this Mac,” an Open button, and an unavailable-state explanation if no native port is connected. Remove activity/intervention history and all editable inputs from Options; retain activity data in local extension storage only if popup behavior still uses it. Change popup fallback wording from “Open Options to review your rules” to “Open Local Focus Coach to review your rules.”

- [ ] **Step 4: Run extension regression checks**

Run: `npm run typecheck && npm test -- --run tests/options.test.ts tests/popup.test.ts tests/privacy-boundary.test.ts && npm run build`

Expected: PASS.

- [ ] **Step 5: Commit the browser UI boundary**

```bash
git add src/options src/popup tests/options.test.ts tests/popup.test.ts
git commit -m "feat(options): move focus controls to desktop"
```

### Task 8: Verify migration, packaging, and the full product path

**Files:**
- Modify: `README.md`
- Modify: `desktop/README.md`
- Modify: `docs/manual-test-checklist.md`
- Modify: `tests/build-output.test.ts`
- Modify: `desktop/installer/test-installer.sh` only if packaged launch/open behavior requires an assertion

**Interfaces:**
- Consumes all prior tasks.
- Documents desktop-only settings ownership, first-run import, reconnect timing, and the correct `dist/` load path.

- [ ] **Step 1: Add failing build/documentation assertions where automated**

```ts
it('ships a read-only options bundle without the old Save settings label', () => {
  expect(readFileSync('dist/src/options/index.html', 'utf8')).not.toContain('Save settings');
});
```

Add manual checklist rows for first-run migration, dashboard save → sync within five seconds, disconnect cache enforcement, Strict Mode weakening rejection, Open Local Focus Coach, and corrected unpacked extension loading from `dist/`.

- [ ] **Step 2: Run focused build and installer verification**

Run: `npm run typecheck && npm test && npm run build && cd desktop && ./installer/test-installer.sh`

Expected: PASS.

- [ ] **Step 3: Run full desktop verification and package the app**

Run: `cd desktop && JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" ./gradlew test build jpackage --rerun-tasks`

Expected: BUILD SUCCESSFUL; dashboard, service, and relay launchers are present in `build/jpackage/Local Focus Coach.app`.

- [ ] **Step 4: Complete the manual macOS smoke test**

1. Install registrations using the current development extension ID and the packaged app image.
2. Load unpacked from `<repo>/dist`, not the repository root.
3. Open the desktop app; configure a site and save.
4. Confirm Chrome reports the matching revision within five seconds and enforces it.
5. Disconnect the native host; confirm cached rules still enforce and dashboard reports Waiting for Chrome.
6. Start Strict Mode; confirm a larger budget, higher warning score, longer grace period, disable operation, or changed intervention order is rejected, while a shorter budget is accepted.
7. Open the Chrome Options page; confirm it has no editable settings and opens the dashboard.

- [ ] **Step 5: Inspect the final diff and commit docs/tests**

```bash
git diff --check
git status --short
git add README.md desktop/README.md docs/manual-test-checklist.md tests/build-output.test.ts desktop/installer/test-installer.sh
git commit -m "docs(settings): document desktop configuration"
```

## Plan Self-Review

**Spec coverage:** Tasks 1–2 establish validated local authority and first-import persistence; Task 3 covers authenticated saves, active Strict Mode restrictions, dashboard launch, and connection acknowledgements; Task 4 preserves the hardened relay boundary; Task 5 handles cached browser enforcement and migration; Task 6 provides the desktop UI; Task 7 removes browser editing; Task 8 verifies packaging, privacy, migration, and the complete macOS flow.

**Placeholder scan:** No planning placeholders, generic validation instructions, or undefined hand-offs remain. All protocol messages, validation limits, persistence interfaces, UI state labels, and verification commands are named above.

**Type consistency:** `FocusSettings` / `FocusRule` are introduced in Task 1; storage API in Task 2; service message names in Task 3; relay mappings in Task 4; TypeScript snapshot forms in Task 5; and dashboard client use in Task 6. The five-second heartbeat sync is used consistently throughout.
