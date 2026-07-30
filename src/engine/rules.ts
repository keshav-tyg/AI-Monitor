import type { InterventionDecision, InterventionKind } from '../shared/types';

/** Accepts a `SiteRule` or any readonly-literal equivalent. */
export interface RuleLike {
  enabled: boolean;
  dailyAllowanceMinutes: number;
  warningScore: number;
  gracePeriodSeconds: number;
  interventions: readonly InterventionKind[];
  blockUntil: 'tomorrow';
}

export interface RuleInput {
  rule: RuleLike;
  score: number;
  usageMs: number;
  now: number;
  /** When the notify step fired for this session, if it has. */
  warnedAt?: number;
  /** When the pause step fired for this session, if it has. */
  pauseShownAt?: number;
  /** Plain-language explanation from `sessionReason`. */
  reason?: string;
}

export const ALLOWANCE_REASON = 'Daily allowance reached';

/**
 * Pure decision function. Every uncertain path returns `none` so that an
 * unknown state can never escalate into closing a tab or blocking a site.
 */
export function nextIntervention(input: RuleInput): InterventionDecision {
  const { rule, score, usageMs, now, warnedAt, pauseShownAt, reason } = input;

  if (!rule.enabled) return { kind: 'none' };
  if (rule.interventions.length === 0) return { kind: 'none' };

  const allowanceMs = rule.dailyAllowanceMinutes * 60_000;
  const allowanceExhausted = usageMs >= allowanceMs;

  // Either trigger can open the ladder, and both walk the same steps. Spending
  // an allowance used to jump straight to the harshest configured action, so a
  // tab could vanish with no warning at all.
  if (!allowanceExhausted && score < rule.warningScore) return { kind: 'none' };

  const explanation = allowanceExhausted
    ? ALLOWANCE_REASON
    : (reason ?? 'Sustained passive scrolling detected');
  const graceMs = rule.gracePeriodSeconds * 1_000;
  const [first, second, third] = rule.interventions;

  // First crossing of the threshold: the gentlest configured action only.
  if (warnedAt === undefined) {
    return first ? { kind: first, reason: explanation } : { kind: 'none' };
  }

  // Each further step costs another full grace period, so someone who stops
  // scrolling never sees the next one.
  if (pauseShownAt === undefined) {
    if (now - warnedAt < graceMs) return { kind: 'none' };
    return second ? { kind: second, reason: explanation } : { kind: 'none' };
  }

  if (now - pauseShownAt < graceMs) return { kind: 'none' };
  return third ? { kind: third, reason: explanation } : { kind: 'none' };
}
