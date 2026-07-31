// @vitest-environment jsdom
import { dismissIntentSurfaces, showFeedWall, showIntentPrompt } from '../src/content/intent-prompt';

function installRuntime(): ReturnType<typeof vi.fn> {
  const sendMessage = vi.fn();
  (globalThis as unknown as { chrome: { runtime: { sendMessage: unknown } } }).chrome = {
    runtime: { sendMessage },
  };
  return sendMessage;
}

function buttonLabelled(label: string): HTMLButtonElement | undefined {
  return Array.from(document.querySelectorAll('button')).find(
    (button) => button.textContent === label,
  );
}

beforeEach(() => {
  document.body.replaceChildren();
});

describe('intent prompt', () => {
  it('offers one doomscroll action and no free text', () => {
    showIntentPrompt({ site: 'instagram-reels', budgetMinutes: 5 });

    expect(document.querySelector('[role="dialog"]')).toHaveTextContent(
      'Hey, what are we doing here?',
    );
    expect(document.querySelectorAll('[role="dialog"] button')).toHaveLength(1);
    expect(document.querySelectorAll('input, textarea, select')).toHaveLength(0);
  });

  it('quotes the budget the rule actually grants', () => {
    showIntentPrompt({ site: 'youtube-shorts', budgetMinutes: 12 });
    expect(buttonLabelled('Doomscrolling — give me 12 minutes')).toBeTruthy();
  });

  it('declares doomscrolling and closes when that answer is chosen', () => {
    const sendMessage = installRuntime();
    showIntentPrompt({ site: 'instagram-reels', budgetMinutes: 5 });

    buttonLabelled('Doomscrolling — give me 5 minutes')?.click();

    expect(sendMessage).toHaveBeenCalledWith({
      type: 'declare-intent',
      site: 'instagram-reels',
      intent: 'doomscroll',
    });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });

  it('does not create a hidden purposeful declaration from Escape', () => {
    const sendMessage = installRuntime();
    showIntentPrompt({ site: 'instagram-reels', budgetMinutes: 5 });

    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(sendMessage).not.toHaveBeenCalled();
    expect(document.querySelector('[role="dialog"]')).toBeTruthy();
  });

  it('does not react to Escape once the session has been started', () => {
    const sendMessage = installRuntime();
    showIntentPrompt({ site: 'instagram-reels', budgetMinutes: 5 });

    buttonLabelled('Doomscrolling — give me 5 minutes')?.click();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(sendMessage).toHaveBeenCalledTimes(1);
  });

  it('stops listening for Escape when a route change dismisses the prompt', () => {
    const sendMessage = installRuntime();
    showIntentPrompt({ site: 'instagram-reels', budgetMinutes: 5 });

    dismissIntentSurfaces();
    document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));

    expect(sendMessage).not.toHaveBeenCalled();
  });
});

describe('feed wall', () => {
  it('offers Leave and nothing else', () => {
    showFeedWall({ site: 'instagram-reels', reason: 'The 5 minutes you asked for are up' });

    const buttons = document.querySelectorAll('[role="dialog"] button');
    expect(buttons).toHaveLength(1);
    expect(buttons[0]).toHaveTextContent('Leave');
    expect(document.querySelector('[role="dialog"]')).toHaveTextContent(
      'The 5 minutes you asked for are up',
    );
  });

  it('asks the service worker to leave the feed', () => {
    const sendMessage = installRuntime();
    showFeedWall({ site: 'youtube-shorts', reason: 'budget spent' });

    buttonLabelled('Leave')?.click();

    expect(sendMessage).toHaveBeenCalledWith({ type: 'wall-leave', site: 'youtube-shorts' });
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });

  it('replaces an open prompt rather than stacking on it', () => {
    showIntentPrompt({ site: 'instagram-reels', budgetMinutes: 5 });
    showFeedWall({ site: 'instagram-reels', reason: 'budget spent' });

    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1);
  });

  it('is removable when the route changes', () => {
    showFeedWall({ site: 'instagram-reels', reason: 'budget spent' });
    dismissIntentSurfaces();
    expect(document.querySelector('[role="dialog"]')).toBeNull();
  });
});
