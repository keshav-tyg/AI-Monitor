# Focus Sensitivity Levels and YouTube Shorts Intent Design

## Goal

Replace the confusing numeric **Warning score** control with three clear
desktop-dashboard choices, while preserving the current numeric protocol and
existing users' behavior. Fix YouTube Shorts so moving from one Short to the
next reliably starts the intent flow.

## Scope

This change covers the macOS Focus Rules dashboard, the existing numeric focus
settings model, the Chrome content script's YouTube Shorts advance detection,
tests, and user-facing documentation.

It does not add cloud services, alter the Native Messaging protocol shape,
change the score engine's point calculations, add sites, or change Strict Mode
session behavior.

## Sensitivity levels

The dashboard presents exactly three mutually exclusive choices for every
enabled site rule:

| Level | Stored `warningScore` | Meaning |
| --- | ---: | --- |
| Mild | 10 | Intervene after more sustained passive scrolling. |
| Medium | 5 | A balanced reminder. |
| Aggressive | 1 | Intervene quickly after passive scrolling begins. |

Mild is the first and default choice for a new rule. The numeric text field and
its `1–50` instruction are removed from the dashboard. The setting is described
as **Focus sensitivity**, not a confidence or warning score.

## Compatibility and persistence

`warningScore` remains the persisted field in SQLite, the service request and
response payloads, the Native Messaging relay, and the extension cache. Those
interfaces continue accepting the legacy integer range of `1..50`, so an
upgrade cannot make a valid existing installation unreadable.

The dashboard maps existing non-preset values to the closest displayed level:

- `1..3` displays Aggressive;
- `4..7` displays Medium;
- `8..50` displays Mild.

Displaying a level never writes to storage. For an existing non-preset score,
the dashboard preserves the exact stored number while the sensitivity control
has not been deliberately changed. Once the person selects a sensitivity level
and saves, the dashboard writes that level's exact numeric value (`1`, `5`, or
`10`). New settings always start at Mild/`10`.

This behavior prevents merely opening the dashboard, or saving an unrelated
field, from silently making a legacy rule stricter or gentler.

## Strict Mode behavior

Strict Mode keeps comparing the underlying numeric values. Moving toward a
lower score is a stricter change and remains allowed during an active Strict
Mode session; moving toward a higher score is weaker and remains rejected.
The dashboard's labels do not weaken that rule:

- Mild → Medium or Aggressive is allowed.
- Medium → Aggressive is allowed.
- Aggressive → Medium or Mild is rejected.
- Medium → Mild is rejected.

An untouched legacy numeric score is sent back unchanged, so saving another
field during Strict Mode does not create a synthetic sensitivity change.

## YouTube Shorts intent detection

The first directly opened Short remains a free item. It is classified as a
direct link because a `/shorts/<id>` URL names a specific item. When the person
moves to the next Short, the first changed Shorts item URL is the reliable
advance signal:

```text
/shorts/first-item  →  allowed direct-link item
/shorts/second-item →  one content advance → intent prompt
```

The content script tracks the normalized Short identifier for the active
YouTube Shorts route. It emits one `content-advance` event only when that
identifier changes after initial arrival. The YouTube adapter no longer uses a
video-source mutation as a second advance source, so a single swipe cannot be
double-counted by both the router and the player.

The existing route guard remains exact: unsupported YouTube routes never start
an adapter or emit an intent event. A malformed or missing Short identifier is
ignored rather than guessed.

## Validation and tests

Automated coverage must prove:

1. Mild, Medium, and Aggressive render in that order and save `10`, `5`, and
   `1` respectively.
2. A legacy score maps to the right displayed level but stays byte-for-byte
   unchanged when another rule field is saved without changing sensitivity.
3. Selecting a level writes only its canonical score.
4. Strict Mode accepts stricter level changes and rejects weaker ones.
5. A first YouTube Short URL does not advance the intent flow.
6. A changed valid Short identifier emits exactly one advance; repeating the
   same URL emits none.
7. That advance reaches the existing declaration flow and displays the intent
   prompt for an enabled YouTube Shorts rule.
8. The full TypeScript and Java test suites, extension build, and macOS app
   package build remain green.

## Documentation

The README and dashboard copy explain Focus sensitivity in plain language and
show the Mild/Medium/Aggressive mappings. They no longer ask an end user to
choose a raw warning score.
