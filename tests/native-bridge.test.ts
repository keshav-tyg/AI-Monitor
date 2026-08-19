import {
  requestOpenDashboard,
  resetNativeBridgeForTest,
  startNativeBridge,
  stopNativeBridge,
} from '../src/background/native-bridge';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import type { Settings } from '../src/shared/types';
import {
  cacheDesktopSettingsForTest,
  installChromeApiSpies,
  installChromeStorageStub,
  type ChromeApiSpies,
} from './chrome-storage';

const HELLO = { version: 1, type: 'extension.hello', payload: {} };
const HEARTBEAT = { version: 1, type: 'extension.heartbeat', payload: {} };
const SYNC_ZERO = {
  version: 1,
  type: 'extension.focusSettings.sync',
  payload: { appliedRevision: 0 },
};
const OPEN_DASHBOARD = { version: 1, type: 'extension.openDashboard', payload: {} };

const enabledSettings: Settings = {
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

const nativeEnabledSettings = {
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
};

let spies: ChromeApiSpies;

beforeEach(() => {
  resetNativeBridgeForTest();
  vi.useFakeTimers();
  spies = installChromeApiSpies();
  installChromeStorageStub();
});

afterEach(() => {
  resetNativeBridgeForTest();
  vi.useRealTimers();
});

it('connects a development build and requests desktop settings on startup', async () => {
  startNativeBridge();
  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);

  expect(spies.connectNative).toHaveBeenCalledTimes(1);
  expect(spies.connectNative).toHaveBeenCalledWith('com.localfocuscoach.strict_mode_dev');
  expect(spies.nativePorts[0]?.postMessage.mock.calls).toEqual([[HELLO], [SYNC_ZERO]]);
});

it('connects a keyed production build only to the production strict host', () => {
  spies.getManifest.mockReturnValue({ key: 'release-public-key' });

  startNativeBridge();

  expect(spies.connectNative).toHaveBeenCalledExactlyOnceWith(
    'com.localfocuscoach.strict_mode',
  );
});

it('offers legacy settings only on a first-run sync without a desktop cache', async () => {
  await chrome.storage.local.set({ settings: enabledSettings });

  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);

  expect(spies.nativePorts[0]?.postMessage).toHaveBeenLastCalledWith({
    version: 1,
    type: 'extension.focusSettings.sync',
    payload: { appliedRevision: 0, legacySettings: nativeEnabledSettings },
  });
});

it('never offers legacy settings after a desktop snapshot has been applied', async () => {
  await chrome.storage.local.set({ settings: enabledSettings });
  await cacheDesktopSettingsForTest(enabledSettings, 3);

  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);

  expect(spies.nativePorts[0]?.postMessage).toHaveBeenLastCalledWith({
    version: 1,
    type: 'extension.focusSettings.sync',
    payload: { appliedRevision: 3 },
  });
});

it('still requests safe desktop defaults when local storage is unavailable', async () => {
  vi.spyOn(chrome.storage.local, 'get').mockRejectedValue(new Error('storage unavailable'));

  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);

  expect(spies.nativePorts[0]?.postMessage.mock.calls).toEqual([[HELLO], [SYNC_ZERO]]);
});

it('sends only health and settings-sync envelopes every five seconds', async () => {
  startNativeBridge();
  const port = spies.nativePorts[0];
  await vi.advanceTimersByTimeAsync(0);

  await vi.advanceTimersByTimeAsync(4_999);
  expect(port?.postMessage).toHaveBeenCalledTimes(2);

  await vi.advanceTimersByTimeAsync(1);
  expect(port?.postMessage.mock.calls).toEqual([
    [HELLO],
    [SYNC_ZERO],
    [HEARTBEAT],
    [SYNC_ZERO],
  ]);
});

it('reconnects with capped exponential delays after repeated disconnects', async () => {
  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);

  const delays = [1_000, 2_000, 4_000, 8_000, 15_000, 15_000];
  for (const [index, delay] of delays.entries()) {
    spies.nativePorts[index]?.emitDisconnect();
    await vi.advanceTimersByTimeAsync(delay - 1);
    expect(spies.connectNative).toHaveBeenCalledTimes(index + 1);
    await vi.advanceTimersByTimeAsync(1);
    expect(spies.connectNative).toHaveBeenCalledTimes(index + 2);
    expect(spies.nativePorts[index + 1]?.postMessage.mock.calls).toEqual([[HELLO], [SYNC_ZERO]]);
  }
});

it('caches and delivers only a valid newer service settings message', async () => {
  const onSettingsSnapshot = vi.fn();
  startNativeBridge(onSettingsSnapshot);
  const port = spies.nativePorts[0];
  await vi.advanceTimersByTimeAsync(0);

  port?.emitMessage({
    version: 1,
    type: 'service.focusSettings',
    payload: { revision: 2, settings: nativeEnabledSettings, chromeAppliedRevision: 0 },
  });
  await vi.advanceTimersByTimeAsync(0);

  expect(onSettingsSnapshot).toHaveBeenCalledExactlyOnceWith({
    revision: 2,
    settings: enabledSettings,
  });

  await vi.advanceTimersByTimeAsync(5_000);
  expect(port?.postMessage).toHaveBeenLastCalledWith({
    version: 1,
    type: 'extension.focusSettings.sync',
    payload: { appliedRevision: 2 },
  });
});

it('ignores malformed and stale messages received from the native host', async () => {
  const onSettingsSnapshot = vi.fn();
  startNativeBridge(onSettingsSnapshot);
  startNativeBridge();
  const port = spies.nativePorts[0];
  await vi.advanceTimersByTimeAsync(0);

  port?.emitMessage({
    version: 1,
    type: 'service.focusSettings',
    payload: { revision: 2, settings: nativeEnabledSettings, chromeAppliedRevision: 0 },
  });
  await vi.advanceTimersByTimeAsync(0);
  port?.emitMessage({
    version: 1,
    type: 'service.focusSettings',
    payload: { revision: 1, settings: nativeEnabledSettings, chromeAppliedRevision: 0 },
  });
  port?.emitMessage({
    version: 1,
    type: 'service.focusSettings',
    payload: {
      revision: 3,
      settings: { ...nativeEnabledSettings, page: { title: 'private' } },
      chromeAppliedRevision: 2,
    },
  });
  port?.emitMessage({ type: 'page.request', payload: { text: 'send me' } });
  await vi.advanceTimersByTimeAsync(0);

  expect(onSettingsSnapshot).toHaveBeenCalledTimes(1);
  expect(spies.connectNative).toHaveBeenCalledTimes(1);
  expect(port?.postMessage.mock.calls).toEqual([[HELLO], [SYNC_ZERO]]);
});

it('sends an exact dashboard-open request only while connected', async () => {
  requestOpenDashboard();
  startNativeBridge();
  const port = spies.nativePorts[0];
  await vi.advanceTimersByTimeAsync(0);

  requestOpenDashboard();
  expect(port?.postMessage).toHaveBeenLastCalledWith(OPEN_DASHBOARD);

  port?.emitDisconnect();
  requestOpenDashboard();
  expect(port?.postMessage.mock.calls).toEqual([[HELLO], [SYNC_ZERO], [OPEN_DASHBOARD]]);
});

it('stopping clears heartbeats and disconnects the open port', async () => {
  startNativeBridge();
  const port = spies.nativePorts[0];
  await vi.advanceTimersByTimeAsync(0);

  stopNativeBridge();
  await vi.advanceTimersByTimeAsync(60_000);

  expect(port?.disconnect).toHaveBeenCalledTimes(1);
  expect(port?.postMessage.mock.calls).toEqual([[HELLO], [SYNC_ZERO]]);
  expect(spies.connectNative).toHaveBeenCalledTimes(1);
});

it('resetting clears a pending reconnect and restores the initial retry delay', async () => {
  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);
  spies.nativePorts[0]?.emitDisconnect();
  resetNativeBridgeForTest();
  await vi.advanceTimersByTimeAsync(60_000);
  expect(spies.connectNative).toHaveBeenCalledTimes(1);

  startNativeBridge();
  await vi.advanceTimersByTimeAsync(0);
  spies.nativePorts[1]?.emitDisconnect();
  await vi.advanceTimersByTimeAsync(999);
  expect(spies.connectNative).toHaveBeenCalledTimes(2);
  await vi.advanceTimersByTimeAsync(1);
  expect(spies.connectNative).toHaveBeenCalledTimes(3);
});
