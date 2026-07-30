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
  /** Plain-language explanation from `sessionReason`. */
  reason?: string;
}

export const ALLOWANCE_REASON = 'Daily allowance reached';

/**
 * Preference order once the daily allowance is spent.
 *
 * `close-tab` outranks `block` here because the plan's own test requires it:
 * a rule configured with every intervention must close the offending tab.
 * The background worker installs the until-tomorrow block alongside this when
 * `block` is also configured, so the stronger action is never lost.
 */
const ALLOWANCE_PRECEDENCE: readonly InterventionKind[] = ['close-tab', 'block', 'pause', 'notify'];

/**
 * Pure decision function. Every uncertain path returns `none` so that an
 * unknown state can never escalate into closing a tab or blocking a site.
 */
export function nextIntervention(input: RuleInput): InterventionDecision {
  const { rule, score, usageMs, now, warnedAt, reason } = input;

  if (!rule.enabled) return { kind: 'none' };
  if (rule.interventions.length === 0) return { kind: 'none' };

  const allowanceMs = rule.dailyAllowanceMinutes * 60_000;
  if (usageMs >= allowanceMs) {
    const action = ALLOWANCE_PRECEDENCE.find((kind) => rule.interventions.includes(kind));
    return action ? { kind: action, reason: ALLOWANCE_REASON } : { kind: 'none' };
  }

  if (score < rule.warningScore) return { kind: 'none' };

  const explanation = reason ?? 'Sustained passive scrolling detected';
  const [first, second] = rule.interventions;

  // First crossing of the threshold: the gentlest configured action only.
  if (warnedAt === undefined) {
    return first ? { kind: first, reason: explanation } : { kind: 'none' };
  }

  // Escalate only once the person has had the full grace period to respond.
  if (now - warnedAt >= rule.gracePeriodSeconds * 1_000) {
    return second ? { kind: second, reason: explanation } : { kind: 'none' };
  }

  return { kind: 'none' };
}
