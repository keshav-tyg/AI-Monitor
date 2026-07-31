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

- [ ] **A feed entry asks once.** Open Instagram Reels directly. Confirm the
      single doomscroll-start button shows the Options-configured budget.
      Reopening during the cooldown must not prompt again.
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
