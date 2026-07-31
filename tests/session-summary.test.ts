import { summarizeSession } from '../src/engine/session-summary';
import type { ClassifierPayload, EngagementRecord } from '../src/shared/types';

function record(overrides: Partial<EngagementRecord> = {}): EngagementRecord {
  return {
    dwellMs: 5_000,
    playedFraction: 0.4,
    replayCount: 0,
    unmuted: false,
    manuallyPaused: false,
    advancedBy: 'scroll',
    ...overrides,
  };
}

const BASE = {
  site: 'instagram-reels',
  declaredIntent: 'purposeful',
  entryKind: 'feed-entry',
  sessionMs: 372_000,
  purposefulActionCount: 0,
  scrollBurstCount: 5,
} as const;

describe('session summary', () => {
  it('takes the middle value of an odd number of items', () => {
    const payload = summarizeSession({
      ...BASE,
      records: [
        record({ dwellMs: 2_000, playedFraction: 0.1 }),
        record({ dwellMs: 7_400, playedFraction: 0.31 }),
        record({ dwellMs: 30_000, playedFraction: 0.9 }),
      ],
    });

    expect(payload.medianDwellSeconds).toBe(7.4);
    expect(payload.medianCompletion).toBe(0.31);
    expect(payload.itemCount).toBe(3);
    expect(payload.sessionMinutes).toBe(6.2);
  });

  it('averages the two middle values of an even number of items', () => {
    const payload = summarizeSession({
      ...BASE,
      records: [
        record({ dwellMs: 2_000, playedFraction: 0.2 }),
        record({ dwellMs: 4_000, playedFraction: 0.4 }),
        record({ dwellMs: 6_000, playedFraction: 0.6 }),
        record({ dwellMs: 8_000, playedFraction: 0.8 }),
      ],
    });

    expect(payload.medianDwellSeconds).toBe(5);
    expect(payload.medianCompletion).toBe(0.5);
  });

  it('counts only items watched essentially to the end as fully watched', () => {
    const payload = summarizeSession({
      ...BASE,
      records: [
        record({ playedFraction: 0.89 }),
        record({ playedFraction: 0.9 }),
        record({ playedFraction: 1 }),
      ],
    });

    expect(payload.fullyWatchedCount).toBe(2);
  });

  it('sums replays and counts unmuted items', () => {
    const payload = summarizeSession({
      ...BASE,
      records: [
        record({ replayCount: 2, unmuted: true }),
        record({ replayCount: 1 }),
        record({ unmuted: true }),
      ],
    });

    expect(payload.replayCount).toBe(3);
    expect(payload.unmutedCount).toBe(2);
  });

  it('produces zeros rather than NaN for a session with no items yet', () => {
    const payload = summarizeSession({ ...BASE, sessionMs: 0, records: [] });

    for (const value of Object.values(payload)) {
      expect(Number.isNaN(value as number)).toBe(false);
    }
    expect(payload.medianDwellSeconds).toBe(0);
    expect(payload.medianCompletion).toBe(0);
    expect(payload.itemCount).toBe(0);
  });

  it('carries nothing but numbers and the three declared enums', () => {
    const payload = summarizeSession({ ...BASE, records: [record()] });

    const stringFields: (keyof ClassifierPayload)[] = ['site', 'declaredIntent', 'entryKind'];
    for (const [key, value] of Object.entries(payload)) {
      if (stringFields.includes(key as keyof ClassifierPayload)) {
        expect(typeof value).toBe('string');
      } else {
        expect(typeof value).toBe('number');
      }
    }
    expect(Object.keys(payload)).toHaveLength(12);
  });

  it('never emits a negative count from malformed input', () => {
    const payload = summarizeSession({
      ...BASE,
      sessionMs: -5_000,
      purposefulActionCount: -3,
      scrollBurstCount: Number.NaN,
      records: [record({ replayCount: -2 })],
    });

    expect(payload.sessionMinutes).toBe(0);
    expect(payload.purposefulActionCount).toBe(0);
    expect(payload.scrollBurstCount).toBe(0);
    expect(payload.replayCount).toBe(0);
  });
});
