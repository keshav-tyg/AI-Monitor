import { nextIntervention } from '../src/engine/rules';

const rule = { enabled: true, warningScore: 10, gracePeriodSeconds: 60, interventions: ['notify', 'pause', 'close-tab', 'block'] as const, blockUntil: 'tomorrow' as const };

it('fails open below the score threshold', () => {
  expect(nextIntervention({ rule, score: 9, now: 10_000 })).toEqual({ kind: 'none' });
});

it('escalates after the warning grace period', () => {
  expect(nextIntervention({ rule, score: 12, now: 70_001, warnedAt: 10_000 })).toMatchObject({ kind: 'pause' });
});

it('ignores a legacy daily allowance field when the score is below threshold', () => {
  const legacyRule = { ...rule, dailyAllowanceMinutes: 1 };
  expect(
    nextIntervention({ rule: legacyRule, score: 0, now: 60_000 }),
  ).toEqual({ kind: 'none' });
});
