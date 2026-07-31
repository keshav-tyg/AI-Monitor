import type { AdvanceSource, EngagementRecord } from '../shared/types';

export interface EngagementDeps {
  now(): number;
  /** Null whenever the view has no media — the X timeline, or a redesign. */
  getVideo(): HTMLVideoElement | null;
}

export interface EngagementTracker {
  /** A gesture close enough to an advance is what caused it. */
  noteGesture(source: Exclude<AdvanceSource, 'auto'>): void;
  /** Cheap poll, driven by the existing one-second heartbeat. */
  sample(): void;
  /** Closes the current item and starts the next one. */
  finishItem(): EngagementRecord;
  reset(): void;
}

/** A scroll or click older than this did not cause the advance that followed. */
const GESTURE_ATTRIBUTION_MS = 1_500;

/** Below this, a backwards jump is ordinary seeking noise, not a replay. */
const REPLAY_REWIND_SECONDS = 0.5;

/**
 * Per-item engagement. Everything here is a number or a flag read off the media
 * element — no titles, no captions, no ids, nothing derived from page text.
 *
 * `now` and `getVideo` are injected so this is testable without a real media
 * element, which jsdom does not meaningfully provide.
 */
export function createEngagementTracker(deps: EngagementDeps): EngagementTracker {
  let itemStartedAt = deps.now();
  let maxPlayedFraction = 0;
  let replayCount = 0;
  let unmuted = false;
  let manuallyPaused = false;
  let lastCurrentTime: number | undefined;
  let lastGesture: { source: Exclude<AdvanceSource, 'auto'>; at: number } | undefined;

  function startItem(at: number): void {
    itemStartedAt = at;
    maxPlayedFraction = 0;
    replayCount = 0;
    unmuted = false;
    manuallyPaused = false;
    lastCurrentTime = undefined;
  }

  function sample(): void {
    const video = deps.getVideo();
    if (!video) return;

    const duration = video.duration;
    const currentTime = video.currentTime;

    if (Number.isFinite(duration) && duration > 0 && Number.isFinite(currentTime)) {
      const fraction = Math.min(1, Math.max(0, currentTime / duration));
      if (fraction > maxPlayedFraction) maxPlayedFraction = fraction;

      // A loop restarts the same item. That is attention, not a new item.
      if (lastCurrentTime !== undefined && currentTime + REPLAY_REWIND_SECONDS < lastCurrentTime) {
        replayCount += 1;
      }
      lastCurrentTime = currentTime;
    }

    // Both flags are sticky: unmuting once, or pausing once to actually look at
    // something, is the signal — reverting it later does not erase it.
    if (video.muted === false && video.volume > 0) unmuted = true;
    if (video.paused && Number.isFinite(currentTime) && currentTime > 0) manuallyPaused = true;
  }

  return {
    noteGesture(source): void {
      lastGesture = { source, at: deps.now() };
    },

    sample,

    finishItem(): EngagementRecord {
      // One last read, so an item advanced between heartbeats is not recorded
      // as though nothing played at all.
      sample();

      const at = deps.now();
      const advancedBy: AdvanceSource =
        lastGesture && at - lastGesture.at <= GESTURE_ATTRIBUTION_MS ? lastGesture.source : 'auto';

      const record: EngagementRecord = {
        dwellMs: Math.max(0, at - itemStartedAt),
        playedFraction: maxPlayedFraction,
        replayCount,
        unmuted,
        manuallyPaused,
        advancedBy,
      };

      startItem(at);
      return record;
    },

    reset(): void {
      startItem(deps.now());
      lastGesture = undefined;
    },
  };
}
