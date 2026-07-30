import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

type RuntimeListener = (
  message: unknown,
  sender: { tab?: { id?: number } },
  sendResponse: (response: unknown) => void,
) => boolean | void;

it('closes the tab that asked to leave a pause screen', async () => {
  installChromeStorageStub();
  const spies = installChromeApiSpies();
  vi.resetModules();
  await import('../src/background/service-worker');

  const addListener = chrome.runtime.onMessage.addListener as unknown as ReturnType<typeof vi.fn>;
  const listener = addListener.mock.calls[0]?.[0] as RuntimeListener | undefined;
  expect(listener).toBeDefined();

  await new Promise<void>((resolve) => {
    const keepChannelOpen = listener?.(
      { type: 'leave-feed', site: 'instagram-reels' },
      { tab: { id: 72 } },
      () => resolve(),
    );
    expect(keepChannelOpen).toBe(true);
  });

  expect(spies.tabsRemove).toHaveBeenCalledWith(72);
});
