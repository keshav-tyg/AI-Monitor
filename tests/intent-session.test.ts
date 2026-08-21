const { classifyMock } = vi.hoisted(() => ({ classifyMock: vi.fn() }));

vi.mock('../src/background/classifier-client', () => ({
  classify: classifyMock,
  resetClassifierClient: vi.fn(),
}));

import { resetSessions, routeForTest } from '../src/background/service-worker';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { listActivity, listInterventions } from '../src/shared/storage';
import {
  cacheDesktopSettingsForTest,
  installChromeApiSpies,
  installChromeStorageStub,
} from './chrome-storage';

beforeEach(async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-07-31T12:00:00'));
  installChromeStorageStub();
  installChromeApiSpies();
  resetSessions();
  classifyMock.mockReset();
  classifyMock.mockResolvedValue(undefined);
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': { ...DEFAULT_SETTINGS.rules['instagram-reels'], enabled: true },
    },
  });
});

afterEach(() => {
  vi.useRealTimers();
});

async function arrive(entryKind: 'deep-link' | 'feed-entry' = 'feed-entry'): Promise<void> {
  await routeForTest({ type: 'arrive', site: 'instagram-reels', entryKind }, 72);
}

async function declare(intent: 'doomscroll' | 'purposeful'): Promise<void> {
  await routeForTest({ type: 'declare-intent', site: 'instagram-reels', intent }, 72);
}

async function addEngagementRecords(count: number): Promise<void> {
  for (let index = 0; index < count; index += 1) {
    await routeForTest(
      {
        type: 'engagement',
        site: 'instagram-reels',
        record: {
          dwellMs: 2_000,
          playedFraction: 0.2,
          replayCount: 0,
          unmuted: false,
          manuallyPaused: false,
          advancedBy: 'scroll',
        },
      },
      72,
    );
  }
}


async function heartbeat(at: number, tabId = 72): Promise<void> {
  await routeForTest({ type: 'event', event: { site: 'instagram-reels', kind: 'heartbeat', at } }, tabId);
}

/**
 * Bills real foreground time the way the content script does — a stream of
 * events the worker measures gaps between. Nothing here touches the daily
 * usage counter directly, because a declared budget no longer reads it.
 */
async function spendForeground(ms: number, tabId = 72): Promise<void> {
  let at = Date.now();
  await heartbeat(at, tabId);
  let remaining = ms;
  while (remaining > 0) {
    const step = Math.min(30_000, remaining);
    at += step;
    await heartbeat(at, tabId);
    remaining -= step;
  }
}

it('prompts when an Instagram feed session begins', async () => {
  await expect(
    routeForTest({ type: 'arrive', site: 'instagram-reels', entryKind: 'feed-entry' }, 72),
  ).resolves.toEqual({ ok: true, type: 'ack' });

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'prompt-intent',
    site: 'instagram-reels',
    budgetMinutes: 5,
  });
});

it('leaves a deep-link item alone, then prompts on its first advance', async () => {
  await arrive('deep-link');
  expect(chrome.tabs.sendMessage).not.toHaveBeenCalled();

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'prompt-intent',
    site: 'instagram-reels',
    budgetMinutes: 5,
  });
});

it('prompts when a YouTube Short deep-link advances', async () => {
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'youtube-shorts': { ...DEFAULT_SETTINGS.rules['youtube-shorts'], enabled: true },
    },
  });

  await routeForTest({ type: 'arrive', site: 'youtube-shorts', entryKind: 'deep-link' }, 72);
  await routeForTest({ type: 'event', event: {
    site: 'youtube-shorts', kind: 'content-advance', at: 2_000,
  } }, 72);

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'prompt-intent',
    site: 'youtube-shorts',
    budgetMinutes: 5,
  });
});

it('walls a spent doomscroll budget once and records one intervention', async () => {
  await arrive();
  await declare('doomscroll');
  await spendForeground(300_000);

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'wall',
    site: 'instagram-reels',
    reason: 'The 5 minutes you asked for are up',
  });
  expect(await listInterventions()).toHaveLength(1);
});

it('reports elapsed doomscroll session time to the popup', async () => {
  await arrive();
  await declare('doomscroll');
  await spendForeground(80_000);

  const response = await routeForTest({ type: 'get-status' }, undefined);
  const reels = response.ok && response.type === 'status'
    ? response.sites.find((site) => site.site === 'instagram-reels')
    : undefined;

  expect(reels?.session).toEqual({
    intent: 'doomscroll',
    usedMs: 80_000,
    budgetMinutes: 5,
  });
});

it('caps the popup timer at the budget once the wall is raised', async () => {
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': {
        ...DEFAULT_SETTINGS.rules['instagram-reels'],
        enabled: true,
        doomscrollBudgetMinutes: 1,
      },
    },
  });

  await arrive();
  await declare('doomscroll');
  // Real usage: the last spend interval always overshoots the exact budget by
  // up to MAX_EVENT_GAP_MS. The popup should not tell the person their
  // 1-minute session lasted 1m 26s.
  await spendForeground(80_000);
  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  const response = await routeForTest({ type: 'get-status' }, undefined);
  const reels =
    response.ok && response.type === 'status'
      ? response.sites.find((site) => site.site === 'instagram-reels')
      : undefined;

  expect(reels?.session).toEqual({ intent: 'doomscroll', usedMs: 60_000, budgetMinutes: 1 });
});

it('walls a 1-minute budget with grammatically correct copy', async () => {
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': {
        ...DEFAULT_SETTINGS.rules['instagram-reels'],
        enabled: true,
        doomscrollBudgetMinutes: 1,
      },
    },
  });

  await arrive();
  await declare('doomscroll');
  await spendForeground(60_000);
  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'wall',
    site: 'instagram-reels',
    reason: 'The 1 minute you asked for is up',
  });
});

it('walls a declared purposeful session only for a high-confidence contradiction', async () => {
  classifyMock.mockResolvedValue({
    verdict: 'contradicts',
    confidence: 0.85,
    reason: 'Every item was skipped quickly',
  });
  await arrive();
  await declare('purposeful');
  await addEngagementRecords(5);

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'wall',
    site: 'instagram-reels',
    reason: 'Every item was skipped quickly',
  });
});

it('does not wall a declared purposeful session below the confidence threshold', async () => {
  classifyMock.mockResolvedValue({ verdict: 'contradicts', confidence: 0.6, reason: 'maybe passive' });
  await arrive();
  await declare('purposeful');
  await addEngagementRecords(5);

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).not.toHaveBeenCalledWith(
    72,
    expect.objectContaining({ type: 'wall' }),
  );
});

it('fails open when classification rejects', async () => {
  classifyMock.mockRejectedValue(new Error('model stopped'));
  await arrive();
  await declare('purposeful');
  await addEngagementRecords(5);

  await expect(
    routeForTest(
      { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
      72,
    ),
  ).resolves.toEqual({ ok: true, type: 'ack' });
  expect(chrome.tabs.sendMessage).not.toHaveBeenCalledWith(
    72,
    expect.objectContaining({ type: 'wall' }),
  );
});

it('closes a walled feed when Leave is chosen even without legacy close-tab enforcement', async () => {
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': {
        ...DEFAULT_SETTINGS.rules['instagram-reels'],
        enabled: true,
        interventions: ['pause'],
      },
    },
  });

  await routeForTest({ type: 'wall-leave', site: 'instagram-reels' }, 72);

  expect(chrome.tabs.remove).toHaveBeenCalledWith(72);
});

it('still walls a spent budget after the service worker is torn down', async () => {
  await arrive();
  await declare('doomscroll');
  await spendForeground(300_000);

  // Exactly what an MV3 teardown, an extension reload, or a browser restart
  // does: every in-memory map is gone, and no fresh `arrive` is sent because
  // the tab never navigated.
  resetSessions();

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'wall',
    site: 'instagram-reels',
    reason: 'The 5 minutes you asked for are up',
  });
});

it('keeps a raised wall raised across a teardown', async () => {
  await arrive();
  await declare('doomscroll');
  await spendForeground(300_000);
  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  resetSessions();
  (chrome.tabs.sendMessage as ReturnType<typeof vi.fn>).mockClear();

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(
    72,
    expect.objectContaining({ type: 'wall' }),
  );
  // Still one crossing: a teardown must not multiply the record either.
  expect(await listInterventions()).toHaveLength(1);
});

it('enforces nothing after a teardown when no session was ever declared', async () => {
  resetSessions();

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).not.toHaveBeenCalledWith(
    72,
    expect.objectContaining({ type: 'wall' }),
  );
});

it('records the session as a timeline: started, timer ended, wall shown, leave pressed', async () => {
  await arrive();
  await declare('doomscroll');
  await spendForeground(300_000);
  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );
  await routeForTest({ type: 'wall-leave', site: 'instagram-reels' }, 72);

  const entries = await listActivity();
  expect(entries.map((entry) => entry.kind)).toEqual([
    'session-started',
    'budget-spent',
    'wall-shown',
    'leave-pressed',
  ]);
  expect(entries[0].detail).toBe('Doomscrolling — 5 minute budget');
  expect(entries[2].detail).toBe('The 5 minutes you asked for are up');
  for (const entry of entries) {
    expect(entry.site).toBe('instagram-reels');
    expect(typeof entry.at).toBe('number');
  }
});

it('serves the timeline to the options page', async () => {
  await arrive();
  await declare('purposeful');

  const response = await routeForTest({ type: 'get-activity' }, undefined);

  expect(response).toMatchObject({ ok: true, type: 'activity' });
  expect(response.ok && response.type === 'activity' ? response.entries : []).toMatchObject([
    { kind: 'session-started', detail: 'Looking for something — no timer' },
  ]);
});

it('does not hand the budget back when the session crosses local midnight', async () => {
  vi.setSystemTime(new Date('2026-07-31T23:57:00'));
  // A day already partly spent on this feed. Deriving the budget from the daily
  // counter meant this number came back as free time after the rollover.
  await spendForeground(600_000);

  await arrive();
  await declare('doomscroll');
  await spendForeground(120_000);

  // Local midnight: `usage` resets to zero, the declaration does not.
  vi.setSystemTime(new Date('2026-08-01T00:01:00'));
  await spendForeground(180_000);

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, {
    type: 'wall',
    site: 'instagram-reels',
    reason: 'The 5 minutes you asked for are up',
  });
});

it('reports session time to the popup across a rollover', async () => {
  vi.setSystemTime(new Date('2026-07-31T23:58:00'));
  await arrive();
  await declare('doomscroll');
  await spendForeground(60_000);

  vi.setSystemTime(new Date('2026-08-01T00:02:00'));
  await spendForeground(30_000);

  const response = await routeForTest({ type: 'get-status' }, undefined);
  const reels =
    response.ok && response.type === 'status'
      ? response.sites.find((site) => site.site === 'instagram-reels')
      : undefined;

  expect(reels?.session).toEqual({ intent: 'doomscroll', usedMs: 90_000, budgetMinutes: 5 });
});

/**
 * Note on what this does and does not prove: the in-memory storage stub
 * resolves every read and write in a single microtask, so the two handlers
 * below run to completion one after the other rather than interleaving at the
 * declaration read. It pins the observable invariant — two tabs, one crossing —
 * but it does not reproduce the interleaving itself. The per-site lock in
 * `withDeclarationLock` is what makes that invariant hold against a real
 * `chrome.storage.local`, where a read is genuinely asynchronous.
 */
it('records one crossing when two tabs advance the same feed at once', async () => {
  await arrive();
  await declare('doomscroll');
  await spendForeground(300_000);
  // A second tab on the same feed, with the same history as the first.
  await routeForTest({ type: 'arrive', site: 'instagram-reels', entryKind: 'feed-entry' }, 99);
  await spendForeground(60_000, 99);

  // Interleaved, not sequential: both handlers read the declaration before
  // either writes it.
  await Promise.all([
    routeForTest(
      { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
      72,
    ),
    routeForTest(
      { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
      99,
    ),
  ]);

  // Both tabs are walled, but the crossing happened once.
  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(72, expect.objectContaining({ type: 'wall' }));
  expect(chrome.tabs.sendMessage).toHaveBeenCalledWith(99, expect.objectContaining({ type: 'wall' }));
  expect(await listInterventions()).toHaveLength(1);

  const kinds = (await listActivity()).map((entry) => entry.kind);
  expect(kinds.filter((kind) => kind === 'wall-shown')).toHaveLength(1);
  expect(kinds.filter((kind) => kind === 'budget-spent')).toHaveLength(1);
});

it('never classifies a deep-link item after its first advance', async () => {
  classifyMock.mockResolvedValue({
    verdict: 'contradicts',
    confidence: 0.95,
    reason: 'should not see the linked item',
  });
  await arrive('deep-link');
  await declare('purposeful');
  await addEngagementRecords(5);

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(classifyMock).not.toHaveBeenCalled();
  expect(chrome.tabs.sendMessage).not.toHaveBeenCalledWith(
    72,
    expect.objectContaining({ type: 'wall' }),
  );
});
