import type { PurposefulDetail } from '../../shared/types';
import {
  createBaseAdapter,
  createMediaAdvanceProbe,
  isProfilePath,
  type Emit,
  type PageAdapter,
} from './base';

const RESERVED = ['reels', 'explore', 'direct', 'accounts', 'stories'];

function classifyClick(target: Element): PurposefulDetail | undefined {
  const anchor = target.closest('a');
  const href = anchor?.getAttribute('href') ?? '';

  if (href.startsWith('/explore')) return 'search';
  if (href.startsWith('/direct')) return 'message';
  if (href.startsWith('/p/')) return 'post';
  if (isProfilePath(href, RESERVED)) return 'profile';

  const label = target.closest('[aria-label]')?.getAttribute('aria-label')?.toLowerCase() ?? '';
  if (label.includes('comment')) return 'comment';
  if (label.includes('save')) return 'save';
  if (label.includes('search')) return 'search';

  return anchor ? 'link' : undefined;
}

export function createInstagramAdapter(emit: Emit): PageAdapter {
  return createBaseAdapter(
    { site: 'instagram-reels', advanced: createMediaAdvanceProbe(), classifyClick },
    emit,
  );
}
