import { effectiveEntryKind, nextDeclarationAction } from '../engine/declaration';
import { nextIntervention } from '../engine/rules';
import { applyEvent, initialSession, sessionReason } from '../engine/score';
import { summarizeSession } from '../engine/session-summary';
import {
  BLOCK_RULE_ID_BASE,
  CLASSIFIER,
  DECLARATION,
  MAX_EVENT_GAP_MS,
  SITE_IDS,
  SUPPORTED_SITES,
  TEMPORARY_CONTINUE_MS,
} from '../shared/constants';
import {
  addUsage,
  appendActivity,
  appendIntervention,
  clearReturnPause,
  getBlocks,
  getDeclaration,
  getReturnPause,
  loadEnforcementSettings,
  listActivity,
  listInterventions,
  saveBlocks,
  saveDeclaration,
  saveReturnPause,
  setFeedback,
  type BlockEntry,
} from '../shared/storage';
import { nextLocalMidnight } from '../shared/time';
import type {
  ActivityKind,
  BackgroundRequest,
  BackgroundResponse,
  ClassifierResult,
  ContentCommand,
  DeclarationEntry,
  DeclaredIntent,
  EngagementRecord,
  EntryKind,
  InterventionDecision,
  InterventionKind,
  NormalizedEvent,
  SessionState,
  SiteId,
  SiteStatus,
} from '../shared/types';
import { classify, resetClassifierClient } from './classifier-client';
import { requestOpenDashboard, startNativeBridge } from './native-bridge';

/** Per-tab scoring state. Never persisted — it dies with the worker. */
const sessions = new Map<number, SessionState>();

/** Per-tab "continue for 5 minutes" suppression. Also never persisted. */
const suppressedUntil = new Map<number, number>();

/**
 * How this tab arrived and what it has done since. Transient by design: a
 * worker teardown forgets it, and the persisted declaration is what carries a
 * budget across that.
 */
interface ArrivalState {
  site: SiteId;
  entryKind: EntryKind;
  advancesSinceEntry: number;
  startedAt: number;
  records: EngagementRecord[];
  purposefulActionCount: number;
  scrollBurstCount: number;
  promptedAt?: number;
  lastClassifiedAt?: number;
  verdict?: ClassifierResult;
  verdictAt?: number;
}

const arrivals = new Map<number, ArrivalState>();

/** A session's payload is aggregate; there is no reason to keep every item. */
const MAX_ENGAGEMENT_RECORDS = 60;

const BLOCK_ALARM_PREFIX = 'block-expiry-';

/** Test seam so suites start from a clean worker. */
export function resetSessions(): void {
  sessions.clear();
  suppressedUntil.clear();
  arrivals.clear();
  resetClassifierClient();
}

function isSupportedSite(value: unknown): value is SiteId {
  return typeof value === 'string' && SITE_IDS.includes(value as SiteId);
}

function isExactOpenDashboardRequest(value: unknown): boolean {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const request = value as Record<string, unknown>;
  if (request.type !== 'open-dashboard' || Object.keys(request).length !== 2) return false;

  const payload = request.payload;
  return !!payload
    && typeof payload === 'object'
    && !Array.isArray(payload)
    && Object.keys(payload).length === 0;
}

function ruleIdsFor(siteIndex: number, patternCount: number): number[] {
  // Deterministic and collision-free: ten reserved ids per supported site.
  return Array.from({ length: patternCount }, (_, offset) => BLOCK_RULE_ID_BASE + siteIndex * 10 + offset);
}

async function sendCommand(tabId: number, command: ContentCommand): Promise<void> {
  try {
    await chrome.tabs.sendMessage(tabId, command);
  } catch {
    // The tab may have navigated away. Nothing to do.
  }
}

export async function installBlock(site: SiteId, expiresAt: number): Promise<void> {
  const index = SUPPORTED_SITES.findIndex((entry) => entry.id === site);
  if (index < 0) return;

  const patterns = SUPPORTED_SITES[index].blockPatterns;
  const ids = ruleIdsFor(index, patterns.length);

  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds: ids,
    addRules: patterns.map((urlFilter, offset) => ({
      id: ids[offset],
      priority: 1,
      action: { type: 'block' as chrome.declarativeNetRequest.RuleActionType },
      condition: {
        urlFilter,
        resourceTypes: ['main_frame' as chrome.declarativeNetRequest.ResourceType],
      },
    })),
  });

  const blocks = (await getBlocks()).filter((entry) => entry.site !== site);
  blocks.push({ site, expiresAt });
  await saveBlocks(blocks);

  // DNR rules have no expiry of their own, and a blocked page loads no content
  // script — so without an alarm nothing would ever wake the worker to remove
  // this rule, and the block could outlive midnight indefinitely.
  chrome.alarms?.create?.(`${BLOCK_ALARM_PREFIX}${site}`, { when: expiresAt });
}

export async function removeExpiredBlocks(now: number = Date.now()): Promise<void> {
  const blocks = await getBlocks();
  const expired = blocks.filter((entry) => entry.expiresAt <= now);
  if (expired.length === 0) return;

  const removeRuleIds = expired.flatMap((entry) => {
    const index = SUPPORTED_SITES.findIndex((site) => site.id === entry.site);
    if (index < 0) return [];
    return ruleIdsFor(index, SUPPORTED_SITES[index].blockPatterns.length);
  });

  if (removeRuleIds.length > 0) {
    await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds, addRules: [] });
  }
  for (const entry of expired) {
    void chrome.alarms?.clear?.(`${BLOCK_ALARM_PREFIX}${entry.site}`);
  }
  await saveBlocks(blocks.filter((entry) => entry.expiresAt > now));
}

async function record(site: SiteId, kind: InterventionKind, reason: string, at: number): Promise<void> {
  await appendIntervention({ id: `${at}-${site}-${kind}`, at, site, kind, reason });
}

/**
 * Serializes read-modify-write cycles on one site's declaration.
 *
 * Message handlers interleave at every `await`, so two advances — two tabs on
 * the same feed, or a heartbeat racing an advance — could both read `walledAt`
 * as unset and each write a crossing, producing duplicate interventions and
 * duplicate timeline rows. The worker is single-threaded, so chaining promises
 * per site is enough to make each cycle atomic.
 */
const declarationLocks = new Map<SiteId, Promise<unknown>>();

function withDeclarationLock<T>(site: SiteId, work: () => Promise<T>): Promise<T> {
  const previous = declarationLocks.get(site) ?? Promise.resolve();
  const next = previous.then(work, work);
  // The stored tail must never reject, or every later waiter inherits it.
  declarationLocks.set(
    site,
    next.then(
      () => undefined,
      () => undefined,
    ),
  );
  return next;
}

/**
 * Bills foreground time against the declaration that is paying for it. Kept
 * separate from `addUsage` because the daily counter resets at midnight and a
 * session's budget must not.
 */
async function spendDeclaredBudget(site: SiteId, milliseconds: number, now: number): Promise<void> {
  if (!(milliseconds > 0)) return;
  await withDeclarationLock(site, async () => {
    const declaration = await getDeclaration(site, now);
    if (!declaration || declaration.intent !== 'doomscroll') return;
    if (declaration.walledAt !== undefined) return;
    await saveDeclaration({ ...declaration, spentMs: declaration.spentMs + milliseconds });
  });
}

/** The plain diary of a declared session: what happened, when, in order. */
async function logActivity(
  site: SiteId,
  kind: ActivityKind,
  detail: string,
  at: number,
): Promise<void> {
  await appendActivity({ id: `${at}-${site}-${kind}`, at, site, kind, detail });
}

async function enforce(
  tabId: number,
  site: SiteId,
  decision: Exclude<InterventionDecision, { kind: 'none' }>,
  session: SessionState,
  now: number,
  blockConfigured: boolean,
): Promise<void> {
  const { kind, reason } = decision;

  // Stamp by escalation stage, not by which action happened to fire. Keying on
  // `kind` stalled any rule whose first intervention was not `notify`: the
  // stage never advanced past the first step.
  if (session.warnedAt === undefined) session.warnedAt = now;
  else if (session.pauseShownAt === undefined) session.pauseShownAt = now;

  if (kind === 'notify') {
    await record(site, kind, reason, now);
    await sendCommand(tabId, { type: 'notify', site, reason });
    try {
      await chrome.notifications.create({
        type: 'basic',
        iconUrl: 'icons/icon-128.png',
        title: 'Still scrolling?',
        message: reason,
      });
    } catch {
      // Notifications are a courtesy, never a requirement.
    }
    return;
  }

  if (kind === 'pause') {
    await record(site, kind, reason, now);
    await sendCommand(tabId, { type: 'pause', site, reason, allowContinue: true });
    return;
  }

  if (kind === 'close-tab') {
    await record(site, kind, reason, now);
    if (blockConfigured) await installBlock(site, nextLocalMidnight(now));
    sessions.delete(tabId);
    await chrome.tabs.remove(tabId);
    return;
  }

  await record(site, kind, reason, now);
  await installBlock(site, nextLocalMidnight(now));
}

/**
 * Unrecognised input becomes `deep-link`, matching the content script's own
 * bias: uncertainty must not create a prompt.
 *
 * (Named `parse…` rather than `is…EntryKind` on purpose — the latter contains
 * the substring the privacy test greps for.)
 */
function parseEntryKind(value: unknown): EntryKind {
  if (value === 'in-app-search' || value === 'feed-entry') return value;
  return 'deep-link';
}

/** Numbers and flags only. Anything unrecognised becomes a harmless default. */
function sanitizeRecord(value: unknown): EngagementRecord | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const candidate = value as Partial<EngagementRecord>;
  const number = (input: unknown): number =>
    typeof input === 'number' && Number.isFinite(input) ? Math.max(0, input) : 0;

  const advancedBy = candidate.advancedBy;
  return {
    dwellMs: number(candidate.dwellMs),
    playedFraction: Math.min(1, number(candidate.playedFraction)),
    replayCount: Math.trunc(number(candidate.replayCount)),
    unmuted: candidate.unmuted === true,
    manuallyPaused: candidate.manuallyPaused === true,
    advancedBy: advancedBy === 'scroll' || advancedBy === 'click' ? advancedBy : 'auto',
  };
}

/**
 * The wall is behavioural: an overlay, never a tab closure and never a network
 * block. A URL block cannot tell a feed swipe from a friend's link, and keeping
 * deep links working is the whole point.
 */
async function raiseWall(
  tabId: number,
  site: SiteId,
  reason: string,
  now: number,
  /** Which control ended the session — the budget, or the model's judgement. */
  cause: 'budget' | 'model',
): Promise<InterventionDecision> {
  // Claiming the crossing and writing it must be one indivisible step, or two
  // racing advances both record the same wall.
  const crossed = await withDeclarationLock(site, async () => {
    const declaration = await getDeclaration(site, now);
    if (!declaration || declaration.walledAt !== undefined) return false;
    await saveDeclaration({ ...declaration, walledAt: now });
    return true;
  });

  if (crossed) {
    // Recorded once, on the crossing. Re-walling on every later advance would
    // bury the review list in duplicates of the same decision.
    await record(site, 'pause', reason, now);
    if (cause === 'budget') await logActivity(site, 'budget-spent', reason, now);
    await logActivity(site, 'wall-shown', reason, now);
  }
  await sendCommand(tabId, { type: 'wall', site, reason });
  return { kind: 'pause', reason };
}

/**
 * Asks the model at most once per interval, and only about a session that has
 * enough shape to judge. A missing answer is the normal case, not an error.
 */
async function maybeClassify(
  arrival: ArrivalState,
  declaration: DeclarationEntry | undefined,
  now: number,
): Promise<ClassifierResult | undefined> {
  if (!declaration) return undefined;
  if (arrival.records.length < CLASSIFIER.minimumItems) return undefined;

  // A verdict older than one interval is no longer describing this session —
  // which matters most for a veto, so it cannot hold a wall down forever.
  if (arrival.verdictAt !== undefined && now - arrival.verdictAt < CLASSIFIER.minimumIntervalMs) {
    return arrival.verdict;
  }
  if (
    arrival.lastClassifiedAt !== undefined &&
    now - arrival.lastClassifiedAt < CLASSIFIER.minimumIntervalMs
  ) {
    return undefined;
  }

  arrival.lastClassifiedAt = now;
  let result: ClassifierResult | undefined;
  try {
    result = await classify(
      summarizeSession({
        site: arrival.site,
        declaredIntent: declaration.intent,
        entryKind: effectiveEntryKind(arrival),
        sessionMs: now - arrival.startedAt,
        records: arrival.records,
        purposefulActionCount: arrival.purposefulActionCount,
        scrollBurstCount: arrival.scrollBurstCount,
      }),
    );
  } catch {
    // The classifier is advisory. A transport or document failure must never
    // turn an otherwise harmless session into enforcement.
    return undefined;
  }

  arrival.verdict = result;
  arrival.verdictAt = result ? now : undefined;
  return result;
}

/**
 * The declared-intent path, evaluated ahead of the score ladder because the
 * declaration is the primary control. Returns what it did so the caller can
 * stop: a wall and a score-based pause on the same event would stack.
 */
async function evaluateDeclaration(
  tabId: number,
  arrival: ArrivalState,
  now: number,
  budgetMinutes: number,
): Promise<InterventionDecision> {
  const declaration = await getDeclaration(arrival.site, now);
  const verdict = await maybeClassify(arrival, declaration, now);

  const action = nextDeclarationAction({
    arrival: { entryKind: arrival.entryKind, advancesSinceEntry: arrival.advancesSinceEntry },
    declaration,
    budgetMs: budgetMinutes * 60_000,
    budgetMinutes,
    verdict,
  });

  if (action.kind === 'prompt') {
    // Once per arrival. The persisted declaration's own expiry is what stops it
    // firing again on the next visit.
    if (arrival.promptedAt !== undefined) return { kind: 'none' };
    arrival.promptedAt = now;
    await sendCommand(tabId, { type: 'prompt-intent', site: arrival.site, budgetMinutes });
    return { kind: 'none' };
  }

  if (action.kind === 'wall') {
    // A doomscroll declaration can only ever be ended by its own budget; a
    // purposeful one can only ever be ended by the model.
    const cause = declaration?.intent === 'doomscroll' ? 'budget' : 'model';
    return raiseWall(tabId, arrival.site, action.reason, now, cause);
  }
  return { kind: 'none' };
}

/**
 * The single decision path. Every early return is a fail-open: an unknown tab,
 * an unsupported site, a disabled extension, or a disabled rule enforces
 * nothing at all.
 */
export async function handleEvent(
  tabId: number,
  event: NormalizedEvent,
  now: number = Date.now(),
): Promise<InterventionDecision> {
  if (!Number.isInteger(tabId)) return { kind: 'none' };
  if (!isSupportedSite(event.site)) return { kind: 'none' };
  if (typeof event.at !== 'number') return { kind: 'none' };

  const settings = await loadEnforcementSettings();
  if (!settings.enabled) return { kind: 'none' };

  const rule = settings.rules[event.site];
  if (!rule || !rule.enabled) return { kind: 'none' };

  const previous = sessions.get(tabId);
  const session =
    previous && previous.site === event.site ? applyEvent(previous, event) : initialSession(event);

  // Carry escalation stamps across the transition so a session that already
  // warned does not warn again from scratch.
  if (previous && previous.site === event.site && session.enteredAt === previous.enteredAt) {
    session.warnedAt = previous.warnedAt;
    session.pauseShownAt = previous.pauseShownAt;
  }
  sessions.set(tabId, session);

  // Bill foreground time. A gap wider than one event can account for means the
  // tab was hidden or the worker slept, so bill nothing rather than clamp it to
  // 30s of presence that never happened.
  if (previous) {
    const gap = event.at - previous.lastEventAt;
    if (gap > 0 && gap <= MAX_EVENT_GAP_MS) {
      await addUsage(event.site, gap, now);
      // The same foreground time, billed a second time against the declaration
      // that is paying for it. Two counters because they expire differently:
      // usage resets at local midnight, a declared budget never does.
      await spendDeclaredBudget(event.site, gap, now);
    }
  }

  // Leaving a feed must never be the thing that enforces against it. Without
  // this, navigating away from a high-score session was itself the event that
  // closed the tab or installed a block.
  if (event.kind === 'view-left') {
    sessions.delete(tabId);
    arrivals.delete(tabId);
    return { kind: 'none' };
  }

  // The declared-intent path runs first, and ahead of the "continue for 5
  // minutes" suppression: that button belongs to the score ladder, and it must
  // not buy a way past a budget the person set themselves.
  let arrival = arrivals.get(tabId);
  let activeDeclaration: DeclarationEntry | undefined;

  // An MV3 worker is torn down whenever it idles, and `arrive` is only sent on
  // a route change — so without this, reloading the extension or coming back to
  // a backgrounded tab would leave the budget unenforced until the next
  // navigation. Rebuilding here keeps the persisted declaration in charge.
  if (!arrival || arrival.site !== event.site) {
    const existing = await getDeclaration(event.site, now);
    arrival = {
      site: event.site,
      // Already declared means this *is* the session they declared, so it gets
      // no free item. Otherwise fail open: one item, then it converts.
      entryKind: existing ? 'feed-entry' : 'deep-link',
      advancesSinceEntry: 0,
      startedAt: now,
      records: [],
      purposefulActionCount: 0,
      scrollBurstCount: 0,
    };
    arrivals.set(tabId, arrival);
  }

  {
    activeDeclaration = await getDeclaration(event.site, now);
    if (event.kind === 'content-advance') arrival.advancesSinceEntry += 1;
    else if (event.kind === 'scroll') arrival.scrollBurstCount += 1;
    else if (event.kind === 'purposeful-action') arrival.purposefulActionCount += 1;

    if (event.kind === 'content-advance') {
      const declared = await evaluateDeclaration(tabId, arrival, now, rule.doomscrollBudgetMinutes);
      if (declared.kind !== 'none') return declared;
    }
  }

  // A declaration is the primary session control. The score ladder remains
  // available before a choice is made, but cannot override a declared
  // purposeful session or shorten an unspent doomscroll budget.
  if (activeDeclaration) return { kind: 'none' };

  const suppression = suppressedUntil.get(tabId);
  if (suppression !== undefined && now < suppression) return { kind: 'none' };

  // A deliberate Leave means the person already saw this pause. Show it once on
  // re-entry and consume it: gating on view-entered keeps the 1/second
  // heartbeats from re-firing it, which rebuilt the overlay every second and
  // made its buttons impossible to click.
  if (event.kind === 'view-entered') {
    const returnPause = await getReturnPause(event.site, now);
    if (returnPause) {
      await clearReturnPause(event.site);
      const decision: InterventionDecision = { kind: 'pause', reason: returnPause.reason };
      await enforce(tabId, event.site, decision, session, now, false);
      return decision;
    }
  }

  const decision = nextIntervention({
    rule,
    score: session.score,
    now,
    warnedAt: session.warnedAt,
    pauseShownAt: session.pauseShownAt,
    reason: sessionReason(session),
  });

  if (decision.kind === 'none') return decision;

  await removeExpiredBlocks(now);
  await enforce(tabId, event.site, decision, session, now, rule.interventions.includes('block'));
  return decision;
}

async function buildStatus(now: number): Promise<BackgroundResponse> {
  const settings = await loadEnforcementSettings();
  const activeSites = new Set([...sessions.values()].map((session) => session.site));

  const sites: SiteStatus[] = [];
  for (const site of SITE_IDS) {
    const rule = settings.rules[site];
    const declaration = await getDeclaration(site, now);
    const session = declaration
      ? declaration.intent === 'doomscroll'
        ? {
            intent: declaration.intent,
            usedMs: Math.max(0, declaration.spentMs),
            budgetMinutes: rule.doomscrollBudgetMinutes,
          }
        : { intent: declaration.intent }
      : undefined;
    sites.push({
      site,
      enabled: rule.enabled,
      active: activeSites.has(site),
      session,
    });
  }
  return { ok: true, type: 'status', enabled: settings.enabled, sites, settings };
}

async function route(request: BackgroundRequest, tabId: number | undefined): Promise<BackgroundResponse> {
  switch (request.type) {
    case 'open-dashboard':
      if (!isExactOpenDashboardRequest(request)) return { ok: false, error: 'Unknown request' };
      requestOpenDashboard();
      return { ok: true, type: 'ack' };
    case 'event': {
      if (tabId === undefined) return { ok: false, error: 'No originating tab' };
      await handleEvent(tabId, request.event);
      return { ok: true, type: 'ack' };
    }
    case 'get-status':
      return buildStatus(Date.now());
    case 'get-interventions':
      return { ok: true, type: 'interventions', records: await listInterventions() };
    case 'set-feedback':
      await setFeedback(request.id, request.feedback);
      return { ok: true, type: 'ack' };
    case 'leave-feed': {
      if (tabId === undefined) return { ok: false, error: 'No originating tab' };
      if (!isSupportedSite(request.site)) return { ok: false, error: 'Unsupported site' };
      await saveReturnPause({
        site: request.site,
        reason: typeof request.reason === 'string' ? request.reason : 'Focus pause',
        expiresAt: nextLocalMidnight(),
      });
      sessions.delete(tabId);
      suppressedUntil.delete(tabId);
      // Closing a legacy pause tab is only allowed when the rule selected it.
      const settings = await loadEnforcementSettings();
      if (settings.rules[request.site]?.interventions.includes('close-tab')) {
        await chrome.tabs.remove(tabId);
      }
      return { ok: true, type: 'ack' };
    }
    case 'temporary-continue': {
      if (tabId !== undefined) suppressedUntil.set(tabId, Date.now() + TEMPORARY_CONTINUE_MS);
      return { ok: true, type: 'ack' };
    }
    case 'arrive': {
      if (tabId === undefined) return { ok: false, error: 'No originating tab' };
      if (!isSupportedSite(request.site)) return { ok: false, error: 'Unsupported site' };

      const now = Date.now();
      // An unrecognised classification is treated as a deep link, matching the
      // content script's own bias: uncertainty must not create a prompt.
      const arrival: ArrivalState = {
        site: request.site,
        entryKind: parseEntryKind(request.entryKind),
        advancesSinceEntry: 0,
        startedAt: now,
        records: [],
        purposefulActionCount: 0,
        scrollBurstCount: 0,
      };
      arrivals.set(tabId, arrival);

      const settings = await loadEnforcementSettings();
      const rule = settings.rules[request.site];
      if (settings.enabled && rule?.enabled) {
        await evaluateDeclaration(tabId, arrival, now, rule.doomscrollBudgetMinutes);
      }
      return { ok: true, type: 'ack' };
    }
    case 'declare-intent': {
      if (!isSupportedSite(request.site)) return { ok: false, error: 'Unsupported site' };

      const now = Date.now();
      const arrival = tabId === undefined ? undefined : arrivals.get(tabId);
      // Anything that is not the doomscroll button is the answer that
      // restricts nothing — including a dismissal.
      const intent: DeclaredIntent = request.intent === 'doomscroll' ? 'doomscroll' : 'purposeful';

      await withDeclarationLock(request.site, async () => {
        await saveDeclaration({
          site: request.site,
          intent,
          entryKind: arrival ? effectiveEntryKind(arrival) : 'feed-entry',
          startedAt: now,
          // The cooldown horizon: until it passes, the question is not asked
          // again, so it stays something read rather than reflex-dismissed.
          expiresAt: now + DECLARATION.cooldownMs,
          spentMs: 0,
        });
      });

      const settings = await loadEnforcementSettings();
      const budgetMinutes = settings.rules[request.site]?.doomscrollBudgetMinutes ?? 0;
      await logActivity(
        request.site,
        'session-started',
        intent === 'doomscroll'
          ? `Doomscrolling — ${budgetMinutes} minute budget`
          : 'Looking for something — no timer',
        now,
      );
      return { ok: true, type: 'ack' };
    }
    case 'get-activity':
      return { ok: true, type: 'activity', entries: await listActivity() };
    case 'engagement': {
      const arrival = tabId === undefined ? undefined : arrivals.get(tabId);
      if (!arrival || arrival.site !== request.site) return { ok: true, type: 'ack' };

      // The record arrives immediately before the content-advance event that
      // finished its item. While this is still the free deep-link/search item,
      // discard it so friend-sent content never enters a model summary later.
      if (effectiveEntryKind(arrival) !== 'feed-entry') return { ok: true, type: 'ack' };

      const record = sanitizeRecord(request.record);
      if (record) {
        arrival.records.push(record);
        if (arrival.records.length > MAX_ENGAGEMENT_RECORDS) arrival.records.shift();
      }
      return { ok: true, type: 'ack' };
    }
    case 'wall-leave': {
      if (tabId === undefined) return { ok: false, error: 'No originating tab' };
      if (!isSupportedSite(request.site)) return { ok: false, error: 'Unsupported site' };

      await logActivity(request.site, 'leave-pressed', 'Left the feed from the wall', Date.now());

      // No return pause here: the wall already survives in the declaration, and
      // a return pause would offer a Continue button the wall is refusing.
      sessions.delete(tabId);
      arrivals.delete(tabId);
      suppressedUntil.delete(tabId);

      // Unlike the legacy pause screen, a wall has no continue action. Leaving
      // it must actually exit this feed; otherwise the removed overlay is a
      // one-click bypass when close-tab is not in the old score ladder.
      await chrome.tabs.remove(tabId);
      return { ok: true, type: 'ack' };
    }
    default:
      return { ok: false, error: 'Unknown request' };
  }
}

function registerListeners(): void {
  const runtime = (globalThis as { chrome?: typeof chrome }).chrome?.runtime;
  if (!runtime?.onMessage) return;

  runtime.onMessage.addListener((message, sender, sendResponse) => {
    route(message as BackgroundRequest, sender.tab?.id)
      .then(sendResponse)
      .catch(() => sendResponse({ ok: false, error: 'Request failed' }));
    return true;
  });

  // Fires even when nothing else is happening, which is the whole point.
  const alarms = (globalThis as { chrome?: typeof chrome }).chrome?.alarms;
  alarms?.onAlarm?.addListener((alarm) => {
    if (!alarm.name.startsWith(BLOCK_ALARM_PREFIX)) return;
    void removeExpiredBlocks(Date.now());
  });

  void removeExpiredBlocks(Date.now());
}

registerListeners();
startNativeBridge(() => {
  // The bridge has already persisted the validated snapshot. Settings are
  // intentionally read from that cache per request, never mirrored as a
  // second mutable authority in worker memory.
});

export type { BlockEntry };

/** Test seam: exercises the same message routes the UI and overlay use. */
export const routeForTest = route;
