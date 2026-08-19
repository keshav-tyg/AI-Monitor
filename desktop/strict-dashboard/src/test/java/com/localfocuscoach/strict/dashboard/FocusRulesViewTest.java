package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.localfocuscoach.strict.protocol.ProtocolMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.util.Duration;
import org.junit.jupiter.api.Test;

class FocusRulesViewTest {
    private static final String SECRET = "dashboard-test-secret";

    @Test
    void rendersMasterToggleAndAllThreeSiteCardsFromServiceSettings() {
        var requests = new CopyOnWriteArrayList<ProtocolMessage>();
        var client = client(requests);
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);

            FxTestSupport.call(() -> {
                assertNotNull(view.lookup("#focusProtectionEnabled"));
                assertEquals(3, view.lookupAll(".focusSiteRule").size());
                assertEquals("5", ((TextField) view.lookup("#instagramReelsBudget")).getText());
                assertEquals("10", ((TextField) view.lookup("#xTimelineWarningScore")).getText());
                assertEquals("60", ((TextField) view.lookup("#youtubeShortsGracePeriod")).getText());
                assertEquals(
                        "Block until tomorrow",
                        ((Label) view.lookup("#instagramReelsBlockDuration")).getText());
                assertEquals(
                        true,
                        ((CheckBox) view.lookup("#youtubeShortsCloseTab")).isSelected());
                return null;
            });
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void invalidBudgetDoesNotSendSaveAndReportsTheExactRange() {
        var requests = new CopyOnWriteArrayList<ProtocolMessage>();
        var client = client(requests);
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            requests.clear();

            FxTestSupport.call(() -> {
                ((TextField) view.lookup("#instagramReelsBudget")).setText("61");
                ((Button) view.lookup("#saveFocusRules")).fire();
                return null;
            });

            assertEquals(0, requests.size());
            assertEquals(
                    "Doomscroll session budget must be 1 to 60 minutes",
                    text(view, "#focusSettingsFeedback"));
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void validatesEveryNumericRangeAndEnabledRuleBeforeSendingSave() {
        var requests = new CopyOnWriteArrayList<ProtocolMessage>();
        var client = client(requests);
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            requests.clear();

            FxTestSupport.call(() -> {
                setText(view, "#instagramReelsWarningScore", "0");
                fire(view, "#saveFocusRules");
                assertEquals(
                        "Warning score must be 1 to 50",
                        textOnFxThread(view, "#focusSettingsFeedback"));

                setText(view, "#instagramReelsWarningScore", "10");
                setText(view, "#xTimelineGracePeriod", "601");
                fire(view, "#saveFocusRules");
                assertEquals(
                        "Grace period must be 0 to 600 seconds",
                        textOnFxThread(view, "#focusSettingsFeedback"));

                setText(view, "#xTimelineGracePeriod", "60");
                for (var suffix : List.of("Notify", "Pause", "CloseTab", "Block")) {
                    ((CheckBox) view.lookup("#youtubeShorts" + suffix)).setSelected(false);
                }
                fire(view, "#saveFocusRules");
                assertEquals(
                        "An enabled rule needs at least one intervention",
                        textOnFxThread(view, "#focusSettingsFeedback"));
                return null;
            });

            assertTrue(requests.isEmpty());
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void validSaveSendsTheCompleteSettingsDocumentWithOrderedInterventions() {
        var requests = new CopyOnWriteArrayList<ProtocolMessage>();
        var client = new ServiceClient(SECRET, request -> {
            requests.add(request);
            if ("dashboard.focusSettings.save".equals(request.type())) {
                @SuppressWarnings("unchecked")
                var settings = (Map<String, Object>) request.payload().get("settings");
                return focusSettingsResponse(4L, 3L, settings);
            }
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            requests.clear();

            FxTestSupport.call(() -> {
                ((CheckBox) view.lookup("#focusProtectionEnabled")).setSelected(false);
                ((CheckBox) view.lookup("#instagramReelsEnabled")).setSelected(true);
                setText(view, "#instagramReelsBudget", "4");
                setText(view, "#instagramReelsWarningScore", "9");
                setText(view, "#instagramReelsGracePeriod", "30");
                ((CheckBox) view.lookup("#instagramReelsPause")).setSelected(false);
                fire(view, "#saveFocusRules");
                return null;
            });
            FxTestSupport.waitFor(
                    () -> "Focus Rules saved".equals(text(view, "#focusSettingsFeedback")),
                    "successful Focus Rules save");

            assertEquals(1, requests.size());
            var save = requests.getFirst();
            assertEquals("dashboard.focusSettings.save", save.type());
            @SuppressWarnings("unchecked")
            var settings = (Map<String, Object>) save.payload().get("settings");
            assertEquals(false, settings.get("enabled"));
            @SuppressWarnings("unchecked")
            var rules = (Map<String, Object>) settings.get("rules");
            assertEquals(
                    List.of("instagram-reels", "x-timeline", "youtube-shorts"),
                    new ArrayList<>(rules.keySet()));
            @SuppressWarnings("unchecked")
            var instagram = (Map<String, Object>) rules.get("instagram-reels");
            assertEquals(4, instagram.get("doomscrollBudgetMinutes"));
            assertEquals(9, instagram.get("warningScore"));
            assertEquals(30, instagram.get("gracePeriodSeconds"));
            assertEquals(
                    List.of("notify", "close-tab", "block"),
                    instagram.get("interventions"));
            assertEquals("Waiting for Chrome", text(view, "#chromeSyncStatus"));
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void pendingSaveIsolatesTheSubmittedDraftUntilCompletion() throws Exception {
        var saveRequests = new AtomicInteger();
        var saveStarted = new CountDownLatch(1);
        var releaseSave = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            if ("dashboard.focusSettings.save".equals(request.type())) {
                saveRequests.incrementAndGet();
                saveStarted.countDown();
                await(releaseSave);
                @SuppressWarnings("unchecked")
                var settings = (Map<String, Object>) request.payload().get("settings");
                return focusSettingsResponse(4L, 4L, settings);
            }
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            FxTestSupport.call(() -> {
                setText(view, "#instagramReelsBudget", "4");
                fire(view, "#saveFocusRules");
                return null;
            });
            assertTrue(saveStarted.await(1, TimeUnit.SECONDS));

            FxTestSupport.call(() -> {
                var budget = (TextField) view.lookup("#instagramReelsBudget");
                var protection = (CheckBox) view.lookup("#focusProtectionEnabled");
                var save = (Button) view.lookup("#saveFocusRules");
                assertTrue(budget.isDisabled());
                assertTrue(protection.isDisabled());
                assertTrue(save.isDisabled());

                // A property mutation cannot stand in for a user edit on a disabled control,
                // but it proves the response also honors the draft-generation guard.
                budget.setText("9");
                save.fire();
                return null;
            });
            releaseSave.countDown();
            FxTestSupport.waitFor(
                    () -> "Focus Rules saved".equals(text(view, "#focusSettingsFeedback")),
                    "isolated Focus Rules save");

            FxTestSupport.call(() -> {
                assertEquals(
                        "9", ((TextField) view.lookup("#instagramReelsBudget")).getText());
                assertFalse(((TextField) view.lookup("#instagramReelsBudget")).isDisabled());
                assertFalse(((Button) view.lookup("#saveFocusRules")).isDisabled());
                assertEquals("Synced with Chrome", textOnFxThread(view, "#chromeSyncStatus"));
                return null;
            });
            assertEquals(1, saveRequests.get());
        } finally {
            releaseSave.countDown();
            dispose(view, client);
        }
    }

    @Test
    void pollsWaitingRevisionUntilChromeAcknowledgesIt() throws Exception {
        var requestNumber = new AtomicInteger();
        var pollStarted = new CountDownLatch(1);
        var releasePoll = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            if (requestNumber.incrementAndGet() == 1) {
                return focusSettingsResponse(3L, 2L);
            }
            pollStarted.countDown();
            await(releasePoll);
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(
                () -> new FocusRulesView(client, () -> {}, Duration.millis(10)));
        try {
            FxTestSupport.waitFor(
                    () -> "Waiting for Chrome".equals(text(view, "#chromeSyncStatus")),
                    "pending Chrome sync");
            assertTrue(pollStarted.await(1, TimeUnit.SECONDS));
            assertEquals(2, requestNumber.get());
            releasePoll.countDown();
            FxTestSupport.waitFor(
                    () -> "Synced with Chrome".equals(text(view, "#chromeSyncStatus")),
                    "acknowledged Chrome sync");
            assertEquals(2, requestNumber.get());
        } finally {
            releasePoll.countDown();
            dispose(view, client);
        }
    }

    @Test
    void unsavedEditableDefaultsRemainWaitingForARealChromeRevision() {
        var client = new ServiceClient(
                SECRET, request -> focusSettingsResponse(0L, 0L, settingsPayload(5)));
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            assertEquals("Waiting for Chrome", text(view, "#chromeSyncStatus"));
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void weakeningErrorLeavesDraftVisibleAndReportsStrictModeRestriction() {
        var client = new ServiceClient(SECRET, request -> {
            if ("dashboard.focusSettings.save".equals(request.type())) {
                return new ProtocolMessage(
                        1, SECRET, "error.focusSettingsWeakening", Map.of());
            }
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            FxTestSupport.call(() -> {
                setText(view, "#instagramReelsBudget", "6");
                fire(view, "#saveFocusRules");
                return null;
            });
            FxTestSupport.waitFor(
                    () -> "Strict Mode is active, so settings cannot be made less protective."
                            .equals(text(view, "#focusSettingsFeedback")),
                    "Strict Mode save restriction");

            assertEquals(
                    "6",
                    FxTestSupport.call(() -> ((TextField) view.lookup("#instagramReelsBudget"))
                            .getText()));
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void ordinarySaveFailureReportsTheExactFailureWithoutChangingTheDraft() {
        var client = new ServiceClient(SECRET, request -> {
            if ("dashboard.focusSettings.save".equals(request.type())) {
                return new ProtocolMessage(1, SECRET, "error.invalidRequest", Map.of());
            }
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            waitUntilLoaded(view);
            FxTestSupport.call(() -> {
                setText(view, "#instagramReelsBudget", "4");
                fire(view, "#saveFocusRules");
                return null;
            });
            FxTestSupport.waitFor(
                    () -> "Could not save Focus Rules".equals(text(view, "#focusSettingsFeedback")),
                    "Focus Rules save failure");

            assertEquals(
                    "4",
                    FxTestSupport.call(() -> ((TextField) view.lookup("#instagramReelsBudget"))
                            .getText()));
            assertFalse(FxTestSupport.call(
                    () -> ((TextField) view.lookup("#instagramReelsBudget")).isDisabled()));
            assertFalse(FxTestSupport.call(
                    () -> ((Button) view.lookup("#saveFocusRules")).isDisabled()));
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void focusSettingsExchangeNeverRunsOnTheJavaFxThread() throws Exception {
        var exchangeRan = new CountDownLatch(1);
        var exchangeOnFxThread = new AtomicBoolean();
        var fxRemainedResponsive = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            exchangeOnFxThread.set(Platform.isFxApplicationThread());
            exchangeRan.countDown();
            return focusSettingsResponse(3L, 3L);
        });

        var view = FxTestSupport.call(() -> {
            var created = new FocusRulesView(client, () -> {});
            Platform.runLater(fxRemainedResponsive::countDown);
            return created;
        });
        try {
            assertTrue(exchangeRan.await(1, TimeUnit.SECONDS));
            assertTrue(fxRemainedResponsive.await(1, TimeUnit.SECONDS));
            assertFalse(exchangeOnFxThread.get());
        } finally {
            dispose(view, client);
        }
    }

    @Test
    void disposePreventsAnOutstandingResponseFromUpdatingControls() throws Exception {
        var exchangeStarted = new CountDownLatch(1);
        var releaseExchange = new CountDownLatch(1);
        var exchangeReturned = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            exchangeStarted.countDown();
            await(releaseExchange);
            exchangeReturned.countDown();
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));

        assertTrue(exchangeStarted.await(1, TimeUnit.SECONDS));
        FxTestSupport.call(() -> {
            view.dispose();
            return null;
        });
        releaseExchange.countDown();
        assertTrue(exchangeReturned.await(1, TimeUnit.SECONDS));
        FxTestSupport.call(() -> null);

        assertEquals("Loading Focus Rules…", text(view, "#focusSettingsFeedback"));
        client.close();
    }

    @Test
    void disposeStopsPollingAndRejectsAnOutstandingPollCallback() throws Exception {
        var requestNumber = new AtomicInteger();
        var pollStarted = new CountDownLatch(1);
        var releasePoll = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            if (requestNumber.incrementAndGet() == 1) {
                return focusSettingsResponse(3L, 2L);
            }
            pollStarted.countDown();
            await(releasePoll);
            return focusSettingsResponse(3L, 3L);
        });
        var view = FxTestSupport.call(
                () -> new FocusRulesView(client, () -> {}, Duration.millis(10)));
        try {
            FxTestSupport.waitFor(
                    () -> "Waiting for Chrome".equals(text(view, "#chromeSyncStatus")),
                    "pending Chrome sync");
            assertTrue(pollStarted.await(1, TimeUnit.SECONDS));

            FxTestSupport.call(() -> {
                view.dispose();
                return null;
            });
            releasePoll.countDown();
            FxTestSupport.call(() -> null);

            assertEquals("Waiting for Chrome", text(view, "#chromeSyncStatus"));
            assertEquals(2, requestNumber.get());
        } finally {
            releasePoll.countDown();
            dispose(view, client);
        }
    }

    @Test
    void refreshDoesNotOverlapAnOutstandingSettingsRequest() throws Exception {
        var requestNumber = new AtomicInteger();
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var client = new ServiceClient(SECRET, request -> {
            if (requestNumber.incrementAndGet() == 1) {
                firstStarted.countDown();
                await(releaseFirst);
                return focusSettingsResponse(1L, 1L, settingsPayload(5));
            }
            secondStarted.countDown();
            return focusSettingsResponse(2L, 2L, settingsPayload(4));
        });
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> {}));
        try {
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            FxTestSupport.call(() -> {
                view.refresh();
                return null;
            });
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
            releaseFirst.countDown();
            waitUntilLoaded(view);
            assertEquals(1, requestNumber.get());
        } finally {
            releaseFirst.countDown();
            dispose(view, client);
        }
    }

    @Test
    void strictModeNavigationRunsOnlyWhileTheViewIsAttached() {
        var navigated = new AtomicBoolean();
        var client = client(new CopyOnWriteArrayList<>());
        var view = FxTestSupport.call(() -> new FocusRulesView(client, () -> navigated.set(true)));
        try {
            waitUntilLoaded(view);
            FxTestSupport.call(() -> {
                fire(view, "#showStrictMode");
                return null;
            });
            assertTrue(navigated.get());
        } finally {
            dispose(view, client);
        }
    }

    private static ServiceClient client(CopyOnWriteArrayList<ProtocolMessage> requests) {
        return new ServiceClient(SECRET, request -> {
            requests.add(request);
            return focusSettingsResponse(3L, 3L);
        });
    }

    private static ProtocolMessage focusSettingsResponse(long revision, long appliedRevision) {
        return focusSettingsResponse(revision, appliedRevision, settingsPayload(5));
    }

    private static ProtocolMessage focusSettingsResponse(
            long revision, long appliedRevision, Map<String, Object> settings) {
        return new ProtocolMessage(
                1,
                SECRET,
                "service.focusSettings",
                Map.of(
                        "revision", revision,
                        "settings", settings,
                        "chromeAppliedRevision", appliedRevision));
    }

    private static Map<String, Object> settingsPayload(int budget) {
        var rule = Map.<String, Object>of(
                "enabled", true,
                "doomscrollBudgetMinutes", budget,
                "warningScore", 10,
                "gracePeriodSeconds", 60,
                "interventions", List.of("notify", "pause", "close-tab", "block"));
        var rules = new LinkedHashMap<String, Object>();
        rules.put("instagram-reels", rule);
        rules.put("x-timeline", rule);
        rules.put("youtube-shorts", rule);
        return Map.<String, Object>of(
                "enabled", true,
                "rules", rules);
    }

    private static String text(FocusRulesView view, String id) {
        return FxTestSupport.call(() -> ((Label) view.lookup(id)).getText());
    }

    private static String textOnFxThread(FocusRulesView view, String id) {
        return ((Label) view.lookup(id)).getText();
    }

    private static void waitUntilLoaded(FocusRulesView view) {
        FxTestSupport.waitFor(
                () -> "5".equals(FxTestSupport.call(
                        () -> ((TextField) view.lookup("#instagramReelsBudget")).getText())),
                "Focus Rules settings");
    }

    private static void setText(FocusRulesView view, String id, String value) {
        ((TextField) view.lookup(id)).setText(value);
    }

    private static void fire(FocusRulesView view, String id) {
        ((Button) view.lookup(id)).fire();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test exchange");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void dispose(FocusRulesView view, ServiceClient client) {
        FxTestSupport.call(() -> {
            view.dispose();
            return null;
        });
        client.close();
    }
}
