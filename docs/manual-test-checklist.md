# Manual test checklist

Automated tests cover the pure logic. These checks need a real Chrome, because
they exercise tab closing, network rules, notifications, and live site markup.

Run through this list against a fresh `npm run build` + **Load unpacked** of
`dist/` before calling a change done.

## Desktop-owned Focus Rules

- [ ] **Load the generated extension directory.** After `npm run build`, use
      Chrome's **Load unpacked** picker to select this repository's `dist/`
      directory, not the repository root. Confirm the extension starts without
      a manifest-load error.
- [ ] **First-run migration imports once.** Begin with an old browser settings
      record and no desktop Focus Rules record. Connect the installed companion,
      open its dashboard, and confirm those rules arrive as revision 1. Edit and
      save a rule in the dashboard, reconnect Chrome, and confirm the old
      browser record cannot overwrite the newer desktop revision.
- [ ] **Dashboard save syncs within five seconds.** With Chrome connected,
      change a site rule in the dashboard and save. Confirm the dashboard
      reports **Synced with Chrome** and Chrome enforces the matching revision
      within one five-second native sync interval.
- [ ] **Cached enforcement survives a disconnect.** With a valid desktop
      revision already applied, disconnect the native host. Confirm cached
      rules still enforce and the dashboard reports **Waiting for Chrome**;
      reconnect and confirm it returns to **Synced with Chrome**.
- [ ] **Strict Mode rejects weakening edits.** During an active Strict Mode
      session, confirm a larger budget, a less-sensitive Focus sensitivity,
      longer grace period, disabling protection, or changing intervention
      order is rejected. Confirm a shorter budget is accepted.
- [ ] **Options only opens the dashboard.** Open the Chrome Options page.
      Confirm it has no editable Focus Rule inputs or save action, then choose
      **Open Local Focus Coach** and confirm the installed dashboard opens.

## Strict Mode companion

The existing extension checks below remain useful on their own. The companion
adds a separate macOS/Google Chrome-only acceptance pass, which requires a
packaged app image, a stable loaded-extension ID, and the per-user companion
registrations. Follow
[`desktop/docs/manual-strict-mode-checklist.md`](../desktop/docs/manual-strict-mode-checklist.md)
for that pass. Do not substitute a different Chromium browser, a temporary
extension ID, or a system-wide LaunchDaemon.

## Fail-open guarantees

- [ ] **Unsupported routes generate no action.** Visit a profile page, a search
      results page, a DM inbox, and a normal `youtube.com/watch` video. Scroll
      each for several minutes. Nothing appears.
- [ ] **A missing selector causes no action.** In DevTools, delete the `video`
      element on a Shorts page and keep scrolling. No intervention fires, no
      error surfaces in the page console.
- [ ] **A disabled rule enforces nothing.** With the site rule off but global
      protection on, scroll past the threshold. Nothing happens.
- [ ] **Emergency disable takes effect immediately.** While a session is
      running, turn protection off in the desktop dashboard and save. The very next scroll
      produces no enforcement.

## Detection quality

- [ ] **A single Reel or Short causes no warning.** Open one, watch it through,
      leave within two minutes. Silence.
- [ ] **Sustained scrolling produces a reasoned warning.** Scroll a feed
      continuously past the 120-second mark and the selected Focus sensitivity
      threshold. The notice names counts and duration — for example "8 content advances and 6
      continuous scrolls over 4m 40s" — and no page content.
- [ ] **Purposeful actions lower confidence.** Mid-session, perform each of:
      a search, a profile visit, a comment, a save, opening a message, and
      following a link. After each, the escalation clock visibly backs off.

## Intent-aware sessions

- [ ] **The whole Reels flow, end to end.** With the Instagram rule enabled and
      the budget set to one minute: open `instagram.com/reels` → the prompt
      appears → choose *Doomscrolling* → the popup shows a timer counting up
      toward `1 min session` while the feed is foregrounded → keep scrolling
      until the minute is spent → the next advance raises the wall → **Leave**
      closes the feed. Then inspect the local `activity` record in the
      service-worker DevTools and confirm four timeline rows, newest first:
      leave pressed, wall shown, timer ended, session started.
- [ ] **A feed entry asks once, with one answer.** Open Instagram Reels
      directly. The prompt offers exactly *Doomscrolling — give me N minutes*,
      quoting the desktop dashboard budget, with no second button, no text box, and no
      duration picker. Reopening during the cooldown must not prompt again.
- [ ] **The prompt cannot be dismissed into a free session.** Press Escape and
      click outside the dialog. It stays up, nothing is declared, and no
      timeline row appears.
- [ ] **A direct link gets one free item.** Paste a specific Reel URL or open
      one from another app. Confirm it opens without a prompt or wall. Advance
      once, then confirm the intent prompt appears.
- [ ] **A direct YouTube Short gets one free item.** Directly open one
      `youtube.com/shorts/<id>` URL and confirm no intent prompt appears on
      that first item. Swipe to a second distinct Short and confirm the intent
      prompt appears once.
- [ ] **Doomscroll budget walls on the next advance.** Set the doomscroll
      session budget to one minute, declare doomscrolling, and use Reels while
      it is foregrounded for one minute. The next advance must show the wall,
      which offers only Leave.
- [ ] **A wall preserves direct links.** While a feed wall is active, open a
      specific Reel in a new tab. It must show the linked item without a wall;
      advancing past it must return to the intent flow.
- [ ] **Model failure fails open.** Disable the Prompt API flag (or test while
      the local model is unavailable). Declarations and budgets still work;
      the model simply cannot veto a budget wall when it cannot answer.
- [ ] **No page content reaches the model.** In the service-worker and
      offscreen-document DevTools, inspect classify messages while using a
      session. They contain aggregate numbers and enum values only — no URL,
      caption, title, post text, or identifier.

## Session state survives everything

Each of these starts the same way: declare a doomscroll session, spend most of
the budget, then do the listed thing. In every case the remaining budget must
carry over — never reset, never bypassed.

- [ ] **Refresh the tab.** Reload the Reels page. No new prompt, and the popup
      timer resumes from where it was.
- [ ] **Close and reopen the tab.** The budget continues; a fresh prompt appears
      only after the 30-minute cooldown has passed.
- [ ] **Reload the extension.** `chrome://extensions` → reload. Without
      navigating the feed tab, keep scrolling: the wall still arrives when the
      budget is spent.
- [ ] **Restart Chrome entirely.** Same as above.
- [ ] **A session survives local midnight.** Set the system clock to 23:57,
      declare a session, spend part of the budget, then let the clock pass
      midnight and keep scrolling. The remaining budget must be what was left,
      not a fresh one — the daily usage counter resetting must change nothing.
- [ ] **A raised wall stays raised.** After the wall appears, reload the
      extension and return to the feed. Advancing must re-wall, and the activity
      log must still contain exactly one *Wall shown* row for that session.
- [ ] **"Continue for 5 minutes" is not a way past a wall.** It suppresses the
      score-ladder pause only; the declared budget still walls.

## Interventions

- [ ] **Pause offers exactly two choices.** The overlay shows **Leave** and
      **Continue for 5 minutes**, keyboard focus starts on the first button,
      and clicking outside the dialog does not dismiss it.
- [ ] **Leave exits.** The overlay closes and the session resets.
- [ ] **Continue for 5 minutes suppresses that tab only.** Enforcement stops in
      this tab for five minutes. Open the same feed in a second tab — it is
      still enforced. It does not change the doomscroll session-budget setting.
- [ ] **Close-tab closes only the originating tab.** With two feed tabs open,
      confirm the other survives.
- [ ] **Blocks are narrow.** With a block active on Instagram Reels,
      `instagram.com/reels` is blocked but the rest of Instagram still loads.
- [ ] **Blocks expire next local day.** Set the system clock past midnight,
      reopen the browser, and confirm the feed loads and the dynamic rule is
      gone from `chrome://extensions` → service worker → DevTools.

## Privacy

- [ ] **No network traffic.** Open DevTools → Network on the service worker and
      on a feed page, run a full session through to enforcement, and confirm
      the extension issues zero requests.
- [ ] **Reload preserves only local settings and history.** Restart Chrome and
      confirm rules, usage, and intervention history survive, and that
      `chrome.storage.sync` is empty.
- [ ] **Stored data contains nothing sensitive.** In the service worker console
      run `chrome.storage.local.get(console.log)` and confirm no URLs beyond the
      site key, no page text, and no coordinates.

## Interface

- [ ] **Popup reports honestly.** Protection state, each site's rule state,
      and an active-session indicator that appears only during a live session.
- [ ] **Dashboard requires an explicit save.** Toggle a rule, close the
      dashboard without saving, reopen it — the saved value is unchanged.
- [ ] **Dashboard validation blocks bad input.** Enter a session budget of `0`,
      grace `-1`, and an enabled rule with no interventions. Each shows an
      inline error and nothing persists. Confirm Focus sensitivity offers only
      Mild, Medium, and Aggressive.
- [ ] **Feedback records.** Click Accurate and Inaccurate on review entries and
      confirm the choice survives a reload.
- [ ] **Dashboard says what will happen.** Enable a rule and read its summary
      line back: it names the doomscroll budget in minutes and the intervention
      ladder in order, and changes live as the fields are edited.
- [ ] **The timeline reads plainly.** Each row shows a local timestamp, the feed
      label, one of the four moments, and a sentence built from your settings —
      no URLs, captions, or ids anywhere in it.
