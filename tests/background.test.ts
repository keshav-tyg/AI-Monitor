import { handleEvent, resetSessions } from '../src/background/index';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { saveSettings } from '../src/shared/storage';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
  resetSessions();
});

it('does not enforce when a page event is unsupported or the rule is disabled', async () => {
  await expect(handleEvent(1, { site: 'x-timeline', kind: 'scroll', at: 10_000 }, 10_000)).resolves.toEqual({ kind: 'none' });
  expect(chrome.tabs.remove).not.toHaveBeenCalled();
  expect(chrome.declarativeNetRequest.updateDynamicRules).not.toHaveBeenCalled();
});

it('closes only the originating tab for an enabled close-tab decision', async () => {
  // Install an enabled X rule and feed enough normalized events to pass the configured threshold.
  await saveSettings({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'x-timeline': { ...DEFAULT_SETTINGS.rules['x-timeline'], enabled: true },
    },
  });

  await handleEvent(72, { site: 'x-timeline', kind: 'view-entered', at: 0 }, 0);
  for (let i = 1; i <= 20; i += 1) await handleEvent(72, { site: 'x-timeline', kind: 'content-advance', at: i * 20_000 }, i * 20_000);
  expect(chrome.tabs.remove).toHaveBeenCalledWith(72);
});
