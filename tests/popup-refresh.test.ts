// @vitest-environment jsdom
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

function status(usedMs: number) {
  return {
    ok: true,
    type: 'status' as const,
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      {
        site: 'instagram-reels' as const,
        enabled: true,
        usedMs,
        allowedMinutes: 15,
        active: true,
      },
    ],
  };
}

afterEach(() => {
  vi.useRealTimers();
});

it('refreshes the visible usage counter while the popup remains open', async () => {
  vi.useFakeTimers();
  installChromeApiSpies();
  installChromeStorageStub();
  const sendMessage = vi
    .fn()
    .mockResolvedValueOnce(status(0))
    .mockResolvedValueOnce(status(1_000));
  (globalThis as unknown as { chrome: { runtime: { sendMessage: unknown } } }).chrome.runtime.sendMessage = sendMessage;
  document.body.innerHTML = '<main id="app"></main>';

  vi.resetModules();
  await import('../src/popup/main');
  await vi.advanceTimersByTimeAsync(0);
  expect(document.body.textContent).toContain('0s of 15 min');

  await vi.advanceTimersByTimeAsync(1_000);
  expect(document.body.textContent).toContain('1s of 15 min');
});
