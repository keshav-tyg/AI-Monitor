import { CLASSIFIER } from '../shared/constants';
import type {
  ClassifierPayload,
  DeclaredIntent,
  EngagementRecord,
  EntryKind,
  SiteId,
} from '../shared/types';

export interface SummaryInput {
  site: SiteId;
  declaredIntent: DeclaredIntent;
  entryKind: EntryKind;
  /** Elapsed time in this session. */
  sessionMs: number;
  records: readonly EngagementRecord[];
  purposefulActionCount: number;
  scrollBurstCount: number;
}

function median(values: readonly number[]): number {
  const sorted = values.filter((value) => Number.isFinite(value)).sort((a, b) => a - b);
  if (sorted.length === 0) return 0;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function round(value: number, places: number): number {
  if (!Number.isFinite(value)) return 0;
  const factor = 10 ** places;
  return Math.round(value * factor) / factor;
}

function whole(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.trunc(value)) : 0;
}

/**
 * Pure aggregation. This is the only thing that ever becomes a prompt, which is
 * why it is a separate module: every value below is a number or one of three
 * enum strings, and nothing here can reach a title, a caption, a URL, or an id.
 *
 * `manuallyPaused` and `advancedBy` are collected per item but deliberately do
 * not appear in the payload — the spec fixes the prompt's shape, and adding a
 * field to it is a change to what leaves the content script.
 */
export function summarizeSession(input: SummaryInput): ClassifierPayload {
  const records = input.records;

  return {
    site: input.site,
    declaredIntent: input.declaredIntent,
    entryKind: input.entryKind,
    sessionMinutes: round(Math.max(0, input.sessionMs) / 60_000, 1),
    itemCount: records.length,
    medianDwellSeconds: round(median(records.map((item) => item.dwellMs)) / 1_000, 1),
    medianCompletion: round(median(records.map((item) => item.playedFraction)), 2),
    fullyWatchedCount: records.filter(
      (item) => item.playedFraction >= CLASSIFIER.fullyWatchedFraction,
    ).length,
    unmutedCount: records.filter((item) => item.unmuted).length,
    replayCount: records.reduce((total, item) => total + whole(item.replayCount), 0),
    purposefulActionCount: whole(input.purposefulActionCount),
    scrollBurstCount: whole(input.scrollBurstCount),
  };
}
