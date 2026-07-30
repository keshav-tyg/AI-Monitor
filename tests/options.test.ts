// @vitest-environment jsdom
import { renderOptions } from '../src/options/main';
import { installChromeApiSpies, installChromeStorageStub } from './chrome-storage';

beforeEach(() => {
  installChromeApiSpies();
  installChromeStorageStub();
});

it('shows the local-only privacy promise and all supported site controls', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);
  expect(document.body.textContent).toContain('Nothing leaves this device');
  expect(document.querySelectorAll('[data-site-rule]')).toHaveLength(3);
});

it('requires an explicit save before an edited rule is activated', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);
  const checkbox = document.querySelector<HTMLInputElement>('[data-site-rule="instagram-reels"] input[type="checkbox"]')!;
  checkbox.click();
  expect(chrome.runtime.sendMessage).not.toHaveBeenCalledWith(expect.objectContaining({ type: 'save-settings' }));
  document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
  expect(chrome.runtime.sendMessage).toHaveBeenCalledWith(expect.objectContaining({ type: 'save-settings' }));
});
