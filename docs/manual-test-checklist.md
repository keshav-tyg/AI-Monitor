# Manual test checklist

Automated tests cover the pure logic. These checks need a real Chrome, because
they exercise tab closing, network rules, notifications, and live site markup.

Run through this list against a fresh `npm run build` + **Load unpacked** of
`dist/` before calling a change done.

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
      running, turn protection off in Options and save. The very next scroll
      produces no enforcement.

## Detection quality

- [ ] **A single Reel or Short causes no warning.** Open one, watch it through,
      leave within two minutes. Silence.
- [ ] **Sustained scrolling produces a reasoned warning.** Scroll a feed
      continuously past the 120-second mark and the score threshold. The notice
      names counts and duration — for example "8 content advances and 6
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
      closes the feed. Then open Options and confirm four timeline rows, newest
      first: leave pressed, wall shown, timer ended, session started.
- [ ] **A feed entry asks once, with one answer.** Open Instagram Reels
      directly. The prompt offers exactly *Doomscrolling — give me N minutes*,
      quoting the Options budget, with no second button, no text box, and no
      duration picker. Reopening during the cooldown must not prompt again.
- [ ] **The prompt cannot be dismissed into a free session.** Press Escape and
      click outside the dialog. It stays up, nothing is declared, and no
      timeline row appears.
- [ ] **A direct link gets one free item.** Paste a specific Reel URL or open
      one from another app. Confirm it opens without a prompt or wall. Advance
      once, then confirm the intent prompt appears.
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
      extension and return to the feed. Advancing must re-wall, and Options must
      still show exactly one *Wall shown* row for that session.
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
- [ ] **Options requires an explicit save.** Toggle a rule, close the page
      without saving, reopen it — the change is gone.
- [ ] **Validation blocks bad input.** Enter a session budget of `0`, warning
      score `99`, grace `-1`, and an enabled rule with no interventions. Each shows an
      inline error and nothing persists.
- [ ] **Feedback records.** Click Accurate and Inaccurate on review entries and
      confirm the choice survives a reload.
- [ ] **Options says what will happen.** Enable a rule and read its summary line
      back: it names the doomscroll budget in minutes and the intervention
      ladder in order, and changes live as the fields are edited.
- [ ] **The timeline reads plainly.** Each row shows a local timestamp, the feed
      label, one of the four moments, and a sentence built from your settings —
      no URLs, captions, or ids anywhere in it.
