/**
 * Local calendar day, e.g. "2026-07-30". `en-CA` yields ISO-ordered output
 * while still resolving against the user's own timezone.
 */
export function todayKey(at: number = Date.now()): string {
  return new Date(at).toLocaleDateString('en-CA');
}

/** Never negative: a clock adjustment must not bill negative time. */
export function elapsedMs(from: number, to: number): number {
  return Math.max(0, to - from);
}

/** Midnight at the start of the next local day, for until-tomorrow blocks. */
export function nextLocalMidnight(at: number = Date.now()): number {
  const date = new Date(at);
  date.setHours(24, 0, 0, 0);
  return date.getTime();
}

/** "4m 40s" / "45s" — used in plain-language reasons. */
export function formatDuration(milliseconds: number): string {
  const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? `${minutes}m ${seconds}s` : `${seconds}s`;
}
