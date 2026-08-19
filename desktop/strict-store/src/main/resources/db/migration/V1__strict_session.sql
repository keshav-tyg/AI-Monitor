CREATE TABLE strict_session (
    session_id TEXT PRIMARY KEY NOT NULL,
    mode TEXT NOT NULL CHECK (mode IN ('TIMED', 'INDEFINITE')),
    started_at TEXT NOT NULL,
    ends_at TEXT,
    early_exit_challenge INTEGER NOT NULL CHECK (early_exit_challenge IN (0, 1)),
    status TEXT NOT NULL CHECK (status IN ('ACTIVE', 'EXPIRED')),
    warning_ends_at TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE strict_session_audit (
    audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id TEXT NOT NULL,
    event TEXT NOT NULL,
    occurred_at TEXT NOT NULL,
    FOREIGN KEY (session_id) REFERENCES strict_session(session_id) ON DELETE CASCADE
);
