const { classifyMock } = vi.hoisted(() => ({ classifyMock: vi.fn() }));

vi.mock('../src/background/classifier-client', () => ({
  classify: classifyMock,
  resetClassifierClient: vi.fn(),
}));

import { resetSessions, routeForTest } from '../src/background/service-worker';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { addUsage, listInterventions, saveSettings } from '../src/shared/storage';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

beforeEach(async () => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-07-31T12:00:00'));
  installChromeStorageStub();
  installChromeApiSpies();
  resetSessions();
  classifyMock.mockReset();
  classifyMock.mockResolvedValue(undefined);
  await saveSettings({
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

it('walls a spent doomscroll budget once and records one intervention', async () => {
  await arrive();
  await declare('doomscroll');
  await addUsage('instagram-reels', 300_000, Date.now());

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

it('does not let the legacy allowance ladder override a purposeful declaration', async () => {
  await saveSettings({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': {
        ...DEFAULT_SETTINGS.rules['instagram-reels'],
        enabled: true,
        dailyAllowanceMinutes: 1,
        interventions: ['pause'],
      },
    },
  });
  await arrive();
  await declare('purposeful');
  await addUsage('instagram-reels', 60_000, Date.now());
  (chrome.tabs.sendMessage as unknown as ReturnType<typeof vi.fn>).mockClear();

  await routeForTest(
    { type: 'event', event: { site: 'instagram-reels', kind: 'content-advance', at: Date.now() } },
    72,
  );

  expect(chrome.tabs.sendMessage).not.toHaveBeenCalledWith(
    72,
    expect.objectContaining({ type: 'pause' }),
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
  await saveSettings({
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
