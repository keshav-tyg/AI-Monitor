import { SUPPORTED_SITES } from '../shared/constants';
import { formatDuration } from '../shared/time';
import type { BackgroundResponse, SiteId, SiteStatus } from '../shared/types';

async function request(message: unknown): Promise<BackgroundResponse | undefined> {
  const runtime = (
    globalThis as { chrome?: { runtime?: { sendMessage?: (value: unknown) => unknown } } }
  ).chrome?.runtime;
  if (!runtime?.sendMessage) return undefined;
  try {
    return (await runtime.sendMessage(message)) as BackgroundResponse | undefined;
  } catch {
    return undefined;
  }
}

function element<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  text?: string,
): HTMLElementTagNameMap[K] {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  return node;
}

function labelFor(site: SiteId): string {
  return SUPPORTED_SITES.find((entry) => entry.id === site)?.label ?? site;
}

export async function renderPopup(root: Element): Promise<void> {
  const status = await request({ type: 'get-status' });
  root.replaceChildren();

  root.append(element('h1', 'Local Focus Coach'));

  if (!status || !status.ok || status.type !== 'status') {
    root.append(element('p', 'Status unavailable. Open Options to review your rules.'));
    return;
  }

  const state = element('p', status.enabled ? 'Protection is on' : 'Protection is off');
  state.className = status.enabled ? 'on' : 'off';
  root.append(state);

  const list = element('ul');
  for (const site of status.sites as SiteStatus[]) {
    const row = element('li');
    row.append(element('strong', labelFor(site.site)));
    row.append(
      element(
        'span',
        site.enabled
          ? ` ${formatDuration(site.usedMs)} of ${site.allowedMinutes} min used today`
          : ' rule disabled',
      ),
    );
    // Session status appears only while a session actually exists.
    if (site.active) row.append(element('em', ' · in a session now'));
    list.append(row);
  }
  root.append(list);

  const options = element('button', 'Open Options');
  options.type = 'button';
  options.addEventListener('click', () => {
    const runtime = (globalThis as { chrome?: { runtime?: { openOptionsPage?: () => void } } })
      .chrome?.runtime;
    runtime?.openOptionsPage?.();
  });
  root.append(options);
}

const mount = document.querySelector('#app');
if (mount) void renderPopup(mount);
