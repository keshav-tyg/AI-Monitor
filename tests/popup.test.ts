// @vitest-environment jsdom
import { renderPopup } from '../src/popup/main';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

function stubStatus(usedMs: number, allowedMinutes: number): void {
  const globalWithChrome = globalThis as unknown as {
    chrome: { runtime: { sendMessage: unknown } };
  };
  globalWithChrome.chrome.runtime.sendMessage = vi.fn(async () => ({
    ok: true,
    type: 'status',
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      { site: 'instagram-reels', enabled: true, usedMs, allowedMinutes, active: true },
    ],
  }));
}

function stubLegacyStatus(usedMinutes: number, allowedMinutes: number): void {
  const globalWithChrome = globalThis as unknown as {
    chrome: { runtime: { sendMessage: unknown } };
  };
  globalWithChrome.chrome.runtime.sendMessage = vi.fn(async () => ({
    ok: true,
    type: 'status',
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      { site: 'instagram-reels', enabled: true, usedMinutes, allowedMinutes, active: true },
    ],
  }));
}

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
});

it('shows partial-minute usage instead of flooring it away', async () => {
  // 38.5s against a 1-minute allowance used to render "0 of 1 min", so the
  // popup looked frozen right up until the moment it enforced.
  stubStatus(38_507, 1);
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('38s of 1 min');
  expect(document.body.textContent).not.toContain('0 of 1 min');
});

it('still reads naturally past a minute', async () => {
  stubStatus(135_000, 15);
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('2m 15s of 15 min');
});

it('renders a whole-minute response from a worker that has not reloaded yet', async () => {
  // `usedMinutes` was the status contract before the popup switched to raw
  // milliseconds. A stale service worker must not turn this into "NaNs".
  stubLegacyStatus(1, 1);
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('1m 0s of 1 min');
  expect(document.body.textContent).not.toContain('NaNs');
});
