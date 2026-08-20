# Figma-Inspired Desktop Dashboard Refresh

## Goal

Refresh the Local Focus Coach macOS dashboard to match the approved Figma
direction, while preserving all existing Focus Rules, Strict Mode, and unlock
security behavior. Focus Rules gains automatic saving so a person never needs
to press a save button after changing a rule. Strict Mode and the unlock view
then adopt the same visual system.

## Scope and order

The work is intentionally delivered in two product increments:

1. Focus Rules shell, rule cards, auto-save, and sync feedback.
2. Strict Mode and unlock screens, using the same shell and visual language.

No service, relay, browser-extension, persistence, protocol, or model behavior
changes. The persisted numeric `warningScore` contract remains intact.

## Shared desktop shell

The JavaFX dashboard uses a desktop-first layout inspired by the approved
Figma design:

- A compact left sidebar identifies **Local Focus Coach** and contains Focus
  Rules and Strict Mode navigation.
- The selected destination has a green active treatment. The root background
  is a warm off-white; cards are white with subtle borders/shadows; type is
  high-contrast charcoal.
- Green communicates active/protected/success. Amber communicates pending or
  safety information. Red is reserved for explicit errors.
- The main content remains keyboard-accessible and usable in narrow or short
  windows via a vertical scroll container. Brand badges are decorative only;
  all controls retain text labels.
- A small footer/privacy note says that browsing history and personal data do
  not leave the device.

The existing view IDs that tests and automation use stay stable unless a
control is deliberately removed (the Focus Rules save button is removed).

## Focus Rules

Focus Rules becomes the default dashboard destination and retains the existing
master protection control and three supported site rules: Instagram Reels,
YouTube Shorts, and X Timeline.

Each site is represented by a clear card in a responsive two-column grid:

- Site name and route label, plus an enable toggle.
- Doomscroll session budget and grace period controls with their existing
  allowed numeric ranges.
- A three-way Focus sensitivity selector in exactly this order: Mild,
  Medium, Aggressive. It maps to stored scores 10, 5, and 1 respectively;
  legacy scores preserve their exact stored value until a person deliberately
  chooses a level.
- The existing intervention controls retain their visible and execution order:
  Notify me, Show a pause screen, Close the tab, Block until tomorrow.

The screen header includes a compact Strict Mode call to action. It explains
that Strict Mode prevents settings from being weakened while a locked session
is active.

### Auto-save behavior

Every intentional Focus Rules edit starts a 700 ms debounce. A valid settled
draft sends the existing `dashboard.focusSettings.save` request automatically.
There is no manual Save Focus Rules button.

- Numeric edits do not save while outside their existing valid range; the
  relevant validation message remains visible until corrected.
- While a request is in progress, controls remain responsive. A later edit is
  retained and results in exactly one follow-up save after the in-flight
  request completes. The latest draft always wins.
- The footer uses plain language: **Saving changes…**, **Saved**, **Synced
  with Chrome**, **Waiting for Chrome**, or the existing validation/Strict
  Mode error. A saved snapshot must never overwrite a newer local draft.
- A Strict Mode weakening rejection leaves the person's draft visible and
  tells them why it cannot be saved.
- Disposing or navigating away cancels pending debounce/poll timers and
  ignores late async responses.

## Strict Mode and unlock

Strict Mode gets the same sidebar, header, card spacing, color system, and
status feedback after Focus Rules is complete. Its behavior is unchanged:

- A person chooses a timed or indefinite session. Timed mode exposes the
  existing duration control; indefinite mode exposes the existing early-exit
  challenge policy.
- Active and restore-warning states preserve their exact existing facts,
  countdown, and controls.
- The unlock view remains a security interaction: it requires the generated
  500-character challenge; backspace works; paste/drop stay blocked; incorrect
  characters are not revealed; and the challenge is not copied to the system
  clipboard. The visual treatment may change, not those protections.

## Validation

Automated tests must cover:

1. Focus Rules renders the sidebar, compact Strict Mode call to action, cards,
   sensitivity choices, and no manual save button.
2. A settled valid edit auto-saves once; rapid edits coalesce; and an edit made
   during a save produces one follow-up request containing the latest draft.
3. Invalid numeric inputs do not send requests; save and Strict Mode errors
   remain visible without losing the draft; navigation/disposal suppresses late
   callbacks.
4. Existing numeric sensitivity preservation, Chrome sync status, strict
   numeric direction, Strict Mode session states, and unlock protections stay
   green.
5. The full Java desktop tests, build, and macOS `jpackage` package succeed.
