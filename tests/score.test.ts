import { applyEvent, initialSession } from '../src/engine/score';

const start = { site: 'youtube-shorts' as const, kind: 'view-entered' as const, at: 1_000 };

it('does not score a one-off short', () => {
  const state = applyEvent(initialSession(start), { ...start, kind: 'content-advance', at: 20_000 });
  expect(state.score).toBeLessThan(10);
});

it('raises confidence for sustained passive advances and scrolling', () => {
  let state = initialSession(start);
  for (let i = 1; i <= 8; i += 1) state = applyEvent(state, { ...start, kind: 'content-advance', at: i * 20_000 });
  for (let i = 9; i <= 14; i += 1) state = applyEvent(state, { ...start, kind: 'scroll', at: i * 20_000 });
  expect(state.score).toBeGreaterThanOrEqual(10);
});

it('materially reduces confidence after a purposeful action', () => {
  const state = applyEvent({ ...initialSession(start), score: 12 }, { ...start, kind: 'purposeful-action', detail: 'comment', at: 10_000 });
  expect(state.score).toBeLessThan(10);
});
