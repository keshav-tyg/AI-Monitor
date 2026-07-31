// @vitest-environment jsdom
import { handleEvent, resetSessions, routeForTest } from '../src/background/service-worker';
import { applyEvent, initialSession } from '../src/engine/score';
import { startPopup } from '../src/popup/main';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { getUsage, saveReturnPause, saveSettings } from '../src/shared/storage';
import type { InterventionKind } from '../src/shared/types';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
  resetSessions();
});

afterEach(() => {
  vi.useRealTimers();
});

async function enableReels(interventions: InterventionKind[]): Promise<void> {
  await saveSettings({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': {
        ...DEFAULT_SETTINGS.rules['instagram-reels'],
        enabled: true,
        interventions,
      },
    },
  });
}

function pauseCommandCount(): number {
  const spy = chrome.tabs.sendMessage as unknown as { mock: { calls: unknown[][] } };
  return spy.mock.calls.filter((call) => (call[1] as { type?: string })?.type === 'pause').length;
}

it('shows a stored return pause once, not on every heartbeat', async () => {
  await enableReels(['notify', 'pause', 'close-tab']);
  await saveReturnPause({ site: 'instagram-reels', reason: 'test', expiresAt: 9_999_999_999_999 });

  await handleEvent(5, { site: 'instagram-reels', kind: 'view-entered', at: 0 }, 0);
  for (let i = 1; i <= 5; i += 1) {
    await handleEvent(5, { site: 'instagram-reels', kind: 'heartbeat', at: i * 1_000 }, i * 1_000);
  }

  expect(pauseCommandCount()).toBe(1);
});

it('does not close the tab on Leave when close-tab is not configured', async () => {
  await enableReels(['notify', 'pause']);

  await routeForTest({ type: 'leave-feed', site: 'instagram-reels', reason: 'test' }, 5);

  expect(chrome.tabs.remove).not.toHaveBeenCalled();
});

it('closes the tab on Leave when close-tab is configured', async () => {
  await enableReels(['notify', 'pause', 'close-tab']);

  await routeForTest({ type: 'leave-feed', site: 'instagram-reels', reason: 'test' }, 5);

  expect(chrome.tabs.remove).toHaveBeenCalledWith(5);
});

it('lets a session go idle even while heartbeats keep arriving', () => {
  const start = { site: 'instagram-reels' as const, kind: 'view-entered' as const, at: 0 };
  let state = { ...initialSession(start), score: 9, consecutiveAdvances: 4 };

  // Two minutes of pure timekeeping with no scrolling at all.
  for (let i = 1; i <= 120; i += 1) {
    state = applyEvent(state, { site: 'instagram-reels', kind: 'heartbeat', at: i * 1_000 });
  }
  const resumed = applyEvent(state, { site: 'instagram-reels', kind: 'scroll', at: 121_000 });

  expect(resumed.score).toBe(0);
});

it('bills nothing for a gap longer than a single event can cover', async () => {
  await enableReels(['notify']);

  await handleEvent(5, { site: 'instagram-reels', kind: 'heartbeat', at: 0 }, 0);
  // Tab hidden for an hour, then shown again.
  await handleEvent(5, { site: 'instagram-reels', kind: 'heartbeat', at: 3_600_000 }, 3_600_000);

  expect(await getUsage('instagram-reels', 3_600_000)).toBe(0);
});

it('keeps popup controls clickable across a refresh tick', async () => {
  vi.useFakeTimers();
  const globalWithChrome = globalThis as unknown as {
    chrome: { runtime: { sendMessage: unknown } };
  };
  globalWithChrome.chrome.runtime.sendMessage = vi.fn(async () => ({
    ok: true,
    type: 'status',
    enabled: true,
    settings: DEFAULT_SETTINGS,
    sites: [
      { site: 'instagram-reels', enabled: true, usedMs: 1_000, allowedMinutes: 15, active: true },
    ],
  }));

  document.body.innerHTML = '<main id="app"></main>';
  const root = document.querySelector('#app')!;
  const stop = startPopup(root);
  await vi.advanceTimersByTimeAsync(0);

  const before = document.querySelector('button');
  await vi.advanceTimersByTimeAsync(1_000);
  const after = document.querySelector('button');

  // Same node: a click in flight during a refresh must not hit a detached button.
  expect(after).toBe(before);
  stop();
});
