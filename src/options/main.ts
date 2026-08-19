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
    element('p', 'Use the desktop app to review or change your Focus Rules.'),
  );

  const open = element('button', 'Open Local Focus Coach');
  open.type = 'button';
  open.dataset['openDesktop'] = '';
  open.addEventListener('click', () => {
    requestOpenDashboard();
  });
  managed.append(open);

  const unavailable = element(
    'p',
    'If Local Focus Coach is unavailable, make sure it is installed and running on this Mac, then try again.',
  );
  unavailable.className = 'unavailable';
  unavailable.dataset['desktopUnavailable'] = '';

  root.append(heading, promise, managed, unavailable);
}

const mount = document.querySelector('#app');
if (mount) void renderOptions(mount);
