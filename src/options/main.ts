import { SUPPORTED_SITES, PRIVACY_PROMISE } from '../shared/constants';
import type { ActivityEntry, ActivityKind, SiteId } from '../shared/types';

const ACTIVITY_LABELS: Record<ActivityKind, string> = {
  'session-started': 'Session started',
  'budget-spent': 'Timer ended',
  'wall-shown': 'Wall shown',
  'leave-pressed': 'Leave pressed',
};

function siteLabel(site: SiteId): string {
  return SUPPORTED_SITES.find((entry) => entry.id === site)?.label ?? site;
}

async function fetchActivity(): Promise<ActivityEntry[]> {
  const runtime = (
    globalThis as { chrome?: { runtime?: { sendMessage?: (value: unknown) => Promise<unknown> } } }
  ).chrome?.runtime;
  if (!runtime?.sendMessage) return [];
  try {
    const response = (await runtime.sendMessage({ type: 'get-activity' })) as
      | { ok?: boolean; type?: string; entries?: ActivityEntry[] }
      | undefined;
    return response?.ok && response.type === 'activity' ? (response.entries ?? []) : [];
  } catch {
    // The launcher stays useful even when the worker has torn down.
    return [];
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

function requestOpenDashboard(): void {
  const runtime = (
    globalThis as { chrome?: { runtime?: { sendMessage?: (value: unknown) => Promise<unknown> } } }
  ).chrome?.runtime;
  if (!runtime?.sendMessage) return;
  try {
    void runtime.sendMessage({ type: 'open-dashboard', payload: {} }).catch(() => undefined);
  } catch {
    // The managed page remains informative when the extension worker is unavailable.
  }
}

export async function renderOptions(root: Element): Promise<void> {
  root.replaceChildren();

  const heading = element('h1', 'Local Focus Coach');
  const promise = element('p', PRIVACY_PROMISE);
  promise.className = 'privacy';

  const managed = element('section');
  managed.className = 'managed';
  managed.append(
    element('h2', 'Focus Rules are managed in Local Focus Coach on this Mac'),
    element(
      'p',
      'This page is a launcher. Every rule — which sites are watched, the ' +
        'session budget, the intervention ladder — lives in the desktop app.',
    ),
  );

  const open = element('button', 'Open Local Focus Coach');
  open.type = 'button';
  open.dataset['openDesktop'] = '';
  open.addEventListener('click', () => {
    requestOpenDashboard();
  });
  managed.append(open);

  const firstTime = element('section');
  firstTime.className = 'first-time';
  firstTime.dataset['firstTime'] = '';
  firstTime.append(
    element('h3', 'Nothing happens until the desktop app is running'),
    element(
      'p',
      'That is not a bug. The extension enforces nothing on its own — it ' +
        'reads your rules from the app. Open it, enable a rule, set a budget, ' +
        'then visit one of the watched feeds.',
    ),
  );

  const unavailable = element(
    'p',
    'If Local Focus Coach is unavailable, the app is not running or the ' +
      'native-messaging host is not registered. Launch the app from ' +
      'Applications (right-click → Open the first time). If it still cannot ' +
      'be reached, see the troubleshooting section of the download page.',
  );
  unavailable.className = 'unavailable';
  unavailable.dataset['desktopUnavailable'] = '';

  const timeline = element('section');
  timeline.className = 'activity';
  timeline.dataset['activityTimeline'] = '';
  timeline.append(
    element('h2', 'Recent activity'),
    element(
      'p',
      'The last two hundred moments this extension recorded on this device. ' +
        'Details are built from your own settings — no page content is stored.',
    ),
  );
  const list = element('ul');
  list.dataset['activityList'] = '';
  timeline.append(list);

  root.append(heading, promise, managed, firstTime, unavailable, timeline);

  const entries = await fetchActivity();
  if (entries.length === 0) {
    const empty = element('li', 'Nothing yet — start a session on a watched feed.');
    empty.dataset['activityEmpty'] = '';
    list.append(empty);
    return;
  }

  // Newest first: the thing that just happened is the thing being looked for.
  for (const entry of [...entries].reverse()) {
    const row = element('li');
    row.dataset['activityKind'] = entry.kind;
    const when = new Date(entry.at).toLocaleString();
    row.append(
      element('time', when),
      element('span', ` — ${siteLabel(entry.site)} — ${ACTIVITY_LABELS[entry.kind]} — ${entry.detail}`),
    );
    list.append(row);
  }
}

const mount = document.querySelector('#app');
if (mount) void renderOptions(mount);
