import { DETECTION } from '../shared/constants';
import { formatDuration } from '../shared/time';
import type { NormalizedEvent, SessionState } from '../shared/types';

/**
 * Confidence scoring is a pure state transition so the reason shown to the
 * user is always reproducible from the events alone. No DOM, no page text,
 * no coordinates enter this module.
 */
export function initialSession(event: NormalizedEvent): SessionState {
  return {
    site: event.site,
    enteredAt: event.at,
    lastEventAt: event.at,
    lastActivityAt: event.at,
    score: 0,
    consecutiveAdvances: 0,
    continuousScrolls: 0,
  };
}

export function applyEvent(session: SessionState, event: NormalizedEvent): SessionState {
  // A different site, an out-of-order event, or a long idle gap all mean the
  // previous session no longer describes what the person is doing.
  if (event.site !== session.site) return initialSession(event);
  if (event.at < session.lastEventAt) return initialSession(event);
  // Measured from real behaviour, not from heartbeats: a visible tab emits a
  // heartbeat every second forever, which would otherwise mean no session ever
  // goes idle and a stale score survives indefinitely.
  const lastActivityAt = session.lastActivityAt ?? session.lastEventAt;
  if (event.at - lastActivityAt > DETECTION.sessionIdleMs) return initialSession(event);
  if (event.kind === 'view-entered') return initialSession(event);

  if (event.kind === 'view-left') {
    return { ...session, lastEventAt: event.at, lastActivityAt: event.at };
  }

  // Timekeeping keeps a declared session budget current while a Reel is
  // watched, but it is deliberately not behavioral evidence for detection.
  if (event.kind === 'heartbeat') {
    return { ...session, lastEventAt: event.at };
  }

  if (event.kind === 'purposeful-action') {
    return {
      ...session,
      lastEventAt: event.at,
      lastActivityAt: event.at,
      lastPurposefulAt: event.at,
      score: session.score * DETECTION.purposefulScoreMultiplier,
      consecutiveAdvances: 0,
      continuousScrolls: 0,
    };
  }

  // Counters track the whole session so the reason can say what happened, but
  // confidence only accrues once the view has been passive long enough to rule
  // out a single deliberate visit.
  const passiveLongEnough = event.at - session.enteredAt >= DETECTION.minimumPassiveMs;

  if (event.kind === 'content-advance') {
    return {
      ...session,
      lastEventAt: event.at,
      lastActivityAt: event.at,
      consecutiveAdvances: session.consecutiveAdvances + 1,
      score: session.score + (passiveLongEnough ? DETECTION.advancePoints : 0),
    };
  }

  return {
    ...session,
    lastEventAt: event.at,
    lastActivityAt: event.at,
    continuousScrolls: session.continuousScrolls + 1,
    score: session.score + (passiveLongEnough ? DETECTION.scrollPoints : 0),
  };
}

/** Plain language, derived only from counters and elapsed time. */
export function sessionReason(session: SessionState): string {
  const duration = formatDuration(session.lastEventAt - session.enteredAt);
  return `${session.consecutiveAdvances} content advances and ${session.continuousScrolls} continuous scrolls over ${duration}`;
}
