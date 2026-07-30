import { nextIntervention } from '../src/engine/rules';

const rule = { enabled: true, dailyAllowanceMinutes: 15, warningScore: 10, gracePeriodSeconds: 60, interventions: ['notify', 'pause', 'close-tab', 'block'] as const, blockUntil: 'tomorrow' as const };

it('fails open below the score threshold', () => {
  expect(nextIntervention({ rule, score: 9, usageMs: 0, now: 10_000 })).toEqual({ kind: 'none' });
});

it('escalates after the warning grace period', () => {
  expect(nextIntervention({ rule, score: 12, usageMs: 0, now: 70_001, warnedAt: 10_000 })).toMatchObject({ kind: 'pause' });
});

it('warns first when the daily allowance is exhausted, rather than closing the tab outright', () => {
  expect(nextIntervention({ rule, score: 0, usageMs: 900_000, now: 1_000 })).toMatchObject({ kind: 'notify', reason: 'Daily allowance reached' });
});

it('reaches the configured enforcement once the allowance escalation has run its course', () => {
  expect(
    nextIntervention({ rule, score: 0, usageMs: 900_000, now: 200_000, warnedAt: 10_000, pauseShownAt: 100_000 }),
  ).toMatchObject({ kind: 'close-tab', reason: 'Daily allowance reached' });
});

it('holds the allowance escalation inside the grace period', () => {
  expect(
    nextIntervention({ rule, score: 0, usageMs: 900_000, now: 30_000, warnedAt: 10_000 }),
  ).toEqual({ kind: 'none' });
});
