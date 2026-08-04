import {
  DEFAULT_SETTINGS,
  MAX_ACTIVITY_RECORDS,
  MAX_INTERVENTION_RECORDS,
  SITE_IDS,
} from './constants';
import { todayKey } from './time';
import type {
  ActivityEntry,
  DeclarationEntry,
  InterventionFeedback,
  InterventionRecord,
  Settings,
  SiteId,
  SiteRule,
} from './types';

/**
 * Every read and write in this module targets `chrome.storage.local`.
 * `sync` and `managed` are deliberately never used: synced data would leave
 * the device, which is the one thing this extension promises not to do.
 */
const KEY_SETTINGS = 'settings';
const KEY_USAGE = 'usage';
const KEY_INTERVENTIONS = 'interventions';
const KEY_BLOCKS = 'blocks';
const KEY_RETURN_PAUSES = 'return-pauses';
const KEY_DECLARATIONS = 'declarations';
const KEY_ACTIVITY = 'activity';

interface UsageEntry {
  day: string;
  milliseconds: number;
}

type UsageMap = Partial<Record<SiteId, UsageEntry>>;

export interface BlockEntry {
  site: SiteId;
  expiresAt: number;
}

/** A deliberate Leave keeps the same pause ready if the feed is reopened. */
export interface ReturnPauseEntry {
  site: SiteId;
  reason: string;
  expiresAt: number;
}

async function readKey<T>(key: string, fallback: T): Promise<T> {
  const stored = await chrome.storage.local.get(key);
  const value = (stored as Record<string, unknown>)[key];
  return value === undefined ? fallback : (value as T);
}

async function writeKey(key: string, value: unknown): Promise<void> {
  await chrome.storage.local.set({ [key]: value });
}

/**
 * Missing or partially-written settings resolve to defaults rather than
 * throwing: an unreadable rule must fail open, not enforce something random.
 */
export async function getSettings(): Promise<Settings> {
  const stored = await readKey<Partial<Settings>>(KEY_SETTINGS, {});
  const rules = {} as Record<SiteId, SiteRule>;
  for (const site of SITE_IDS) {
    const legacyRule = stored.rules?.[site] as Partial<SiteRule> | undefined;
    const fallback = DEFAULT_SETTINGS.rules[site];
    // Construct the supported shape explicitly. Old allowance fields (and any
    // other stale keys) remain harmless in local storage and disappear on the
    // next Options save.
    rules[site] = {
      enabled: legacyRule?.enabled ?? fallback.enabled,
      warningScore: legacyRule?.warningScore ?? fallback.warningScore,
      gracePeriodSeconds: legacyRule?.gracePeriodSeconds ?? fallback.gracePeriodSeconds,
      doomscrollBudgetMinutes:
        legacyRule?.doomscrollBudgetMinutes ?? fallback.doomscrollBudgetMinutes,
      interventions: legacyRule?.interventions ?? fallback.interventions,
      blockUntil: legacyRule?.blockUntil ?? fallback.blockUntil,
    };
  }
  return { enabled: stored.enabled ?? DEFAULT_SETTINGS.enabled, rules };
}

export async function saveSettings(settings: Settings): Promise<void> {
  await writeKey(KEY_SETTINGS, settings);
}

/** Zero once the stored day is no longer today — the daily reset. */
export async function getUsage(site: SiteId, now: number = Date.now()): Promise<number> {
  const usage = await readKey<UsageMap>(KEY_USAGE, {});
  const entry = usage[site];
  if (!entry || entry.day !== todayKey(now)) return 0;
  return entry.milliseconds;
}

/** Returns the new total for the current local day. */
export async function addUsage(
  site: SiteId,
  milliseconds: number,
  now: number = Date.now(),
): Promise<number> {
  const usage = await readKey<UsageMap>(KEY_USAGE, {});
  const day = todayKey(now);
  const entry = usage[site];
  const base = entry && entry.day === day ? entry.milliseconds : 0;
  const total = base + Math.max(0, milliseconds);
  usage[site] = { day, milliseconds: total };
  await writeKey(KEY_USAGE, usage);
  return total;
}

/** Oldest first. Appending past the cap drops only the oldest record. */
export async function listInterventions(): Promise<InterventionRecord[]> {
  return readKey<InterventionRecord[]>(KEY_INTERVENTIONS, []);
}

export async function appendIntervention(record: InterventionRecord): Promise<void> {
  const records = await listInterventions();
  const retained = records.slice(-(MAX_INTERVENTION_RECORDS - 1));
  retained.push(record);
  await writeKey(KEY_INTERVENTIONS, retained);
}

/** An unknown id leaves every stored record untouched. */
export async function setFeedback(id: string, feedback: InterventionFeedback): Promise<void> {
  const records = await listInterventions();
  const updated = records.map((record) =>
    record.id === id ? { ...record, feedback } : record,
  );
  await writeKey(KEY_INTERVENTIONS, updated);
}

export async function getBlocks(): Promise<BlockEntry[]> {
  return readKey<BlockEntry[]>(KEY_BLOCKS, []);
}

export async function saveBlocks(blocks: BlockEntry[]): Promise<void> {
  await writeKey(KEY_BLOCKS, blocks);
}

export async function getReturnPause(
  site: SiteId,
  now: number = Date.now(),
): Promise<ReturnPauseEntry | undefined> {
  const pauses = await readKey<ReturnPauseEntry[]>(KEY_RETURN_PAUSES, []);
  const active = pauses.filter((entry) => entry.expiresAt > now);
  if (active.length !== pauses.length) await writeKey(KEY_RETURN_PAUSES, active);
  return active.find((entry) => entry.site === site);
}

/** A return pause is consumed by being shown once. */
export async function clearReturnPause(site: SiteId): Promise<void> {
  const pauses = await readKey<ReturnPauseEntry[]>(KEY_RETURN_PAUSES, []);
  await writeKey(KEY_RETURN_PAUSES, pauses.filter((entry) => entry.site !== site));
}

export async function saveReturnPause(entry: ReturnPauseEntry): Promise<void> {
  const pauses = await readKey<ReturnPauseEntry[]>(KEY_RETURN_PAUSES, []);
  await writeKey(KEY_RETURN_PAUSES, [...pauses.filter((item) => item.site !== entry.site), entry]);
}

/**
 * A declaration must outlive the service worker: without persistence, a worker
 * teardown mid-session would silently hand back a fresh budget. Expired entries
 * are pruned on read, exactly as return pauses are.
 */
export async function getDeclaration(
  site: SiteId,
  now: number = Date.now(),
): Promise<DeclarationEntry | undefined> {
  const stored = await readKey<DeclarationEntry[]>(KEY_DECLARATIONS, []);
  const active = stored.filter((entry) => entry.expiresAt > now);
  if (active.length !== stored.length) await writeKey(KEY_DECLARATIONS, active);
  return active.find((entry) => entry.site === site);
}

export async function saveDeclaration(entry: DeclarationEntry): Promise<void> {
  const stored = await readKey<DeclarationEntry[]>(KEY_DECLARATIONS, []);
  await writeKey(KEY_DECLARATIONS, [...stored.filter((item) => item.site !== entry.site), entry]);
}

/** Oldest first, so the options page can read top-to-bottom as it happened. */
export async function listActivity(): Promise<ActivityEntry[]> {
  return readKey<ActivityEntry[]>(KEY_ACTIVITY, []);
}

export async function appendActivity(entry: ActivityEntry): Promise<void> {
  const entries = await listActivity();
  const retained = entries.slice(-(MAX_ACTIVITY_RECORDS - 1));
  retained.push(entry);
  await writeKey(KEY_ACTIVITY, retained);
}

export async function clearDeclaration(site: SiteId): Promise<void> {
  const stored = await readKey<DeclarationEntry[]>(KEY_DECLARATIONS, []);
  await writeKey(KEY_DECLARATIONS, stored.filter((entry) => entry.site !== site));
}
