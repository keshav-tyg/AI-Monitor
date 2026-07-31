// @vitest-environment jsdom
import { renderPopup } from '../src/popup/main';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

function stubStatus(
  active: boolean,
  session?: { intent: 'doomscroll' | 'purposeful'; usedMs?: number; budgetMinutes?: number },
): void {
  const globalWithChrome = globalThis as unknown as {
    chrome: { runtime: { sendMessage: unknown } };
  };
  globalWithChrome.chrome.runtime.sendMessage = vi.fn(async () => ({
    ok: true,
    type: 'status',
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      { site: 'instagram-reels', enabled: true, active, session },
    ],
  }));
}

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
});

it('shows progress against the declared doomscroll session budget', async () => {
  stubStatus(true, { intent: 'doomscroll', usedMs: 80_000, budgetMinutes: 5 });
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('rule active');
  expect(document.body.textContent).toContain('in a session now');
  expect(document.body.textContent).toContain('1m 20s of 5 min session');
  expect(document.body.textContent).not.toContain('used today');
});

it('shows no time limit for a declared purposeful session', async () => {
  stubStatus(true, { intent: 'purposeful' });
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('No time limit');
});

it('does not show a session marker when the feed is inactive', async () => {
  stubStatus(false);
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('rule active');
  expect(document.body.textContent).not.toContain('in a session now');
});
