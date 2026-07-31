import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';
import { DEFAULT_SETTINGS } from '../src/shared/constants';
import { saveSettings } from '../src/shared/storage';

type RuntimeListener = (
  message: unknown,
  sender: { tab?: { id?: number } },
  sendResponse: (response: unknown) => void,
) => boolean | void;

async function dispatch(
  listener: RuntimeListener | undefined,
  message: unknown,
  tabId: number,
): Promise<void> {
  await new Promise<void>((resolve) => {
    const keepChannelOpen = listener?.(message, { tab: { id: tabId } }, () => resolve());
    expect(keepChannelOpen).toBe(true);
  });
}

it('closes the tab that asked to leave a pause screen', async () => {
  installChromeStorageStub();
  const spies = installChromeApiSpies();
  vi.resetModules();
  await import('../src/background/service-worker');

  const addListener = chrome.runtime.onMessage.addListener as unknown as ReturnType<typeof vi.fn>;
  const listener = addListener.mock.calls[0]?.[0] as RuntimeListener | undefined;
  expect(listener).toBeDefined();

  await dispatch(listener, { type: 'leave-feed', site: 'instagram-reels' }, 72);

  expect(spies.tabsRemove).toHaveBeenCalledWith(72);
});

it('reopens a left feed directly into its existing pause', async () => {
  installChromeStorageStub();
  const spies = installChromeApiSpies();
  await saveSettings({
    enabled: true,
    rules: {
      ...DEFAULT_SETTINGS.rules,
      'instagram-reels': {
        ...DEFAULT_SETTINGS.rules['instagram-reels'],
        enabled: true,
        gracePeriodSeconds: 60,
        interventions: ['notify', 'pause', 'close-tab'],
      },
    },
  });
  vi.resetModules();
  await import('../src/background/service-worker');

  const addListener = chrome.runtime.onMessage.addListener as unknown as ReturnType<typeof vi.fn>;
  const listener = addListener.mock.calls[0]?.[0] as RuntimeListener | undefined;
  expect(listener).toBeDefined();

  await dispatch(
    listener,
    { type: 'leave-feed', site: 'instagram-reels', reason: 'Focus pause' },
    72,
  );
  await dispatch(
    listener,
    {
      type: 'event',
      event: { site: 'instagram-reels', kind: 'view-entered', at: 120_000 },
    },
    73,
  );

  expect(spies.tabsSendMessage).toHaveBeenCalledWith(73, {
    type: 'pause',
    site: 'instagram-reels',
    reason: 'Focus pause',
    allowContinue: true,
  });
});
