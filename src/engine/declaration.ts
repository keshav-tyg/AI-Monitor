import { budgetSpentReason, CLASSIFIER, DECLARATION, WALL_HELD_REASON } from '../shared/constants';
import type {
  ClassifierResult,
  DeclarationEntry,
  DeclaredIntent,
  EntryKind,
} from '../shared/types';

/** Per-tab arrival state. Transient — it dies with the service worker. */
export interface SessionArrival {
  entryKind: EntryKind;
  /** Advances past the item the session arrived on. */
  advancesSinceEntry: number;
}

export type DeclarationAction =
  | { kind: 'none' }
  | { kind: 'prompt' }
  | { kind: 'wall'; reason: string };

export interface DeclarationInput {
  arrival: SessionArrival;
  declaration?: DeclarationEntry;
  budgetMs: number;
  budgetMinutes: number;
  /** The model's answer, when one was obtained. Absent is the normal case. */
  verdict?: ClassifierResult;
}

/**
 * A link a friend sent still works — but only for the item it named. The first
 * advance past that item is what keeps "one free item" from becoming an
 * unlimited bypass.
 */
export function effectiveEntryKind(arrival: SessionArrival): EntryKind {
  if (arrival.entryKind === 'feed-entry') return 'feed-entry';
  return arrival.advancesSinceEntry >= DECLARATION.freeItemsOnDeepLink
    ? 'feed-entry'
    : arrival.entryKind;
}

export interface BudgetInput {
  declaration: DeclarationEntry;
  budgetMs: number;
}

/**
 * Measured against foreground time rather than the clock, so a budget is not
 * quietly spent by a tab sitting in the background.
 *
 * The counter belongs to the declaration itself. Deriving it from the daily
 * usage total was wrong across a local-midnight rollover: the day's counter
 * resets to zero while the declaration lives on, which handed the session its
 * whole budget back — plus every minute already spent that day.
 */
export function isBudgetSpent(input: BudgetInput): boolean {
  if (input.declaration.intent !== 'doomscroll') return false;
  return input.declaration.spentMs >= input.budgetMs;
}

/**
 * A veto only prevents enforcement, so it costs less confidence than ending a
 * session the person said was purposeful — which the model *causes*.
 */
export function confidenceGate(intent: DeclaredIntent): number {
  return intent === 'doomscroll' ? CLASSIFIER.vetoConfidence : CLASSIFIER.contradictConfidence;
}

function contradictsAtGate(
  intent: DeclaredIntent,
  verdict: ClassifierResult | undefined,
): ClassifierResult | undefined {
  if (!verdict) return undefined;
  if (verdict.verdict !== 'contradicts') return undefined;
  if (!Number.isFinite(verdict.confidence)) return undefined;
  return verdict.confidence >= confidenceGate(intent) ? verdict : undefined;
}

/**
 * The single declaration decision. Every unhandled combination returns `none`,
 * so an unknown state leaves the person less restricted rather than more.
 */
export function nextDeclarationAction(input: DeclarationInput): DeclarationAction {
  // A deep-link item is never prompted, never walled, never classified.
  if (effectiveEntryKind(input.arrival) !== 'feed-entry') return { kind: 'none' };

  const declaration = input.declaration;
  if (!declaration) return { kind: 'prompt' };

  // A wall already raised stays raised for the rest of the declaration, so
  // scrolling on past one is not a way through it.
  if (declaration.walledAt !== undefined) return { kind: 'wall', reason: WALL_HELD_REASON };

  if (declaration.intent === 'doomscroll') {
    const spent = isBudgetSpent({ declaration, budgetMs: input.budgetMs });
    if (!spent) return { kind: 'none' };
    // The model may say this looks genuinely deliberate. That vetoes the wall.
    if (contradictsAtGate('doomscroll', input.verdict)) return { kind: 'none' };
    return { kind: 'wall', reason: budgetSpentReason(input.budgetMinutes) };
  }

  // Declared purposeful: no timer at all. Only the model ends this session, and
  // only when it is confident the behaviour contradicts the declaration.
  const contradiction = contradictsAtGate('purposeful', input.verdict);
  if (!contradiction) return { kind: 'none' };
  return { kind: 'wall', reason: contradiction.reason.slice(0, CLASSIFIER.maximumReasonLength) };
}
