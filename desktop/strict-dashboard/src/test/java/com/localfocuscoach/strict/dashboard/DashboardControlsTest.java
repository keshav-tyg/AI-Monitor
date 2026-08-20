package com.localfocuscoach.strict.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.junit.jupiter.api.Test;

class DashboardControlsTest {
    @Test
    void switchBoxRetainsTheSuppliedNativeCheckBox() {
        FxTestSupport.call(() -> {
            var control = new CheckBox();
            control.setId("protectionEnabled");

            var wrapper = DashboardControls.switchBox(control);
            new Scene(wrapper);

            assertEquals("figmaSwitch", wrapper.getStyleClass().getLast());
            assertSame(control, wrapper.lookup("#protectionEnabled"));
            assertTrue(wrapper.getChildren().contains(control));
            return null;
        });
    }

    @Test
    void stepperChangesTheExistingFieldWithoutCrossingItsBounds() {
        FxTestSupport.call(() -> {
            var field = new TextField("10");
            HBox stepper = DashboardControls.stepper("budget", field, 10, 11, "min");
            new Scene(stepper);

            assertSame(field, stepper.lookup("#budget"));
            ((Button) stepper.lookup("#budgetIncrease")).fire();
            assertEquals("11", field.getText());
            ((Button) stepper.lookup("#budgetIncrease")).fire();
            assertEquals("11", field.getText());
            ((Button) stepper.lookup("#budgetDecrease")).fire();
            assertEquals("10", field.getText());
            ((Button) stepper.lookup("#budgetDecrease")).fire();
            assertEquals("10", field.getText());
            return null;
        });
    }

    @Test
    void segmentedRetainsThreeNativeRadioButtonsInSensitivityOrder() {
        FxTestSupport.call(() -> {
            var group = new ToggleGroup();
            var buttons = new EnumMap<FocusSensitivity, RadioButton>(FocusSensitivity.class);
            for (var sensitivity : FocusSensitivity.values()) {
                var button = new RadioButton(sensitivity.name());
                button.setId(sensitivity.name().toLowerCase());
                buttons.put(sensitivity, button);
            }

            var segmented = DashboardControls.segmented(group, buttons);
            new Scene(segmented);

            assertEquals(3, segmented.getChildren().size());
            assertSame(buttons.get(FocusSensitivity.MILD), segmented.getChildren().get(0));
            assertSame(buttons.get(FocusSensitivity.MEDIUM), segmented.getChildren().get(1));
            assertSame(buttons.get(FocusSensitivity.AGGRESSIVE), segmented.getChildren().get(2));
            assertTrue(buttons.values().stream().allMatch(button -> button.getToggleGroup() == group));
            return null;
        });
    }

    @Test
    void cardAppliesTheSharedFigmaCardContract() {
        FxTestSupport.call(() -> {
            var child = new CheckBox("Enabled");
            VBox card = DashboardControls.card("protectionCard", child);

            assertEquals("protectionCard", card.getId());
            assertTrue(card.getStyleClass().contains("figmaCard"));
            assertSame(child, card.getChildren().getFirst());
            return null;
        });
    }
}
