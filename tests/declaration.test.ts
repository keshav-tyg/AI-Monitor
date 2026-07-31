import {
  confidenceGate,
  effectiveEntryKind,
  isBudgetSpent,
  nextDeclarationAction,
} from '../src/engine/declaration';
import type { ClassifierResult, DeclarationEntry } from '../src/shared/types';

function declaration(overrides: Partial<DeclarationEntry> = {}): DeclarationEntry {
  return {
    site: 'instagram-reels',
    intent: 'doomscroll',
    entryKind: 'feed-entry',
    startedAt: 1_000,
    expiresAt: 1_801_000,
    usageAtStartMs: 60_000,
    ...overrides,
  };
}

function verdict(overrides: Partial<ClassifierResult> = {}): ClassifierResult {
  return { verdict: 'contradicts', confidence: 0.9, reason: 'looks passive', ...overrides };
}

const BUDGET_MS = 300_000;

describe('effective entry kind', () => {
  it('keeps a deep-link arrival a deep link until it advances past the item', () => {
    expect(effectiveEntryKind({ entryKind: 'deep-link', advancesSinceEntry: 0 })).toBe('deep-link');
    expect(effectiveEntryKind({ entryKind: 'in-app-search', advancesSinceEntry: 0 })).toBe(
      'in-app-search',
    );
  });

  it('converts to a feed session on the first advance past the arrival item', () => {
    expect(effectiveEntryKind({ entryKind: 'deep-link', advancesSinceEntry: 1 })).toBe('feed-entry');
    expect(effectiveEntryKind({ entryKind: 'in-app-search', advancesSinceEntry: 3 })).toBe(
      'feed-entry',
    );
  });
});

describe('budget accounting', () => {
  it('spends against foreground usage, not wall-clock time', () => {
    const entry = declaration();
    expect(isBudgetSpent({ declaration: entry, usageMs: 300_000, budgetMs: BUDGET_MS })).toBe(false);
    expect(isBudgetSpent({ declaration: entry, usageMs: 360_000, budgetMs: BUDGET_MS })).toBe(true);
  });

  it('never spends a budget that was never granted', () => {
    const entry = declaration({ intent: 'purposeful' });
    expect(isBudgetSpent({ declaration: entry, usageMs: 9_999_999, budgetMs: BUDGET_MS })).toBe(
      false,
    );
  });
});

describe('confidence gates', () => {
  it('asks more of the model when it causes enforcement than when it prevents it', () => {
    expect(confidenceGate('doomscroll')).toBe(0.5);
    expect(confidenceGate('purposeful')).toBe(0.8);
  });
});

describe('next declaration action', () => {
  const base = {
    usageMs: 60_000,
    budgetMs: BUDGET_MS,
    budgetMinutes: 5,
  };

  it('prompts on a feed entry with no declaration', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 0 },
    });
    expect(action).toEqual({ kind: 'prompt' });
  });

  it('does not prompt a deep-link arrival that has not advanced', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'deep-link', advancesSinceEntry: 0 },
    });
    expect(action).toEqual({ kind: 'none' });
  });

  it('prompts once a deep-link arrival advances past its item', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'deep-link', advancesSinceEntry: 1 },
    });
    expect(action).toEqual({ kind: 'prompt' });
  });

  it('does not re-prompt while a declaration is still active', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 4 },
      declaration: declaration({ intent: 'purposeful' }),
    });
    expect(action).toEqual({ kind: 'none' });
  });

  it('walls once the declared budget is spent', () => {
    const action = nextDeclarationAction({
      ...base,
      usageMs: 400_000,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 12 },
      declaration: declaration(),
    });
    expect(action.kind).toBe('wall');
    expect(action.kind === 'wall' && action.reason).toContain('5 minutes');
  });

  it('lets the model veto a budget wall when the behaviour looks deliberate', () => {
    const action = nextDeclarationAction({
      ...base,
      usageMs: 400_000,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 12 },
      declaration: declaration(),
      verdict: verdict({ confidence: 0.6 }),
    });
    expect(action).toEqual({ kind: 'none' });
  });

  it('ignores a veto that the model is not confident about', () => {
    const action = nextDeclarationAction({
      ...base,
      usageMs: 400_000,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 12 },
      declaration: declaration(),
      verdict: verdict({ confidence: 0.4 }),
    });
    expect(action.kind).toBe('wall');
  });

  it('never walls a purposeful declaration on time alone', () => {
    const action = nextDeclarationAction({
      ...base,
      usageMs: 9_999_999,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 40 },
      declaration: declaration({ intent: 'purposeful' }),
    });
    expect(action).toEqual({ kind: 'none' });
  });

  it('ends a purposeful session when the model contradicts it at high confidence', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 20 },
      declaration: declaration({ intent: 'purposeful' }),
      verdict: verdict({ confidence: 0.85, reason: 'no item held attention' }),
    });
    expect(action).toEqual({ kind: 'wall', reason: 'no item held attention' });
  });

  it('leaves a purposeful session alone below the higher bar', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 20 },
      declaration: declaration({ intent: 'purposeful' }),
      verdict: verdict({ confidence: 0.6 }),
    });
    expect(action).toEqual({ kind: 'none' });
  });

  it('never walls a deep-link item, whatever the model says', () => {
    const action = nextDeclarationAction({
      ...base,
      usageMs: 9_999_999,
      arrival: { entryKind: 'deep-link', advancesSinceEntry: 0 },
      declaration: declaration(),
      verdict: verdict({ confidence: 0.99 }),
    });
    expect(action).toEqual({ kind: 'none' });
  });

  it('keeps a raised wall raised on the next advance', () => {
    const action = nextDeclarationAction({
      ...base,
      arrival: { entryKind: 'feed-entry', advancesSinceEntry: 21 },
      declaration: declaration({ intent: 'purposeful', walledAt: 500_000 }),
    });
    expect(action.kind).toBe('wall');
  });
});
