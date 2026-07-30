// @vitest-environment jsdom
import { showPauseOverlay } from '../src/content/overlay';

it('requires a deliberate continue or leave choice', () => {
  showPauseOverlay({ site: 'youtube-shorts', reason: 'test', allowContinue: true });
  expect(document.querySelector('[role="dialog"]')).toHaveTextContent('You’re in a scrolling loop');
  expect(document.querySelectorAll('[role="dialog"] button')).toHaveLength(2);
});
