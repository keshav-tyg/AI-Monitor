import { DEFAULT_SETTINGS } from '../src/shared/constants';
import {
  acceptDesktopSnapshot,
  loadDesktopSettingsSnapshot,
  parseDesktopSettingsSnapshot,
  saveDesktopSettingsSnapshot,
  toDesktopSettingsPayload,
} from '../src/shared/desktop-settings';
import type { Settings } from '../src/shared/types';
import { installChromeStorageStub } from './chrome-storage';

const validSettings: Settings = {
  enabled: true,
  rules: {
    ...DEFAULT_SETTINGS.rules,
    'instagram-reels': {
      enabled: true,
      warningScore: 8,
      gracePeriodSeconds: 30,
      doomscrollBudgetMinutes: 4,
      interventions: ['notify', 'pause'],
      blockUntil: 'tomorrow',
    },
  },
};

beforeEach(() => {
  installChromeStorageStub();
});

it('keeps the last valid cached desktop snapshot when a newer payload is invalid', async () => {
  await saveDesktopSettingsSnapshot({ revision: 4, settings: validSettings });

  expect(await acceptDesktopSnapshot({ revision: 5, settings: { rules: {} } })).toBe(false);
  expect(await loadDesktopSettingsSnapshot()).toEqual({ revision: 4, settings: validSettings });
});

it('accepts only a strictly newer validated desktop snapshot', async () => {
  await saveDesktopSettingsSnapshot({ revision: 4, settings: validSettings });

  expect(await acceptDesktopSnapshot({ revision: 4, settings: validSettings })).toBe(false);
  expect(await acceptDesktopSnapshot({ revision: 3, settings: validSettings })).toBe(false);
  expect(await acceptDesktopSnapshot({ revision: 5, settings: validSettings })).toBe(true);
  expect((await loadDesktopSettingsSnapshot())?.revision).toBe(5);
});

it('treats a malformed persisted cache as absent', async () => {
  await chrome.storage.local.set({
    'desktop-settings-snapshot': {
      revision: 9,
      settings: { ...validSettings, tracking: { pages: ['https://example.test/private'] } },
    },
  });

  expect(await loadDesktopSettingsSnapshot()).toBeUndefined();
});

it.each([
  ['revision zero', { revision: 0, settings: validSettings }],
  [
    'an unsupported site',
    {
      revision: 1,
      settings: {
        ...validSettings,
        rules: { ...validSettings.rules, tiktok: validSettings.rules['instagram-reels'] },
      },
    },
  ],
  [
    'an out-of-range number',
    {
      revision: 1,
      settings: {
        ...validSettings,
        rules: {
          ...validSettings.rules,
          'instagram-reels': { ...validSettings.rules['instagram-reels'], warningScore: 51 },
        },
      },
    },
  ],
  [
    'duplicate interventions',
    {
      revision: 1,
      settings: {
        ...validSettings,
        rules: {
          ...validSettings.rules,
          'instagram-reels': {
            ...validSettings.rules['instagram-reels'],
            interventions: ['notify', 'notify'],
          },
        },
      },
    },
  ],
  [
    'an enabled rule without an intervention',
    {
      revision: 1,
      settings: {
        ...validSettings,
        rules: {
          ...validSettings.rules,
          'instagram-reels': { ...validSettings.rules['instagram-reels'], interventions: [] },
        },
      },
    },
  ],
])('rejects a desktop snapshot containing %s', (_label, snapshot) => {
  expect(parseDesktopSettingsSnapshot(snapshot)).toBeUndefined();
});

it('projects legacy extension settings to the exact desktop protocol shape', () => {
  expect(toDesktopSettingsPayload(validSettings)).toEqual({
    enabled: true,
    rules: {
      'instagram-reels': {
        enabled: true,
        warningScore: 8,
        gracePeriodSeconds: 30,
        doomscrollBudgetMinutes: 4,
        interventions: ['notify', 'pause'],
      },
      'x-timeline': {
        enabled: false,
        warningScore: 10,
        gracePeriodSeconds: 60,
        doomscrollBudgetMinutes: 5,
        interventions: ['notify', 'pause', 'close-tab', 'block'],
      },
      'youtube-shorts': {
        enabled: false,
        warningScore: 10,
        gracePeriodSeconds: 60,
        doomscrollBudgetMinutes: 5,
        interventions: ['notify', 'pause', 'close-tab', 'block'],
      },
    },
  });
});
