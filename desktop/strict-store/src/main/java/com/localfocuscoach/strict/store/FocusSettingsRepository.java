package com.localfocuscoach.strict.store;

import com.localfocuscoach.strict.focus.FocusSettings;
import java.util.Optional;

/** Persistence boundary for the single desktop-owned focus-settings document. */
public interface FocusSettingsRepository {
    Optional<FocusSettings> load();

    FocusSettings save(FocusSettings candidate);

    FocusSettings importIfAbsent(FocusSettings legacy);
}
