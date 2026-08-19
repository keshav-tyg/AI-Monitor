import { DEFAULT_SETTINGS, SITE_IDS } from './constants';
import type {
  DesktopSettingsSnapshot,
  InterventionKind,
  Settings,
  SiteId,
  SiteRule,
} from './types';

const KEY_DESKTOP_SETTINGS_SNAPSHOT = 'desktop-settings-snapshot';

const INTERVENTIONS: readonly InterventionKind[] = ['notify', 'pause', 'close-tab', 'block'];
const SETTINGS_KEYS = ['enabled', 'rules'] as const;
const CACHED_RULE_KEYS = [
  'enabled',
  'warningScore',
  'gracePeriodSeconds',
  'doomscrollBudgetMinutes',
  'interventions',
  'blockUntil',
] as const;
const DESKTOP_RULE_KEYS = [
  'enabled',
  'warningScore',
  'gracePeriodSeconds',
  'doomscrollBudgetMinutes',
  'interventions',
] as const;

export interface DesktopSettingsPayload {
  enabled: boolean;
  rules: Record<SiteId, Omit<SiteRule, 'blockUntil'>>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const actual = Object.keys(value);
  return actual.length === keys.length && keys.every((key) => Object.hasOwn(value, key));
}

function integerInRange(value: unknown, minimum: number, maximum: number): value is number {
  return Number.isSafeInteger(value) && (value as number) >= minimum && (value as number) <= maximum;
}

function parseInterventions(value: unknown): InterventionKind[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const parsed: InterventionKind[] = [];
  for (const item of value) {
    if (typeof item !== 'string' || !INTERVENTIONS.includes(item as InterventionKind)) {
      return undefined;
    }
    const intervention = item as InterventionKind;
    if (parsed.includes(intervention)) return undefined;
    parsed.push(intervention);
  }
  return parsed;
}

function parseRule(value: unknown, desktopPayload: boolean): SiteRule | undefined {
  if (!isRecord(value)) return undefined;
  const keys = desktopPayload ? DESKTOP_RULE_KEYS : CACHED_RULE_KEYS;
  if (!hasExactKeys(value, keys)) return undefined;
  if (typeof value['enabled'] !== 'boolean') return undefined;
  if (!integerInRange(value['doomscrollBudgetMinutes'], 1, 60)) return undefined;
  if (!integerInRange(value['warningScore'], 1, 50)) return undefined;
  if (!integerInRange(value['gracePeriodSeconds'], 0, 600)) return undefined;
  if (!desktopPayload && value['blockUntil'] !== 'tomorrow') return undefined;

  const interventions = parseInterventions(value['interventions']);
  if (!interventions || (value['enabled'] && interventions.length === 0)) return undefined;

  return {
    enabled: value['enabled'],
    warningScore: value['warningScore'],
    gracePeriodSeconds: value['gracePeriodSeconds'],
    doomscrollBudgetMinutes: value['doomscrollBudgetMinutes'],
    interventions,
    blockUntil: 'tomorrow',
  };
}

function parseSettings(value: unknown, desktopPayload: boolean): Settings | undefined {
  if (!isRecord(value) || !hasExactKeys(value, SETTINGS_KEYS)) return undefined;
  if (typeof value['enabled'] !== 'boolean') return undefined;
  const rawRules = value['rules'];
  if (!isRecord(rawRules) || !hasExactKeys(rawRules, SITE_IDS)) return undefined;

  const rules = {} as Record<SiteId, SiteRule>;
  for (const site of SITE_IDS) {
    const rule = parseRule(rawRules[site], desktopPayload);
    if (!rule) return undefined;
    rules[site] = rule;
  }
  return { enabled: value['enabled'], rules };
}

function parseRevision(value: unknown): number | undefined {
  return Number.isSafeInteger(value) && (value as number) > 0 ? (value as number) : undefined;
}

export function parseDesktopSettingsSnapshot(value: unknown): DesktopSettingsSnapshot | undefined {
  if (!isRecord(value) || !hasExactKeys(value, ['revision', 'settings'])) return undefined;
  const revision = parseRevision(value['revision']);
  const settings = parseSettings(value['settings'], false);
  return revision === undefined || !settings ? undefined : { revision, settings };
}

/** Converts the exact native service frame into the extension's fixed rule shape. */
export function parseServiceFocusSettingsMessage(
  value: unknown,
): DesktopSettingsSnapshot | undefined {
  if (!isRecord(value) || !hasExactKeys(value, ['version', 'type', 'payload'])) return undefined;
  if (value['version'] !== 1 || value['type'] !== 'service.focusSettings') return undefined;
  const payload = value['payload'];
  if (
    !isRecord(payload)
    || !hasExactKeys(payload, ['revision', 'settings', 'chromeAppliedRevision'])
    || !Number.isSafeInteger(payload['chromeAppliedRevision'])
    || (payload['chromeAppliedRevision'] as number) < 0
  ) {
    return undefined;
  }
  const revision = parseRevision(payload['revision']);
  const settings = parseSettings(payload['settings'], true);
  return revision === undefined || !settings ? undefined : { revision, settings };
}

export type DesktopSettingsCacheRead =
  | { readable: true; snapshot: DesktopSettingsSnapshot | undefined }
  | { readable: false };

export async function readDesktopSettingsCache(): Promise<DesktopSettingsCacheRead> {
  try {
    const stored = await chrome.storage.local.get(KEY_DESKTOP_SETTINGS_SNAPSHOT);
    return {
      readable: true,
      snapshot: parseDesktopSettingsSnapshot(
        (stored as Record<string, unknown>)[KEY_DESKTOP_SETTINGS_SNAPSHOT],
      ),
    };
  } catch {
    return { readable: false };
  }
}

/** Fail-open read for enforcement and display; revisioned writes use the read result above. */
export async function loadDesktopSettingsSnapshot(): Promise<DesktopSettingsSnapshot | undefined> {
  const cached = await readDesktopSettingsCache();
  return cached.readable ? cached.snapshot : undefined;
}

export async function saveDesktopSettingsSnapshot(
  value: DesktopSettingsSnapshot,
): Promise<void> {
  const snapshot = parseDesktopSettingsSnapshot(value);
  if (!snapshot) throw new TypeError('Invalid desktop settings snapshot');
  await chrome.storage.local.set({ [KEY_DESKTOP_SETTINGS_SNAPSHOT]: snapshot });
}

let acceptanceTail: Promise<void> = Promise.resolve();

export async function acceptDesktopSnapshot(value: unknown): Promise<boolean> {
  const snapshot = parseDesktopSettingsSnapshot(value);
  if (!snapshot) return false;

  let accepted = false;
  const operation = acceptanceTail.then(async () => {
    const cached = await readDesktopSettingsCache();
    if (!cached.readable) return;
    if (cached.snapshot && snapshot.revision <= cached.snapshot.revision) return;
    await saveDesktopSettingsSnapshot(snapshot);
    accepted = true;
  });
  acceptanceTail = operation.catch(() => undefined);
  await operation;
  return accepted;
}

export function cloneDefaultSettings(): Settings {
  const rules = {} as Record<SiteId, SiteRule>;
  for (const site of SITE_IDS) {
    const rule = DEFAULT_SETTINGS.rules[site];
    rules[site] = { ...rule, interventions: [...rule.interventions] };
  }
  return { enabled: DEFAULT_SETTINGS.enabled, rules };
}

/**
 * Reads the old browser-owned shape while dropping retired keys. Invalid known
 * values abort migration so the desktop service can materialize safe defaults.
 */
export function normalizeLegacySettings(value: unknown): Settings | undefined {
  if (!isRecord(value)) return undefined;
  const defaults = cloneDefaultSettings();
  const rawRules = isRecord(value['rules']) ? value['rules'] : {};
  const normalizedRules: Record<string, unknown> = {};

  for (const site of SITE_IDS) {
    const rawRule = isRecord(rawRules[site]) ? rawRules[site] : {};
    const fallback = defaults.rules[site];
    normalizedRules[site] = {
      enabled: rawRule['enabled'] ?? fallback.enabled,
      warningScore: rawRule['warningScore'] ?? fallback.warningScore,
      gracePeriodSeconds: rawRule['gracePeriodSeconds'] ?? fallback.gracePeriodSeconds,
      doomscrollBudgetMinutes:
        rawRule['doomscrollBudgetMinutes'] ?? fallback.doomscrollBudgetMinutes,
      interventions: rawRule['interventions'] ?? fallback.interventions,
      blockUntil: 'tomorrow',
    };
  }

  return parseSettings(
    {
      enabled: value['enabled'] ?? defaults.enabled,
      rules: normalizedRules,
    },
    false,
  );
}

export function toDesktopSettingsPayload(settings: Settings): DesktopSettingsPayload {
  const validated = parseSettings(settings, false);
  if (!validated) throw new TypeError('Invalid legacy settings');

  const rules = {} as Record<SiteId, Omit<SiteRule, 'blockUntil'>>;
  for (const site of SITE_IDS) {
    const rule = validated.rules[site];
    rules[site] = {
      enabled: rule.enabled,
      warningScore: rule.warningScore,
      gracePeriodSeconds: rule.gracePeriodSeconds,
      doomscrollBudgetMinutes: rule.doomscrollBudgetMinutes,
      interventions: [...rule.interventions],
    };
  }
  return { enabled: validated.enabled, rules };
}
