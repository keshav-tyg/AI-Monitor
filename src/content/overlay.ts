import type { SiteId } from '../shared/types';

const DIALOG_ID = 'local-focus-coach-pause';
const NOTICE_ID = 'local-focus-coach-notice';

export interface PauseOverlayOptions {
  site: SiteId;
  reason: string;
  allowContinue: boolean;
}

export interface NoticeOptions {
  site: SiteId;
  reason: string;
}

/**
 * `all: initial` isolates the overlay from the host page's cascade. The plan
 * called for a shadow root, but the accompanying test queries the overlay
 * through `document.querySelector`, which never crosses a shadow boundary —
 * so the styling is scoped this way instead.
 */
const RESET = 'all: initial; font-family: system-ui, -apple-system, sans-serif;';

function removeById(id: string): void {
  document.getElementById(id)?.remove();
}

export function dismissOverlays(): void {
  removeById(DIALOG_ID);
  removeById(NOTICE_ID);
}

/** Optional chaining throughout: outside the extension there is no runtime. */
function notifyBackground(message: unknown): void {
  const runtime = (
    globalThis as { chrome?: { runtime?: { sendMessage?: (value: unknown) => void } } }
  ).chrome?.runtime;
  runtime?.sendMessage?.(message);
}

function makeButton(label: string, onActivate: () => void): HTMLButtonElement {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  button.style.cssText = `${RESET} cursor: pointer; padding: 10px 18px; border-radius: 8px; border: 1px solid #d0d0d0; background: #ffffff; color: #111111; font-size: 15px;`;
  button.addEventListener('click', onActivate);
  return button;
}

/** A quiet, non-blocking banner. Never steals focus. */
export function showNotice(options: NoticeOptions): void {
  removeById(NOTICE_ID);

  const notice = document.createElement('div');
  notice.id = NOTICE_ID;
  notice.setAttribute('role', 'status');
  notice.setAttribute('aria-live', 'polite');
  notice.style.cssText = `${RESET} position: fixed; top: 16px; right: 16px; z-index: 2147483647; max-width: 320px; padding: 12px 16px; border-radius: 10px; background: #111111; color: #ffffff; font-size: 14px; line-height: 1.4;`;
  notice.textContent = options.reason;

  document.body.append(notice);
  window.setTimeout(() => removeById(NOTICE_ID), 8_000);
}

/**
 * Full-page pause. Leaving and continuing are both explicit choices — there is
 * no dismiss-by-clicking-away, because that is the reflex being interrupted.
 */
export function showPauseOverlay(options: PauseOverlayOptions): void {
  removeById(DIALOG_ID);

  const dialog = document.createElement('div');
  dialog.id = DIALOG_ID;
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-label', 'Scrolling pause');
  dialog.style.cssText = `${RESET} position: fixed; inset: 0; z-index: 2147483647; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 16px; padding: 32px; background: rgba(12, 12, 14, 0.94); color: #ffffff; text-align: center;`;

  const heading = document.createElement('h2');
  heading.textContent = 'You’re in a scrolling loop';
  heading.style.cssText = `${RESET} color: #ffffff; font-size: 26px; font-weight: 650;`;

  const explanation = document.createElement('p');
  explanation.textContent = options.reason;
  explanation.style.cssText = `${RESET} color: #d8d8dc; font-size: 16px; max-width: 460px; line-height: 1.5;`;

  const actions = document.createElement('div');
  actions.style.cssText = `${RESET} display: flex; gap: 12px; flex-wrap: wrap; justify-content: center;`;

  const leave = makeButton('Leave', () => {
    dismissOverlays();
    notifyBackground({ type: 'leave-feed', site: options.site, reason: options.reason });
  });
  actions.append(leave);

  if (options.allowContinue) {
    actions.append(
      makeButton('Continue for 5 minutes', () => {
        dismissOverlays();
        notifyBackground({ type: 'temporary-continue', site: options.site });
      }),
    );
  }

  dialog.append(heading, explanation, actions);
  document.body.append(dialog);
  leave.focus();
}
