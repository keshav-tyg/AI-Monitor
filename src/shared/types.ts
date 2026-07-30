/** The only three experiences this prototype understands. */
export type SiteId = 'instagram-reels' | 'x-timeline' | 'youtube-shorts';

export type EventKind =
  | 'view-entered'
  | 'view-left'
  | 'heartbeat'
  | 'scroll'
  | 'content-advance'
  | 'purposeful-action';

export type InterventionKind = 'notify' | 'pause' | 'close-tab' | 'block';

/** Why a purposeful action counted as purposeful. Never page content. */
export type PurposefulDetail =
  | 'search'
  | 'profile'
  | 'post'
  | 'comment'
  | 'save'
  | 'message'
  | 'link';

export interface NormalizedEvent {
  site: SiteId;
  kind: EventKind;
  at: number;
  detail?: PurposefulDetail;
}

export interface SiteRule {
  enabled: boolean;
  dailyAllowanceMinutes: number;
  warningScore: number;
  gracePeriodSeconds: number;
  interventions: InterventionKind[];
  blockUntil: 'tomorrow';
}

export interface Settings {
  enabled: boolean;
  rules: Record<SiteId, SiteRule>;
}

export interface SessionState {
  site: SiteId;
  enteredAt: number;
  lastEventAt: number;
  lastPurposefulAt?: number;
  score: number;
  consecutiveAdvances: number;
  continuousScrolls: number;
  warnedAt?: number;
  pauseShownAt?: number;
}

export type InterventionFeedback = 'accurate' | 'inaccurate';

export interface InterventionRecord {
  id: string;
  at: number;
  site: SiteId;
  kind: InterventionKind;
  reason: string;
  feedback?: InterventionFeedback;
}

/** `none` carries no reason so a fail-open result stays trivially comparable. */
export type InterventionDecision =
  | { kind: 'none' }
  | { kind: InterventionKind; reason: string };

/** Per-site aggregate shown in the popup. No event-level detail escapes here. */
export interface SiteStatus {
  site: SiteId;
  enabled: boolean;
  /** Raw milliseconds. Rounding to whole minutes here made a 1-minute
   *  allowance read "0 of 1" until the instant it enforced. */
  usedMs: number;
  allowedMinutes: number;
  active: boolean;
}

export type BackgroundRequest =
  | { type: 'event'; event: NormalizedEvent }
  | { type: 'get-status' }
  | { type: 'save-settings'; settings: Settings }
  | { type: 'get-interventions' }
  | { type: 'set-feedback'; id: string; feedback: InterventionFeedback }
  | { type: 'dismiss-pause'; site: SiteId }
  | { type: 'temporary-continue'; site: SiteId };

export type BackgroundResponse =
  | { ok: true; type: 'status'; enabled: boolean; sites: SiteStatus[]; settings: Settings }
  | { ok: true; type: 'settings'; settings: Settings }
  | { ok: true; type: 'interventions'; records: InterventionRecord[] }
  | { ok: true; type: 'ack' }
  | { ok: false; error: string };

/** Background -> content script. The content script renders nothing else. */
export type ContentCommand =
  | { type: 'notify'; site: SiteId; reason: string }
  | { type: 'pause'; site: SiteId; reason: string; allowContinue: boolean };
