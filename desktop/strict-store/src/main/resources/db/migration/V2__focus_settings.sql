CREATE TABLE focus_settings (
    singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
    revision INTEGER NOT NULL CHECK (revision >= 1),
    settings_json TEXT NOT NULL,
    imported_from_extension INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL
);
