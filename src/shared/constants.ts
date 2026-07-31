import type { Settings, SiteId, SiteRule } from './types';

export interface SupportedSite {
  id: SiteId;
  label: string;
  /** Narrow patterns used for temporary blocks — never a whole-site block. */
  blockPatterns: string[];
}

export const SUPPORTED_SITES: readonly SupportedSite[] = [
  {
    id: 'instagram-reels',
    label: 'Instagram Reels',
    blockPatterns: ['*://*.instagram.com/reels/*'],
  },
  {
    id: 'x-timeline',
    label: 'X timeline',
    blockPatterns: ['*://x.com/home*', '*://twitter.com/home*'],
  },
  {
    id: 'youtube-shorts',
    label: 'YouTube Shorts',
    blockPatterns: ['*://*.youtube.com/shorts/*'],
  },
];

export const SITE_IDS: readonly SiteId[] = SUPPORTED_SITES.map((site) => site.id);

/** Rules ship disabled: the extension enforces nothing until asked to. */
const DEFAULT_RULE: SiteRule = {
  enabled: false,
  warningScore: 10,
  gracePeriodSeconds: 60,
  doomscrollBudgetMinutes: 5,
  interventions: ['notify', 'pause', 'close-tab', 'block'],
  blockUntil: 'tomorrow',
};

export const DEFAULT_SETTINGS: Settings = {
  enabled: false,
  rules: {
    'instagram-reels': { ...DEFAULT_RULE },
    'x-timeline': { ...DEFAULT_RULE },
    'youtube-shorts': { ...DEFAULT_RULE },
  },
};

export const DETECTION = {
  advancePoints: 2,
  scrollPoints: 1,
  purposefulScoreMultiplier: 0.25,
  sessionIdleMs: 90_000,
  /** No confidence accrues before this much time in the view. */
  minimumPassiveMs: 120_000,
} as const;

/** Dynamic DNR rule IDs are BLOCK_RULE_ID_BASE + index in SUPPORTED_SITES. */
export const BLOCK_RULE_ID_BASE = 9000;

/** A single event can never bill more than this much foreground time. */
export const MAX_EVENT_GAP_MS = 30_000;

/** Active supported views report time without adding a scrolling signal. */
export const HEARTBEAT_INTERVAL_MS = 1_000;

/** "Continue for 5 minutes" suppression. Per tab, never persisted. */
export const TEMPORARY_CONTINUE_MS = 300_000;

export const MAX_INTERVENTION_RECORDS = 200;

export const DECLARATION = {
  /** One prompt per site per cooldown, so it stays read rather than reflexed. */
  cooldownMs: 1_800_000,
  /** A deep-link visit becomes a feed session on the first advance past it. */
  freeItemsOnDeepLink: 1,
  /** Dismissing the prompt without answering means "looking for something". */
  defaultIntent: 'purposeful',
} as const;

export const CLASSIFIER = {
  /** A veto only prevents enforcement, so it costs less confidence. */
  vetoConfidence: 0.5,
  /** Ending a declared purposeful session *causes* enforcement. Higher bar. */
  contradictConfidence: 0.8,
  /** Past this the declaration governs unchanged. */
  timeoutMs: 1_500,
  /** Nothing is asked about a session too short to have a shape. */
  minimumItems: 5,
  minimumIntervalMs: 20_000,
  maximumReasonLength: 120,
  /** `playedFraction` at or above this counts as fully watched. */
  fullyWatchedFraction: 0.9,
  offscreenUrl: 'src/offscreen/index.html',
} as const;

export const INTENT_PROMPT_QUESTION = 'Hey, what are we doing here?';
export const PURPOSEFUL_BUTTON_LABEL = 'Looking for something';

/** The budget is configurable, so the button and the wall must quote the same
 *  number the rule actually grants rather than a hard-coded five. */
export function doomscrollButtonLabel(minutes: number): string {
  return `Doomscrolling — give me ${minutes} minutes`;
}

export function budgetSpentReason(minutes: number): string {
  return `The ${minutes} minutes you asked for are up`;
}

/** Shown when a wall that was already raised is simply still raised. */
export const WALL_HELD_REASON = 'This feed is walled for the rest of this session';

export const PRIVACY_PROMISE =
  'Nothing leaves this device. This extension does not record screenshots, upload browsing history, or send behavioral data to a server.';
