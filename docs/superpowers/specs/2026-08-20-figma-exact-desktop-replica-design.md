# Figma-Exact macOS Dashboard Replica

## Goal

Rebuild the Local Focus Coach macOS dashboard as a faithful native JavaFX
replica of the approved Figma export. The Focus Rules, Strict Mode, active
session, and unlock screens must use the same visual language, reference
dimensions, component states, and copy while retaining the existing local
service, auto-save, and Strict Mode security behavior.

## Source of truth

The authoritative visual reference is the user-provided Figma export at:

`~/Downloads/macOS Dashboard for Local Focus Coach.zip`

Reference screens supplied with the export show Focus Rules and Strict Mode.
The exported `src/app/App.tsx` and `src/styles/theme.css` define the exact
layout, tokens, text, and interaction presentation. They are design reference
only; production remains Java 21 and JavaFX, and no React/WebView runtime is
introduced.

## Scope

### In scope

- Native JavaFX visual rebuild of the dashboard shell and Focus Rules screen.
- Native JavaFX visual rebuild of idle Strict Mode, active-session, and unlock
  screens using the same component system.
- Custom JavaFX presentation for switches, stepper buttons, segmented
  sensitivity selection, checkboxes, cards, status bar, site badges, and
  macOS-style title bar.
- Screenshot-based visual validation at the Figma reference size and normal
  JavaFX behavioral tests for real settings, auto-save, Strict Mode, and
  unlock protection.

### Out of scope

- Service, relay, extension, native-messaging, persistence, and Gemini/model
  changes.
- A WebView, React runtime, Tailwind, or copied generated front-end code.
- Changing session policy, native enforcement, the 700 ms save delay, or the
  challenge security model.

## Shared visual system

The reference canvas is an 1100 × 760 macOS window. It remains resizable, but
this size is the visual acceptance target.

- Window canvas: `#F0EFE9`; surrounding preview/background: muted warm grey.
- Title bar: 40 px high, `#E8E7E1`, with the red/yellow/green macOS controls
  at left and a centered `Local Focus Coach` title.
- Sidebar: 208 px wide, `#E8E7E1`, thin low-opacity divider, 12 px horizontal
  padding. It contains a 32 px green logo tile, two text navigation rows, and
  a local-privacy panel anchored at the bottom.
- Main content: 28 px horizontal and 24 px vertical page padding; 16 px gaps.
- Typography: Inter-style sans serif with JavaFX system fallback. Titles are
  18 px semibold, card titles 13 px semibold, body 12–13 px, metadata and
  labels 10–11 px. Numeric/challenge text uses a monospace system fallback.
- Palette: primary green `#2F6B4A`; active/soft green `#E8F4EE`; sidebar
  `#E8E7E1`; canvas `#F0EFE9`; foreground `#1C1C1E`; muted `#6E6E73`; red
  `#C0392B`; amber safety treatment; thin borders `rgba(0,0,0,0.08)`.
- Cards: white, 16 px corner radius, 20 px padding, low-opacity border, and
  a restrained `0 1 4 rgba(0,0,0,0.06)`-equivalent shadow.
- Controls use the Figma 12 px radius family: green pill switches, pale-grey
  stepper buttons, selected white segmented tab with a subtle shadow, and
  16 px square green checkboxes.

All existing accessible IDs remain stable except the already removed manual
Focus Rules save button. Decorative icon nodes do not replace text labels.

## Focus Rules layout

Focus Rules is the default route and matches the supplied screen.

1. Header: 18 px `Focus Rules` title, the approved passive-use subtitle, and
   a dark compact Strict Mode CTA containing lock icon, `Strict Mode`,
   `Prevents weakening settings`, and a right chevron.
2. Protection card: shield tile, `Protection enabled`/`Protection disabled`,
   Active badge when enabled, descriptive status copy, and a large switch.
3. Site grid: exactly two equal columns at the 1100 px reference width, 16 px
   gap, then one column when the usable main-content width is below 720 px.
   Cards render Instagram Reels, YouTube Shorts, and X Timeline in the
   reference order.
4. Each site card contains the colored site badge, name, route label, switch,
   divider, Session budget and Grace period steppers, the three-way Mild /
   Medium / Aggressive segmented selector, selected-level hint, and ordered
   intervention checkboxes. The block-duration pill appears only if the block
   intervention is selected.
5. The footer is a pinned status bar. It displays the actual status using the
   Figma visual state: spinner/amber for saving, green check for saved/synced,
   and red only for actual errors. A newer draft must never show stale `Saved`.

Figma's sample values are visual examples only. The dashboard always renders
the real service snapshot and saves through the existing authenticated
`dashboard.focusSettings.save` request. Sensitivity remains stored as numeric
scores: Mild=10, Medium=5, Aggressive=1; untouched legacy values are retained.

## Strict Mode, active session, and unlock

Idle Strict Mode matches the supplied reference:

- Header: dark 32 px lock tile, 18 px title, and the approved explanation that
  locked sessions prevent weakening, deletion, or disabling of rules.
- Session card: equal Timed session and Indefinite selection cards. The chosen
  mode has green border and pale-green fill. Timed mode exposes hours and
  minutes via Figma-style steppers.
- Preparation card: `Unlock sequence`, explanatory copy, a centered monospace
  challenge panel, confirmation field, and error state. It uses the existing
  generated challenge and validation semantics.
- Safety card: amber `Good to know` card with the existing honest policy copy.
- Start action: full-width dark `Start Strict Mode` button, disabled according
  to the current real preconditions.

The active-session and separate unlock views use the same card and color
system. Countdown/warning facts, session state, unlock availability, and
errors remain exactly truthful to the service state.

The Figma preview shows a short grouped sequence for readability. The
production app retains its current 500-character challenge. It may group and
scroll the visible text in the same monospace panel, but must not shorten,
copy, reveal incorrect characters, or otherwise weaken the challenge.
Backspace, paste/drop blocking, context-menu/clipboard protections, and late
callback disposal behavior remain unchanged.

## Native component architecture

Create a small dashboard presentation layer inside the existing JavaFX module:

- A shared shell owns title bar, sidebar, navigation state, content viewport,
  and pinned footer placement.
- Reusable native builders/controls own one visual concern each: `FigmaSwitch`,
  `FigmaStepper`, `FigmaSegmentedControl`, `FigmaCheckBox`, `DashboardCard`,
  and `SiteBadge` (names may follow current project conventions).
- `FocusRulesView`, `StrictModeView`, and `UnlockChallengeView` bind those
  components to their current real model and service callbacks. They must not
  duplicate protection policy or protocol parsing.
- The stylesheet owns only visual tokens/layout states; it does not encode
  business rules or save orchestration.

Use exported Figma assets when available. The provided export contains textual
site marks and Lucide icon references rather than downloadable production SVGs;
until a Figma node-specific export supplies assets, use existing JavaFX shapes
or text marks solely where they visually match the supplied reference and keep
their adjacent text labels accessible.

## State and error behavior

- Every intentional Focus Rules edit increments the draft generation,
  immediately clears old success text, and restarts the existing 700 ms
  debounce.
- Invalid edits display only their validation message and never issue a save.
- In-flight save responses may set Saved/Synced only when their submitted
  generation equals the current draft generation. A changed or invalid draft
  cannot be labeled saved.
- Existing latest-draft queue, refresh-generation guard, disposal, and Chrome
  sync polling semantics remain intact.
- Strict Mode save rejections, service failures, unavailable Chrome sync, and
  unlock failures keep the current draft/challenge state visible and use the
  corresponding Figma error or amber safety treatment.

## Validation

1. JavaFX tests verify the exact content/accessible IDs, grid breakpoints,
   custom-control semantics, draft-status transitions, and preserved focus-rule
   request payloads.
2. Strict Mode and unlock tests retain all current timed/indefinite, warning,
   500-character, paste/drop, hidden-mismatch, and clipboard assertions.
3. At 1100 × 760, capture the Focus Rules and Strict Mode JavaFX scenes and
   compare their component bounds, visible text, selected states, and color
   tokens against the supplied Figma reference. Any difference must be
   intentional, documented, and limited to genuine native window behavior or
   the stronger 500-character security requirement.
4. Run the full Java 21 desktop test, build, and macOS `jpackage` gate. The
   packaged `Local Focus Coach.app` must exist before delivery.

## Acceptance criteria

- The physical app visibly matches the supplied Figma screens at 1100 × 760:
  sidebar, cards, headers, control shapes, colors, typography hierarchy,
  spacing, and footer status bar.
- All controls operate on real Local Focus Coach settings and not demo data.
- Focus Rules auto-save, Strict Mode, and unlock behavior remain secure and
  fully tested.
- No new web runtime or backend/protocol behavior is introduced.
