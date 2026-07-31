import {
  doomscrollButtonLabel,
  INTENT_PROMPT_QUESTION,
  PURPOSEFUL_BUTTON_LABEL,
} from '../shared/constants';
import type { DeclaredIntent, SiteId } from '../shared/types';

const PROMPT_ID = 'local-focus-coach-intent';
const WALL_ID = 'local-focus-coach-wall';
let promptEscapeHandler: ((event: KeyboardEvent) => void) | undefined;

/** Same isolation trick as `overlay.ts`: no shadow root, so tests can query it. */
const RESET = 'all: initial; font-family: system-ui, -apple-system, sans-serif;';

const SURFACE =
  'position: fixed; inset: 0; z-index: 2147483647; display: flex; flex-direction: column;' +
  ' align-items: center; justify-content: center; gap: 18px; padding: 32px;' +
  ' background: rgba(12, 12, 14, 0.94); color: #ffffff; text-align: center;';

export interface IntentPromptOptions {
  site: SiteId;
  budgetMinutes: number;
}

export interface FeedWallOptions {
  site: SiteId;
  reason: string;
}

function removeById(id: string): void {
  document.getElementById(id)?.remove();
}

function dismissIntentPrompt(): void {
  removeById(PROMPT_ID);
  if (promptEscapeHandler) {
    document.removeEventListener('keydown', promptEscapeHandler, true);
    promptEscapeHandler = undefined;
  }
}

export function dismissIntentSurfaces(): void {
  dismissIntentPrompt();
  removeById(WALL_ID);
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
  button.style.cssText = `${RESET} cursor: pointer; padding: 12px 20px; border-radius: 8px; border: 1px solid #d0d0d0; background: #ffffff; color: #111111; font-size: 15px;`;
  button.addEventListener('click', onActivate);
  return button;
}

/**
 * Two buttons, no free text and no duration picker. Free text costs a second of
 * inference before the person can proceed and can be misread; a picker invites
 * negotiating the limit upward at the moment it is least wanted.
 */
export function showIntentPrompt(options: IntentPromptOptions): void {
  dismissIntentPrompt();

  const dialog = document.createElement('div');
  dialog.id = PROMPT_ID;
  dialog.setAttribute('role', 'dialog');
  dialog.setAttribute('aria-modal', 'true');
  dialog.setAttribute('aria-label', 'What are you here for?');
  dialog.style.cssText = `${RESET} ${SURFACE}`;

  const heading = document.createElement('h2');
  heading.textContent = INTENT_PROMPT_QUESTION;
  heading.style.cssText = `${RESET} color: #ffffff; font-size: 26px; font-weight: 650;`;

  const actions = document.createElement('div');
  actions.style.cssText = `${RESET} display: flex; gap: 12px; flex-wrap: wrap; justify-content: center;`;

  function onKeyDown(event: KeyboardEvent): void {
    if (event.key !== 'Escape') return;
    declare('purposeful');
  }

  function declare(intent: DeclaredIntent): void {
    dismissIntentPrompt();
    notifyBackground({ type: 'declare-intent', site: options.site, intent });
  }

  const doomscroll = makeButton(doomscrollButtonLabel(options.budgetMinutes), () =>
    declare('doomscroll'),
  );
  const purposeful = makeButton(PURPOSEFUL_BUTTON_LABEL, () => declare('purposeful'));
  actions.append(doomscroll, purposeful);

  dialog.append(heading, actions);
  // Dismissing without answering is not a way past the question — it resolves
  // to the answer that restricts nothing, and the model keeps watching.
  promptEscapeHandler = onKeyDown;
  document.addEventListener('keydown', promptEscapeHandler, true);
  document.body.append(dialog);
  doomscroll.focus();
}

/**
 * Behavioural, not a network block: a URL block cannot tell a feed swipe from a
 * friend's link, because both are `/reels/<id>`. The wall stops a reflex, not a
 * determined person, and that is what keeps deep links working.
 */
export function showFeedWall(options: FeedWallOptions): void {
  dismissIntentPrompt();
  removeById(WALL_ID);

  const wall = document.createElement('div');
  wall.id = WALL_ID;
  wall.setAttribute('role', 'dialog');
  wall.setAttribute('aria-modal', 'true');
  wall.setAttribute('aria-label', 'Feed paused');
  wall.style.cssText = `${RESET} ${SURFACE}`;

  const heading = document.createElement('h2');
  heading.textContent = 'That is the session you agreed to';
  heading.style.cssText = `${RESET} color: #ffffff; font-size: 26px; font-weight: 650;`;

  const explanation = document.createElement('p');
  explanation.textContent = options.reason;
  explanation.style.cssText = `${RESET} color: #d8d8dc; font-size: 16px; max-width: 460px; line-height: 1.5;`;

  // The only action. No "continue for 5 minutes" here: that button belongs to
  // the score-based pause, where nothing was agreed to in advance.
  // `wall-leave`, not `leave-feed`: a return pause would offer "Continue for 5
  // minutes" on the way back in, which is exactly what the wall is refusing.
  const leave = makeButton('Leave', () => {
    dismissIntentSurfaces();
    notifyBackground({ type: 'wall-leave', site: options.site });
  });

  wall.append(heading, explanation, leave);
  document.body.append(wall);
  leave.focus();
}
