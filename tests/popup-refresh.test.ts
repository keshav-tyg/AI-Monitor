// @vitest-environment jsdom
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

function status(enabled: boolean, active: boolean) {
  return {
    ok: true,
    type: 'status' as const,
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      {
        site: 'instagram-reels' as const,
        enabled,
        active,
      },
    ],
  };
}

afterEach(() => {
  vi.useRealTimers();
});

it('refreshes the visible rule state while the popup remains open', async () => {
  vi.useFakeTimers();
  installChromeApiSpies();
  installChromeStorageStub();
  const sendMessage = vi
    .fn()
    .mockResolvedValueOnce(status(true, false))
    .mockResolvedValueOnce(status(false, true));
  (globalThis as unknown as { chrome: { runtime: { sendMessage: unknown } } }).chrome.runtime.sendMessage = sendMessage;
  document.body.innerHTML = '<main id="app"></main>';

  vi.resetModules();
  await import('../src/popup/main');
  await vi.advanceTimersByTimeAsync(0);
  expect(document.body.textContent).toContain('rule active');

  await vi.advanceTimersByTimeAsync(1_000);
  expect(document.body.textContent).toContain('rule disabled');
  expect(document.body.textContent).toContain('in a session now');
});
