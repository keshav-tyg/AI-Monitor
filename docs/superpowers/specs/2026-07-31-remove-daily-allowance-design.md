# Remove Daily Allowance — Design

## Purpose

Make the doomscroll session budget the only time-based control. The current
daily allowance and the declared session budget both meter feed use, which
makes it unclear which limit is in charge and weakens the intent-based model.

## Product behaviour

- A person enters a feed and declares either **Doomscrolling** or **Looking for
  something**.
- A doomscroll declaration grants the configurable per-site session budget
  (five minutes by default), measured only while the feed is foregrounded.
- A purposeful declaration has no timer. The local classifier may still raise
  a wall only for a high-confidence contradiction.
- Before a person has an active intent declaration, sustained passive scrolling
  may still use the score-based notification and pause ladder. An active
  declaration remains the primary control and is never overridden by that
  ladder. Neither path escalates because a daily minute total was reached.

## Removed concepts

- `dailyAllowanceMinutes` is removed from rules, defaults, Options, popup
  status, validation, documentation, and tests.
- The legacy rule engine no longer receives usage or emits a daily-allowance
  reason. It evaluates score and configured grace/intervention stages only.
- Popup rows show whether a supported session is active, but no daily
  "used today" meter.

## Preserved data and privacy

Foreground usage accounting remains local because it is the source of truth
for the active doomscroll session budget. Existing stored settings with a
daily-allowance property remain readable; the property is ignored and is
dropped on the next Options save. No new data is collected or transmitted.

## Acceptance criteria

- Options exposes a doomscroll session budget but no daily allowance field.
- A stored daily allowance cannot trigger a notification, pause, close, or
  block.
- The popup contains no daily allowance or "used today" wording.
- A doomscroll budget still walls on the next feed advance once foreground
  usage since declaration reaches that budget.
- Score-based interventions continue to work without any usage input for
  sessions that do not have an active declaration.
