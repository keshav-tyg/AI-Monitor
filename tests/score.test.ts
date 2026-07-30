import { applyEvent, initialSession } from '../src/engine/score';
import type { NormalizedEvent } from '../src/shared/types';

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

it('does not count a timekeeping heartbeat as scrolling behavior', () => {
  const session = {
    ...initialSession(start),
    score: 5,
    consecutiveAdvances: 3,
    continuousScrolls: 4,
  };
  const heartbeat = {
    site: 'youtube-shorts',
    kind: 'heartbeat',
    at: 20_000,
  } as unknown as NormalizedEvent;

  const state = applyEvent(session, heartbeat);

  expect(state.score).toBe(5);
  expect(state.consecutiveAdvances).toBe(3);
  expect(state.continuousScrolls).toBe(4);
});
