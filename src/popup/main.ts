import { SUPPORTED_SITES } from '../shared/constants';
import { formatDuration } from '../shared/time';
import type { BackgroundResponse, SiteId, SiteStatus } from '../shared/types';

const POPUP_REFRESH_MS = 1_000;

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

function statusText(site: SiteStatus): string {
  return site.enabled ? ' rule active' : ' rule disabled';
}

function sessionText(site: SiteStatus): string | undefined {
  if (!site.enabled) return undefined;
  if (!site.session) return site.active ? ' · Choose an intent' : undefined;
  if (site.session.intent === 'purposeful') return ' · No time limit';

  const usedMs = site.session.usedMs ?? 0;
  const budgetMinutes = site.session.budgetMinutes ?? 0;
  return ` · ${formatDuration(usedMs)} of ${budgetMinutes} min session`;
}

function syncSessionState(row: Element, site: SiteStatus): void {
  const marker = row.querySelector('[data-session-marker]');
  if (site.active && !marker) {
    const nextMarker = element('em', ' · in a session now');
    nextMarker.dataset['sessionMarker'] = '';
    row.append(nextMarker);
  } else if (!site.active) {
    marker?.remove();
  }

  const text = sessionText(site);
  const timer = row.querySelector('[data-session-status]');
  if (!text) {
    timer?.remove();
  } else if (timer) {
    timer.textContent = text;
  } else {
    const nextTimer = element('span', text);
    nextTimer.dataset['sessionStatus'] = '';
    row.append(nextTimer);
  }
}

/**
 * Updates only the numbers that change. Rebuilding the whole subtree once a
 * second detached the buttons mid-interaction, so a click that raced the
 * refresh landed on a node no longer in the document and was silently lost.
 * Returns false when the existing markup does not match, so the caller can
 * fall back to a full render.
 */
function updateUsageInPlace(root: Element, status: BackgroundResponse | undefined): boolean {
  if (!status || !status.ok || status.type !== 'status') return false;

  for (const site of status.sites) {
    const row = root.querySelector(`[data-site-row="${site.site}"]`);
    const slot = row?.querySelector('[data-status]');
    if (!row || !slot) return false;
    slot.textContent = statusText(site);

    syncSessionState(row, site);
  }
  return status.sites.length > 0;
}

export async function renderPopup(
  root: Element,
  prefetched?: BackgroundResponse,
): Promise<void> {
  // Callers that already asked for status pass it in, so one refresh never
  // costs two round trips to the worker.
  const status = prefetched ?? (await request({ type: 'get-status' }));
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
    row.dataset['siteRow'] = site.site;
    row.append(element('strong', labelFor(site.site)));
    const ruleStatus = element('span', statusText(site));
    ruleStatus.dataset['status'] = '';
    row.append(ruleStatus);
    // Session status appears only while a session actually exists.
    if (site.active) {
      const session = element('em', ' · in a session now');
      session.dataset['sessionMarker'] = '';
      row.append(session);
    }
    const sessionStatus = sessionText(site);
    if (sessionStatus) {
      const timer = element('span', sessionStatus);
      timer.dataset['sessionStatus'] = '';
      row.append(timer);
    }
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

/** Keep the short-lived popup accurate without overlapping status requests. */
export function startPopup(root: Element): () => void {
  let refreshing = false;
  const refresh = async (): Promise<void> => {
    if (refreshing) return;
    refreshing = true;
    try {
      const status = await request({ type: 'get-status' });
      if (!updateUsageInPlace(root, status)) await renderPopup(root, status);
    } finally {
      refreshing = false;
    }
  };

  void refresh();
  const timer = window.setInterval(() => void refresh(), POPUP_REFRESH_MS);
  return () => window.clearInterval(timer);
}

const mount = document.querySelector('#app');
if (mount) startPopup(mount);
