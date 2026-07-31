import type { EntryKind, SiteId } from '../shared/types';

export interface ArrivalInput {
  site: SiteId;
  /** `window.location.href` at arrival, before any advance. */
  href: string;
  /** `document.referrer`. Frequently empty, and that is expected. */
  referrer: string;
}

interface SiteProvenance {
  /** Every host that is still "this site" for referrer purposes. */
  hosts: readonly string[];
  /** True when the arrival URL named a specific item rather than the feed. */
  hasItemId(path: string): boolean;
  /** Referrer paths that mean the person searched or browsed to get here. */
  searchPaths: readonly string[];
  /** Referrer paths that are the feed itself. */
  feedPaths: readonly string[];
}

const PROVENANCE: Record<SiteId, SiteProvenance> = {
  'instagram-reels': {
    hosts: ['instagram.com'],
    hasItemId: (path) => /^\/reels?\/[^/]+/.test(path),
    searchPaths: ['/explore'],
    feedPaths: ['/reels'],
  },
  'x-timeline': {
    hosts: ['x.com', 'twitter.com'],
    // The timeline route carries no item id, ever.
    hasItemId: () => false,
    searchPaths: ['/search', '/explore'],
    feedPaths: ['/home'],
  },
  'youtube-shorts': {
    hosts: ['youtube.com'],
    hasItemId: (path) => /^\/shorts\/[^/]+/.test(path),
    searchPaths: ['/results', '/feed/explore'],
    feedPaths: ['/shorts'],
  },
};

function normalizeHost(url: URL): string {
  return url.hostname.replace(/^(www|m|mobile)\./, '');
}

function startsWithAny(path: string, prefixes: readonly string[]): boolean {
  return prefixes.some((prefix) => path === prefix || path.startsWith(`${prefix}/`));
}

/**
 * Deterministic arrival classification. Two signals only: the referrer, and the
 * shape of the URL at arrival.
 *
 * Every uncertain branch returns `deep-link`, the least restrictive answer.
 * Referrers are frequently empty — a link opened from another application or a
 * messaging app usually arrives with nothing — so treating uncertainty as a
 * feed entry would prompt people who did exactly what they meant to do. This
 * deliberately under-prompts; the model backstop catches what slips through.
 */
export function classifyEntry(input: ArrivalInput): EntryKind {
  const site = PROVENANCE[input.site];
  if (!site) return 'deep-link';

  let url: URL;
  try {
    url = new URL(input.href);
  } catch {
    return 'deep-link';
  }

  const referrer = input.referrer.trim();
  if (referrer === '') {
    // No referrer at all: an item id is the only remaining evidence that this
    // arrival was aimed at something specific.
    return site.hasItemId(url.pathname) ? 'deep-link' : 'feed-entry';
  }

  let from: URL;
  try {
    from = new URL(referrer);
  } catch {
    return 'deep-link';
  }

  if (!site.hosts.includes(normalizeHost(from))) return 'deep-link';
  if (startsWithAny(from.pathname, site.searchPaths)) return 'in-app-search';
  if (startsWithAny(from.pathname, site.feedPaths)) return 'feed-entry';

  // Same site, some other page — a profile, a story, a DM. Aimed at something.
  return 'deep-link';
}
