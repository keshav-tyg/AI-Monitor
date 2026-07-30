import type { NormalizedEvent, PurposefulDetail, SiteId } from '../../shared/types';
import { createInstagramAdapter } from './instagram';
import { createXAdapter } from './x';
import { createYouTubeAdapter } from './youtube';

/** Adapters emit everything except the site, which the caller already knows. */
export type Emit = (event: Omit<NormalizedEvent, 'site'>) => void;

export interface PageAdapter {
  site: SiteId;
  start(): void;
  stop(): void;
}

export interface AdapterDefinition {
  site: SiteId;
  /** True only on a confident signal that the current item changed. */
  advanced(): boolean;
  /** Undefined means "not recognised" — never a guess. */
  classifyClick(target: Element): PurposefulDetail | undefined;
}

const SCROLL_THROTTLE_MS = 750;

/**
 * Exact route guards. Anything outside these three views returns undefined so
 * the content script starts no observer at all.
 */
export function detectSite(url: URL): SiteId | undefined {
  const host = url.hostname.replace(/^www\./, '');
  const path = url.pathname;

  if (host === 'instagram.com') {
    return path.startsWith('/reels') ? 'instagram-reels' : undefined;
  }
  if (host === 'x.com' || host === 'twitter.com') {
    return path === '/home' ? 'x-timeline' : undefined;
  }
  if (host === 'youtube.com') {
    return path.startsWith('/shorts/') ? 'youtube-shorts' : undefined;
  }
  return undefined;
}

/**
 * Shared observer lifecycle. Every listener registered in `start` is removed
 * in `stop`, so leaving a supported view leaves no page hooks behind.
 */
export function createBaseAdapter(definition: AdapterDefinition, emit: Emit): PageAdapter {
  let lastScrollAt = 0;
  let observer: MutationObserver | undefined;
  let started = false;

  const onScroll = (): void => {
    const now = Date.now();
    if (now - lastScrollAt < SCROLL_THROTTLE_MS) return;
    lastScrollAt = now;
    emit({ kind: 'scroll', at: now });
  };

  const onClick = (event: Event): void => {
    const target = event.target;
    if (!(target instanceof Element)) return;
    const detail = definition.classifyClick(target);
    // An unrecognised click is not evidence of anything. Emit nothing.
    if (!detail) return;
    emit({ kind: 'purposeful-action', at: Date.now(), detail });
  };

  const checkAdvance = (): void => {
    if (!definition.advanced()) return;
    emit({ kind: 'content-advance', at: Date.now() });
  };

  return {
    site: definition.site,
    start(): void {
      if (started) return;
      started = true;
      emit({ kind: 'view-entered', at: Date.now() });
      window.addEventListener('scroll', onScroll, { passive: true });
      document.addEventListener('click', onClick, true);
      observer = new MutationObserver(checkAdvance);
      observer.observe(document.documentElement, { childList: true, subtree: true });
    },
    stop(): void {
      if (!started) return;
      started = false;
      window.removeEventListener('scroll', onScroll);
      document.removeEventListener('click', onClick, true);
      observer?.disconnect();
      observer = undefined;
      emit({ kind: 'view-left', at: Date.now() });
    },
  };
}

export function createAdapter(site: SiteId, emit: Emit): PageAdapter | undefined {
  switch (site) {
    case 'instagram-reels':
      return createInstagramAdapter(emit);
    case 'x-timeline':
      return createXAdapter(emit);
    case 'youtube-shorts':
      return createYouTubeAdapter(emit);
    default:
      return undefined;
  }
}

/** Shared by the two video feeds: a changed media source means a new item. */
export function createMediaAdvanceProbe(): () => boolean {
  let lastSource: string | undefined;
  return () => {
    const video = document.querySelector('video');
    // Selector gone (a site redesign) — report nothing rather than guess.
    if (!video) return false;
    const source = video.currentSrc || video.src;
    if (!source) return false;
    if (lastSource === undefined) {
      lastSource = source;
      return false;
    }
    if (source === lastSource) return false;
    lastSource = source;
    return true;
  };
}

/** A profile link looks like `/name`, but feed roots must not count. */
export function isProfilePath(href: string, reserved: readonly string[]): boolean {
  if (!/^\/[A-Za-z0-9._]+\/?$/.test(href)) return false;
  const name = href.replace(/\//g, '');
  return !reserved.includes(name);
}
