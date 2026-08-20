package com.localfocuscoach.strict.dashboard;

import java.util.Map;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

final class DashboardControls {
    private DashboardControls() {}

    static HBox switchBox(CheckBox control) {
        Objects.requireNonNull(control);
        var wrapper = new HBox(control);
        wrapper.getStyleClass().add("figmaSwitch");
        wrapper.setAlignment(Pos.CENTER);
        return wrapper;
    }

    static HBox stepper(
            String fieldId,
            TextField field,
            int minimum,
            int maximum,
            String unit) {
        Objects.requireNonNull(fieldId);
        Objects.requireNonNull(field);
        Objects.requireNonNull(unit);
        if (fieldId.isBlank()) {
            throw new IllegalArgumentException("Field ID cannot be blank");
        }
        if (minimum > maximum) {
            throw new IllegalArgumentException("Minimum cannot exceed maximum");
        }

        field.setId(fieldId);
        field.getStyleClass().add("figmaStepperField");
        var decrease = stepperButton(fieldId + "Decrease", "−", field, -1, minimum, maximum);
        var increase = stepperButton(fieldId + "Increase", "+", field, 1, minimum, maximum);
        var unitLabel = new Label(unit);
        unitLabel.getStyleClass().add("figmaStepperUnit");
        var stepper = new HBox(8, decrease, field, increase, unitLabel);
        stepper.getStyleClass().add("figmaStepper");
        stepper.setAlignment(Pos.CENTER_LEFT);
        return stepper;
    }

    static HBox segmented(ToggleGroup group, Map<FocusSensitivity, RadioButton> buttons) {
        Objects.requireNonNull(group);
        Objects.requireNonNull(buttons);
        var segmented = new HBox();
        segmented.getStyleClass().add("figmaSegmented");
        for (var sensitivity : FocusSensitivity.values()) {
            var button = Objects.requireNonNull(
                    buttons.get(sensitivity), "Missing " + sensitivity + " sensitivity button");
            button.setToggleGroup(group);
            button.getStyleClass().add("figmaSegmentedOption");
            button.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(button, Priority.ALWAYS);
            segmented.getChildren().add(button);
        }
        return segmented;
    }

    static VBox card(String id, Node... children) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(children);
        var card = new VBox(children);
        card.setId(id);
        card.getStyleClass().add("figmaCard");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    static StackPane shieldIcon(String idPrefix, boolean checked, Color color) {
        Objects.requireNonNull(idPrefix);
        Objects.requireNonNull(color);
        var shield = vectorPath(
                idPrefix + "Shield",
                "M9 1.5 L15 4 V8.5 C15 12.5 12.6 15.3 9 16.5 "
                        + "C5.4 15.3 3 12.5 3 8.5 V4 Z",
                color);
        var artwork = new Group(shield);
        if (checked) {
            artwork.getChildren().add(vectorPath(
                    idPrefix + "Check", "M5.8 8.9 L8 11 L12.3 6.7", color));
        }
        var icon = new StackPane(artwork);
        configureIcon(icon, idPrefix + "Icon");
        return icon;
    }

    static StackPane lockIcon(String idPrefix, Color color) {
        Objects.requireNonNull(idPrefix);
        Objects.requireNonNull(color);
        var body = vectorPath(idPrefix + "LockBody", "M4 8 H14 V16 H4 Z", color);
        var shackle = vectorPath(
                idPrefix + "LockShackle", "M6 8 V5.7 C6 2.7 12 2.7 12 5.7 V8", color);
        var icon = new StackPane(new Group(body, shackle));
        configureIcon(icon, idPrefix + "Icon");
        return icon;
    }

    private static SVGPath vectorPath(String id, String content, Color color) {
        var path = new SVGPath();
        path.setId(id);
        path.getStyleClass().add("figmaVectorPath");
        path.setContent(content);
        path.setFill(Color.TRANSPARENT);
        path.setStroke(color);
        path.setStrokeWidth(1.5);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        return path;
    }

    private static void configureIcon(StackPane icon, String id) {
        icon.setId(id);
        icon.getStyleClass().add("figmaVectorIcon");
        icon.setMinSize(18, 18);
        icon.setPrefSize(18, 18);
        icon.setMaxSize(18, 18);
    }

    private static Button stepperButton(
            String id,
            String text,
            TextField field,
            int delta,
            int minimum,
            int maximum) {
        var button = new Button(text);
        button.setId(id);
        button.getStyleClass().add("figmaStepperButton");
        button.setOnAction(event -> {
            try {
                var current = Integer.parseInt(field.getText().trim());
                var next = current + delta;
                if (next >= minimum && next <= maximum) {
                    field.setText(Integer.toString(next));
                }
            } catch (NumberFormatException ignored) {
                // Keep invalid draft text visible so the owning view can validate it.
            }
        });
        return button;
    }
}
