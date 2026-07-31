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

it('lets a person configure the doomscroll session budget before saving', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);

  const budget = document.querySelector<HTMLInputElement>(
    '[data-site-rule="instagram-reels"] input[data-field="doomscrollBudgetMinutes"]',
  );
  expect(budget?.value).toBe('5');

  budget!.value = '12';
  budget!.dispatchEvent(new Event('input', { bubbles: true }));
  document.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();

  expect(chrome.runtime.sendMessage).toHaveBeenCalledWith(
    expect.objectContaining({
      type: 'save-settings',
      settings: expect.objectContaining({
        rules: expect.objectContaining({
          'instagram-reels': expect.objectContaining({ doomscrollBudgetMinutes: 12 }),
        }),
      }),
    }),
  );
});
