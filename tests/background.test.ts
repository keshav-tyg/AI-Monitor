import { handleEvent, installBlock, resetSessions } from '../src/background/index';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { saveSettings } from '../src/shared/storage';
import type { InterventionKind } from '../src/shared/types';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
  resetSessions();
});

async function enableX(interventions: InterventionKind[]): Promise<void> {
  await saveSettings({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'x-timeline': { ...DEFAULT_SETTINGS.rules['x-timeline'], enabled: true, interventions },
    },
  });
}

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
