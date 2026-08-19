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
  warningScore: number;
  gracePeriodSeconds: number;
  /** Granted by a "doomscrolling" declaration, spent against foreground usage. */
  doomscrollBudgetMinutes: number;
  interventions: InterventionKind[];
  blockUntil: 'tomorrow';
}

/**
 * How the person reached the feed. Deterministic — referrer and URL shape only,
 * never the model. An uncertain arrival is a `deep-link`, which is the least
 * restrictive answer.
 */
export type EntryKind = 'deep-link' | 'in-app-search' | 'feed-entry';

/** The answer to "what are we doing here?". Two buttons, nothing else. */
export type DeclaredIntent = 'doomscroll' | 'purposeful';

/** What moved the feed on. `auto` means no gesture was attributable. */
export type AdvanceSource = 'scroll' | 'click' | 'auto';

/** One feed item's engagement. Numbers and flags only — never page content. */
export interface EngagementRecord {
  dwellMs: number;
  playedFraction: number;
  replayCount: number;
  unmuted: boolean;
  manuallyPaused: boolean;
  advancedBy: AdvanceSource;
}

/**
 * The complete prompt payload. A leak reveals scroll statistics and the button
 * that was pressed — nothing about what was watched.
 */
export interface ClassifierPayload {
  site: SiteId;
  declaredIntent: DeclaredIntent;
  entryKind: EntryKind;
  sessionMinutes: number;
  itemCount: number;
  medianDwellSeconds: number;
  medianCompletion: number;
  fullyWatchedCount: number;
  unmutedCount: number;
  replayCount: number;
  purposefulActionCount: number;
  scrollBurstCount: number;
}

/** Does the behaviour match what was declared? */
export type ClassifierVerdict = 'matches' | 'contradicts';

export interface ClassifierResult {
  verdict: ClassifierVerdict;
  confidence: number;
  /** Capped at 120 characters and stored with the intervention record. */
  reason: string;
}

/**
 * A declaration outlives the service worker, so a budget cannot be reset by a
 * worker teardown. `expiresAt` is the cooldown horizon: until it passes, the
 * prompt does not fire again for this site.
 */
export interface DeclarationEntry {
  site: SiteId;
  intent: DeclaredIntent;
  entryKind: EntryKind;
  startedAt: number;
  expiresAt: number;
  /**
   * Foreground milliseconds this declaration has consumed. Owned by the
   * session, not derived from the daily usage counter: that counter resets at
   * local midnight, which would otherwise hand a session declared at 23:59 its
   * budget back plus everything already spent that day.
   */
  spentMs: number;
  walledAt?: number;
}

export interface Settings {
  enabled: boolean;
  rules: Record<SiteId, SiteRule>;
}

/** The desktop service is authoritative; Chrome persists only this applied copy. */
export interface DesktopSettingsSnapshot {
  revision: number;
  settings: Settings;
}

export interface SessionState {
  site: SiteId;
  enteredAt: number;
  lastEventAt: number;
  /** Last event that was actual behaviour. Heartbeats deliberately do not
   *  advance this, so pure timekeeping cannot hold a stale session open. */
  lastActivityAt: number;
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

/**
 * The four moments a declared session actually has. Kept separate from
 * `InterventionRecord` because these are a plain diary of what happened, not
 * judgements carrying accurate/inaccurate feedback.
 */
export type ActivityKind = 'session-started' | 'budget-spent' | 'wall-shown' | 'leave-pressed';

export interface ActivityEntry {
  id: string;
  at: number;
  site: SiteId;
  kind: ActivityKind;
  /** Plain language, derived from settings and counters. Never page content. */
  detail: string;
}

/** `none` carries no reason so a fail-open result stays trivially comparable. */
export type InterventionDecision =
  | { kind: 'none' }
  | { kind: InterventionKind; reason: string };

/** Per-site aggregate shown in the popup. No event-level detail escapes here. */
export interface SiteStatus {
  site: SiteId;
  enabled: boolean;
  active: boolean;
  /** The declared session controls the popup timer, never a daily allowance. */
  session?: {
    intent: DeclaredIntent;
    usedMs?: number;
    budgetMinutes?: number;
  };
}

export type BackgroundRequest =
  | { type: 'event'; event: NormalizedEvent }
  | { type: 'open-dashboard'; payload: Record<string, never> }
  | { type: 'get-status' }
  | { type: 'get-interventions' }
  | { type: 'set-feedback'; id: string; feedback: InterventionFeedback }
  | { type: 'leave-feed'; site: SiteId; reason: string }
  | { type: 'temporary-continue'; site: SiteId }
  | { type: 'arrive'; site: SiteId; entryKind: EntryKind }
  | { type: 'declare-intent'; site: SiteId; intent: DeclaredIntent }
  | { type: 'engagement'; site: SiteId; record: EngagementRecord }
  | { type: 'wall-leave'; site: SiteId }
  | { type: 'get-activity' };

export type BackgroundResponse =
  | { ok: true; type: 'status'; enabled: boolean; sites: SiteStatus[]; settings: Settings }
  | { ok: true; type: 'interventions'; records: InterventionRecord[] }
  | { ok: true; type: 'activity'; entries: ActivityEntry[] }
  | { ok: true; type: 'ack' }
  | { ok: false; error: string };

/** Background -> content script. The content script renders nothing else. */
export type ContentCommand =
  | { type: 'notify'; site: SiteId; reason: string }
  | { type: 'pause'; site: SiteId; reason: string; allowContinue: boolean }
  | { type: 'prompt-intent'; site: SiteId; budgetMinutes: number }
  | { type: 'wall'; site: SiteId; reason: string };
