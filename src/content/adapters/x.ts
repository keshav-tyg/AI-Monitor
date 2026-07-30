import type { PurposefulDetail } from '../../shared/types';
import { createBaseAdapter, isProfilePath, type Emit, type PageAdapter } from './base';

const RESERVED = ['home', 'explore', 'search', 'messages', 'notifications', 'compose', 'i'];

function classifyClick(target: Element): PurposefulDetail | undefined {
  const anchor = target.closest('a');
  const href = anchor?.getAttribute('href') ?? '';

  if (href.startsWith('/search') || href.startsWith('/explore')) return 'search';
  if (href.startsWith('/messages')) return 'message';
  if (href.startsWith('/compose') || href.includes('/status/')) return 'post';
  if (isProfilePath(href, RESERVED)) return 'profile';

  const label = target.closest('[aria-label]')?.getAttribute('aria-label')?.toLowerCase() ?? '';
  if (label.includes('reply')) return 'comment';
  if (label.includes('bookmark')) return 'save';
  if (label.includes('search')) return 'search';

  return anchor ? 'link' : undefined;
}

/**
 * The timeline has no per-item media to watch, so an advance is a scroll that
 * moved at least a full viewport — a conservative stand-in for "next item".
 */
function createTimelineAdvanceProbe(): () => boolean {
  let lastY: number | undefined;
  return () => {
    const viewport = window.innerHeight;
    if (!viewport) return false;
    const y = window.scrollY;
    if (lastY === undefined) {
      lastY = y;
      return false;
    }
    if (Math.abs(y - lastY) < viewport) return false;
    lastY = y;
    return true;
  };
}

export function createXAdapter(emit: Emit): PageAdapter {
  return createBaseAdapter(
    { site: 'x-timeline', advanced: createTimelineAdvanceProbe(), classifyClick },
    emit,
  );
}
