// @vitest-environment jsdom
import { renderOptions } from '../src/options/main';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
  document.body.innerHTML = '<main id="app"></main>';
});

it('has no editable Focus Rule controls and asks the service worker to open the desktop app', async () => {
  const mount = document.querySelector('#app')!;

  await renderOptions(mount);

  expect(document.querySelectorAll('input, select')).toHaveLength(0);
  document.querySelector<HTMLButtonElement>('[data-open-desktop]')!.click();
  expect(chrome.runtime.sendMessage).toHaveBeenCalledWith({
    type: 'open-dashboard',
    payload: {},
  });
});

it('keeps the local-only privacy promise and explains how to recover when the desktop app is unavailable', async () => {
  await renderOptions(document.querySelector('#app')!);

  expect(document.body).toHaveTextContent('Nothing leaves this device');
  expect(document.body).toHaveTextContent(
    'Focus Rules are managed in Local Focus Coach on this Mac',
  );
  expect(document.querySelector('[data-desktop-unavailable]')).toHaveTextContent(
    'If Local Focus Coach is unavailable',
  );
});

it('explains the extension will do nothing until the desktop app runs', async () => {
  await renderOptions(document.querySelector('#app')!);

  const firstTime = document.querySelector('[data-first-time]');
  expect(firstTime).toHaveTextContent('Nothing happens until the desktop app is running');
  // The reassurance that this is by design, not a bug the user has to debug.
  expect(firstTime).toHaveTextContent('That is not a bug');
});

it('renders the recent activity timeline newest first', async () => {
  (chrome.runtime.sendMessage as ReturnType<typeof vi.fn>).mockImplementation(
    async (message: { type: string }) => {
      if (message.type !== 'get-activity') return undefined;
      return {
        ok: true,
        type: 'activity',
        entries: [
          {
            id: '1',
            at: Date.parse('2026-08-22T00:00:00'),
            site: 'instagram-reels',
            kind: 'session-started',
            detail: 'Doomscrolling — 1 minute budget',
          },
          {
            id: '2',
            at: Date.parse('2026-08-22T00:01:00'),
            site: 'instagram-reels',
            kind: 'wall-shown',
            detail: 'The 1 minute you asked for is up',
          },
        ],
      };
    },
  );

  await renderOptions(document.querySelector('#app')!);

  const rows = document.querySelectorAll('[data-activity-list] li');
  expect(rows).toHaveLength(2);
  expect(rows[0]).toHaveTextContent('Wall shown');
  expect(rows[0]).toHaveTextContent('Instagram Reels');
  expect(rows[1]).toHaveTextContent('Session started');
});

it('shows an empty-state row when nothing has happened yet', async () => {
  await renderOptions(document.querySelector('#app')!);

  expect(document.querySelector('[data-activity-empty]')).toHaveTextContent('Nothing yet');
});

it('does not send a browser settings-save request', async () => {
  await renderOptions(document.querySelector('#app')!);

  expect(chrome.runtime.sendMessage).not.toHaveBeenCalledWith(
    expect.objectContaining({ type: 'save-settings' }),
  );
});
