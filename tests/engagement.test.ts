import { createEngagementTracker } from '../src/content/engagement';

interface FakeVideo {
  currentTime: number;
  duration: number;
  muted: boolean;
  volume: number;
  paused: boolean;
}

function fakeVideo(overrides: Partial<FakeVideo> = {}): FakeVideo {
  return { currentTime: 0, duration: 10, muted: true, volume: 1, paused: false, ...overrides };
}

function harness(video?: FakeVideo) {
  let clock = 1_000;
  const tracker = createEngagementTracker({
    now: () => clock,
    getVideo: () => (video ?? null) as unknown as HTMLVideoElement | null,
  });
  return {
    tracker,
    advanceClock(ms: number) {
      clock += ms;
    },
  };
}

describe('engagement tracking', () => {
  it('measures dwell per item and restarts it on the next one', () => {
    const video = fakeVideo();
    const { tracker, advanceClock } = harness(video);

    advanceClock(4_000);
    expect(tracker.finishItem().dwellMs).toBe(4_000);

    advanceClock(1_500);
    expect(tracker.finishItem().dwellMs).toBe(1_500);
  });

  it('keeps the furthest point reached, not the point at the moment of advance', () => {
    const video = fakeVideo({ duration: 20 });
    const { tracker, advanceClock } = harness(video);

    video.currentTime = 18;
    tracker.sample();
    // A loop back to the start must not erase how far the item was watched.
    video.currentTime = 1;
    tracker.sample();
    advanceClock(500);

    const record = tracker.finishItem();
    expect(record.playedFraction).toBeCloseTo(0.9, 5);
    expect(record.replayCount).toBe(1);
  });

  it('ignores small backwards seeks', () => {
    const video = fakeVideo({ duration: 30 });
    const { tracker } = harness(video);

    video.currentTime = 10;
    tracker.sample();
    video.currentTime = 9.8;
    tracker.sample();

    expect(tracker.finishItem().replayCount).toBe(0);
  });

  it('records unmuting and a deliberate pause, and keeps them once seen', () => {
    const video = fakeVideo({ muted: true, currentTime: 3 });
    const { tracker } = harness(video);

    video.muted = false;
    video.paused = true;
    tracker.sample();
    video.muted = true;
    video.paused = false;
    tracker.sample();

    const record = tracker.finishItem();
    expect(record.unmuted).toBe(true);
    expect(record.manuallyPaused).toBe(true);
  });

  it('attributes an advance to the gesture that just preceded it', () => {
    const video = fakeVideo();
    const { tracker, advanceClock } = harness(video);

    tracker.noteGesture('scroll');
    advanceClock(200);
    expect(tracker.finishItem().advancedBy).toBe('scroll');

    tracker.noteGesture('click');
    advanceClock(100);
    expect(tracker.finishItem().advancedBy).toBe('click');
  });

  it('calls an advance with no recent gesture automatic', () => {
    const video = fakeVideo();
    const { tracker, advanceClock } = harness(video);

    tracker.noteGesture('scroll');
    advanceClock(4_000);

    expect(tracker.finishItem().advancedBy).toBe('auto');
  });

  it('degrades to dwell only when the view has no media element', () => {
    const { tracker, advanceClock } = harness(undefined);

    tracker.sample();
    advanceClock(2_000);
    const record = tracker.finishItem();

    expect(record).toEqual({
      dwellMs: 2_000,
      playedFraction: 0,
      replayCount: 0,
      unmuted: false,
      manuallyPaused: false,
      advancedBy: 'auto',
    });
  });

  it('survives a media element reporting no usable duration', () => {
    const video = fakeVideo({ duration: Number.NaN, currentTime: 5 });
    const { tracker } = harness(video);

    tracker.sample();
    expect(tracker.finishItem().playedFraction).toBe(0);
  });
});
