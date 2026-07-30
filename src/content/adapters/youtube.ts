import type { PurposefulDetail } from '../../shared/types';
import {
  createBaseAdapter,
  createMediaAdvanceProbe,
  type Emit,
  type PageAdapter,
} from './base';

function classifyClick(target: Element): PurposefulDetail | undefined {
  const anchor = target.closest('a');
  const href = anchor?.getAttribute('href') ?? '';

  if (href.startsWith('/results')) return 'search';
  if (href.startsWith('/@') || href.startsWith('/channel/') || href.startsWith('/c/')) {
    return 'profile';
  }
  if (href.startsWith('/watch')) return 'post';

  const label = target.closest('[aria-label]')?.getAttribute('aria-label')?.toLowerCase() ?? '';
  if (label.includes('comment')) return 'comment';
  if (label.includes('save')) return 'save';
  if (label.includes('search')) return 'search';

  return anchor ? 'link' : undefined;
}

export function createYouTubeAdapter(emit: Emit): PageAdapter {
  return createBaseAdapter(
    { site: 'youtube-shorts', advanced: createMediaAdvanceProbe(), classifyClick },
    emit,
  );
}
