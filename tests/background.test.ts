import {
  handleEvent,
  installBlock,
  resetSessions,
  routeForTest,
} from '../src/background/service-worker';
import { resetNativeBridgeForTest } from '../src/background/native-bridge';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import type { InterventionKind } from '../src/shared/types';
import {
  cacheDesktopSettingsForTest,
  installChromeApiSpies,
  installChromeStorageStub,
} from './chrome-storage';

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
  resetSessions();
});

async function enableX(interventions: InterventionKind[]): Promise<void> {
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'x-timeline': { ...DEFAULT_SETTINGS.rules['x-timeline'], enabled: true, interventions },
    },
  });
}

it('continues to enforce cached settings while the native bridge is disconnected', async () => {
  await cacheDesktopSettingsForTest({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'x-timeline': {
        ...DEFAULT_SETTINGS.rules['x-timeline'],
        enabled: true,
        warningScore: 1,
        interventions: ['notify'],
      },
    },
  }, 2);
  resetNativeBridgeForTest();

  await handleEvent(7, { site: 'x-timeline', kind: 'view-entered', at: 0 }, 0);
  let decision = await handleEvent(7, { site: 'x-timeline', kind: 'scroll', at: 0 }, 0);
  for (let index = 1; index <= 6; index += 1) {
    decision = await handleEvent(
      7,
      { site: 'x-timeline', kind: 'scroll', at: index * 20_000 },
      index * 20_000,
    );
  }
  expect(decision).toMatchObject({ kind: 'notify' });
});

it('does not expose a browser-owned settings save route', async () => {
  await expect(
    routeForTest({ type: 'save-settings', settings: DEFAULT_SETTINGS } as never, undefined),
  ).resolves.toEqual({ ok: false, error: 'Unknown request' });
});

it('does not enforce when a page event is unsupported or the rule is disabled', async () => {
  await expect(handleEvent(1, { site: 'x-timeline', kind: 'scroll', at: 10_000 }, 10_000)).resolves.toEqual({ kind: 'none' });
  expect(chrome.tabs.remove).not.toHaveBeenCalled();
  expect(chrome.declarativeNetRequest.updateDynamicRules).not.toHaveBeenCalled();
});

it('closes only the originating tab for an enabled close-tab decision', async () => {
  await enableX(['notify', 'pause', 'close-tab', 'block']);

  await handleEvent(72, { site: 'x-timeline', kind: 'view-entered', at: 0 }, 0);
  for (let i = 1; i <= 20; i += 1) await handleEvent(72, { site: 'x-timeline', kind: 'content-advance', at: i * 20_000 }, i * 20_000);
  expect(chrome.tabs.remove).toHaveBeenCalledWith(72);
});

it('never enforces on the event that says the person left the feed', async () => {
  await enableX(['notify', 'pause', 'close-tab', 'block']);

  await handleEvent(72, { site: 'x-timeline', kind: 'view-entered', at: 0 }, 0);
  // Escalate as far as the pause step, so the next decision would be close-tab.
  for (let i = 1; i <= 13; i += 1) await handleEvent(72, { site: 'x-timeline', kind: 'content-advance', at: i * 20_000 }, i * 20_000);
  expect(chrome.tabs.remove).not.toHaveBeenCalled();

  // Leaving must not be the thing that closes the tab.
  await expect(
    handleEvent(72, { site: 'x-timeline', kind: 'view-left', at: 320_000 }, 320_000),
  ).resolves.toEqual({ kind: 'none' });
  expect(chrome.tabs.remove).not.toHaveBeenCalled();
});

it('keeps escalating when pause is the first configured intervention', async () => {
  await enableX(['pause', 'close-tab']);

  await handleEvent(72, { site: 'x-timeline', kind: 'view-entered', at: 0 }, 0);
  for (let i = 1; i <= 20; i += 1) await handleEvent(72, { site: 'x-timeline', kind: 'content-advance', at: i * 20_000 }, i * 20_000);
  expect(chrome.tabs.remove).toHaveBeenCalledWith(72);
});

it('schedules expiry when installing a block, so it cannot outlive midnight', async () => {
  await installBlock('youtube-shorts', 1_753_938_000_000);
  expect(chrome.alarms.create).toHaveBeenCalledWith(
    'block-expiry-youtube-shorts',
    { when: 1_753_938_000_000 },
  );
});
