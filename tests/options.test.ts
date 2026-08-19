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

it('does not send a browser settings-save request', async () => {
  await renderOptions(document.querySelector('#app')!);

  expect(chrome.runtime.sendMessage).not.toHaveBeenCalledWith(
    expect.objectContaining({ type: 'save-settings' }),
  );
});
