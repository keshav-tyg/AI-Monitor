import { PRIVACY_PROMISE } from '../shared/constants';

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

  root.append(heading, promise, managed, firstTime, unavailable);
}

const mount = document.querySelector('#app');
if (mount) void renderOptions(mount);
