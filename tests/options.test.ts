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

it('spells out what an enabled rule will actually do', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);

  const section = document.querySelector('[data-site-rule="instagram-reels"]')!;
  const budget = section.querySelector<HTMLInputElement>(
    'input[data-field="doomscrollBudgetMinutes"]',
  )!;
  expect(budget.value).toBe('5');

  section.querySelector<HTMLInputElement>('input[type="checkbox"]')!.click();
  expect(section.querySelector('.summary')).toHaveTextContent(
    'A declared doomscroll session gets 5 minutes',
  );
});

it('shows an activity timeline of the declared session', async () => {
  (chrome.runtime.sendMessage as ReturnType<typeof vi.fn>).mockImplementation(
    async (message: { type: string }) => {
      if (message.type !== 'get-activity') return undefined;
      return {
        ok: true,
        type: 'activity',
        entries: [
          {
            id: '1',
            at: Date.parse('2026-07-31T12:00:00'),
            site: 'instagram-reels',
            kind: 'session-started',
            detail: 'Doomscrolling — 5 minute budget',
          },
          {
            id: '2',
            at: Date.parse('2026-07-31T12:05:00'),
            site: 'instagram-reels',
            kind: 'wall-shown',
            detail: 'The 5 minutes you asked for are up',
          },
        ],
      };
    },
  );

  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);

  const rows = document.querySelectorAll('[data-activity-timeline] li');
  expect(rows).toHaveLength(2);
  // Newest first.
  expect(rows[0]).toHaveTextContent('Wall shown');
  expect(rows[0]).toHaveTextContent('Instagram Reels');
  expect(rows[1]).toHaveTextContent('Session started');
});

it('says so plainly when nothing has happened yet', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);

  expect(document.querySelector('[data-activity-timeline]')).toHaveTextContent('Nothing yet');
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

it('does not expose the removed daily allowance control', async () => {
  document.body.innerHTML = '<main id="app"></main>';
  await renderOptions(document.querySelector('#app')!);

  expect(document.querySelector('[data-field="dailyAllowanceMinutes"]')).toBeNull();
});
