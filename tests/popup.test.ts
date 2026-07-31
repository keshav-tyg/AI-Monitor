// @vitest-environment jsdom
import { renderPopup } from '../src/popup/main';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

function stubStatus(active: boolean): void {
  const globalWithChrome = globalThis as unknown as {
    chrome: { runtime: { sendMessage: unknown } };
  };
  globalWithChrome.chrome.runtime.sendMessage = vi.fn(async () => ({
    ok: true,
    type: 'status',
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      { site: 'instagram-reels', enabled: true, active },
    ],
  }));
}

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
});

it('shows session state without a daily usage meter', async () => {
  stubStatus(true);
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('rule active');
  expect(document.body.textContent).toContain('in a session now');
  expect(document.body.textContent).not.toMatch(/used today|of \d+ min/);
});

it('does not show a session marker when the feed is inactive', async () => {
  stubStatus(false);
  document.body.innerHTML = '<main id="app"></main>';

  await renderPopup(document.querySelector('#app')!);

  expect(document.body.textContent).toContain('rule active');
  expect(document.body.textContent).not.toContain('in a session now');
});
