// @vitest-environment jsdom
import { showPauseOverlay } from '../src/content/overlay';

beforeEach(() => {
  document.body.replaceChildren();
});

it('requires a deliberate continue or leave choice', () => {
  showPauseOverlay({ site: 'youtube-shorts', reason: 'test', allowContinue: true });
  expect(document.querySelector('[role="dialog"]')).toHaveTextContent('You’re in a scrolling loop');
  expect(document.querySelectorAll('[role="dialog"] button')).toHaveLength(2);
});

it('asks the service worker to leave the current feed when Leave is clicked', () => {
  const sendMessage = vi.fn();
  (globalThis as unknown as { chrome: { runtime: { sendMessage: unknown } } }).chrome = {
    runtime: { sendMessage },
  };
  showPauseOverlay({ site: 'instagram-reels', reason: 'test', allowContinue: true });

  const leave = Array.from(document.querySelectorAll('button')).find(
    (button) => button.textContent === 'Leave',
  );
  leave?.click();

  expect(sendMessage).toHaveBeenCalledWith({ type: 'leave-feed', site: 'instagram-reels' });
});
