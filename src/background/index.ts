import { nextIntervention } from '../engine/rules';
import { applyEvent, initialSession, sessionReason } from '../engine/score';
import {
  BLOCK_RULE_ID_BASE,
  MAX_EVENT_GAP_MS,
  SITE_IDS,
  SUPPORTED_SITES,
  TEMPORARY_CONTINUE_MS,
} from '../shared/constants';
import {
  addUsage,
  appendIntervention,
  getBlocks,
  getSettings,
  getUsage,
  listInterventions,
  saveBlocks,
  saveSettings,
  setFeedback,
  type BlockEntry,
} from '../shared/storage';
import { nextLocalMidnight } from '../shared/time';
import type {
  BackgroundRequest,
  BackgroundResponse,
  ContentCommand,
  InterventionDecision,
  InterventionKind,
  NormalizedEvent,
  SessionState,
  Settings,
  SiteId,
  SiteStatus,
} from '../shared/types';

/** Per-tab scoring state. Never persisted — it dies with the worker. */
const sessions = new Map<number, SessionState>();

/** Per-tab "continue for 5 minutes" suppression. Also never persisted. */
const suppressedUntil = new Map<number, number>();

/** Test seam so suites start from a clean worker. */
export function resetSessions(): void {
  sessions.clear();
  suppressedUntil.clear();
}

function isSupportedSite(value: unknown): value is SiteId {
  return typeof value === 'string' && SITE_IDS.includes(value as SiteId);
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
  await saveBlocks(blocks.filter((entry) => entry.expiresAt > now));
}

async function record(site: SiteId, kind: InterventionKind, reason: string, at: number): Promise<void> {
  await appendIntervention({ id: `${at}-${site}-${kind}`, at, site, kind, reason });
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

  if (kind === 'notify') {
    session.warnedAt = now;
    await record(site, kind, reason, now);
    await sendCommand(tabId, { type: 'notify', site, reason });
    try {
      await chrome.notifications.create({
        type: 'basic',
        iconUrl: 'icon.png',
        title: 'Still scrolling?',
        message: reason,
      });
    } catch {
      // Notifications are a courtesy, never a requirement.
    }
    return;
  }

  if (kind === 'pause') {
    session.pauseShownAt = now;
    await record(site, kind, reason, now);
    await sendCommand(tabId, { type: 'pause', site, reason, allowContinue: true });
    return;
  }

  if (kind === 'close-tab') {
    await record(site, kind, reason, now);
    // The stronger action is not lost when both are configured: allowance
    // exhaustion installs the block alongside closing the tab.
    if (blockConfigured) await installBlock(site, nextLocalMidnight(now));
    sessions.delete(tabId);
    await chrome.tabs.remove(tabId);
    return;
  }

  await record(site, kind, reason, now);
  await installBlock(site, nextLocalMidnight(now));
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

  const settings = await getSettings();
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

  // Bill foreground time, capped so a backgrounded tab cannot inflate usage.
  if (previous) {
    const delta = Math.min(Math.max(0, event.at - previous.lastEventAt), MAX_EVENT_GAP_MS);
    if (delta > 0) await addUsage(event.site, delta, now);
  }

  const suppression = suppressedUntil.get(tabId);
  if (suppression !== undefined && now < suppression) return { kind: 'none' };

  const usageMs = await getUsage(event.site, now);
  const decision = nextIntervention({
    rule,
    score: session.score,
    usageMs,
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
  const settings = await getSettings();
  const activeSites = new Set([...sessions.values()].map((session) => session.site));

  const sites: SiteStatus[] = [];
  for (const site of SITE_IDS) {
    const rule = settings.rules[site];
    sites.push({
      site,
      enabled: rule.enabled,
      usedMinutes: Math.floor((await getUsage(site, now)) / 60_000),
      allowedMinutes: rule.dailyAllowanceMinutes,
      active: activeSites.has(site),
    });
  }
  return { ok: true, type: 'status', enabled: settings.enabled, sites, settings };
}

async function route(request: BackgroundRequest, tabId: number | undefined): Promise<BackgroundResponse> {
  switch (request.type) {
    case 'event': {
      if (tabId === undefined) return { ok: false, error: 'No originating tab' };
      await handleEvent(tabId, request.event);
      return { ok: true, type: 'ack' };
    }
    case 'get-status':
      return buildStatus(Date.now());
    case 'save-settings':
      await saveSettings(request.settings as Settings);
      return { ok: true, type: 'settings', settings: await getSettings() };
    case 'get-interventions':
      return { ok: true, type: 'interventions', records: await listInterventions() };
    case 'set-feedback':
      await setFeedback(request.id, request.feedback);
      return { ok: true, type: 'ack' };
    case 'dismiss-pause': {
      if (tabId !== undefined) sessions.delete(tabId);
      return { ok: true, type: 'ack' };
    }
    case 'temporary-continue': {
      if (tabId !== undefined) suppressedUntil.set(tabId, Date.now() + TEMPORARY_CONTINUE_MS);
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

  void removeExpiredBlocks(Date.now());
}

registerListeners();

export type { BlockEntry };
