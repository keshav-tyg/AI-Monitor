import { classifyEntry } from '../src/content/entry-provenance';
import type { EntryKind, SiteId } from '../src/shared/types';

interface Case {
  name: string;
  site: SiteId;
  href: string;
  referrer: string;
  expected: EntryKind;
}

const CASES: Case[] = [
  {
    name: 'an external referrer is a deep link',
    site: 'instagram-reels',
    href: 'https://www.instagram.com/reels/ABC123/',
    referrer: 'https://mail.google.com/mail/u/0/',
    expected: 'deep-link',
  },
  {
    name: 'an empty referrer with an item id is a deep link',
    site: 'instagram-reels',
    href: 'https://www.instagram.com/reels/ABC123/',
    referrer: '',
    expected: 'deep-link',
  },
  {
    name: 'an empty referrer with no item id is a feed entry',
    site: 'instagram-reels',
    href: 'https://www.instagram.com/reels/',
    referrer: '',
    expected: 'feed-entry',
  },
  {
    name: 'the timeline never carries an item id, so a bare arrival is a feed entry',
    site: 'x-timeline',
    href: 'https://x.com/home',
    referrer: '',
    expected: 'feed-entry',
  },
  {
    name: 'an in-app search referrer is in-app-search',
    site: 'youtube-shorts',
    href: 'https://www.youtube.com/shorts/xyz789',
    referrer: 'https://www.youtube.com/results?search_query=how+to+poach+an+egg',
    expected: 'in-app-search',
  },
  {
    name: 'an explore referrer is in-app-search',
    site: 'instagram-reels',
    href: 'https://www.instagram.com/reels/ABC123/',
    referrer: 'https://www.instagram.com/explore/',
    expected: 'in-app-search',
  },
  {
    name: 'the feed itself as referrer is a feed entry',
    site: 'youtube-shorts',
    href: 'https://www.youtube.com/shorts/xyz789',
    referrer: 'https://www.youtube.com/shorts/earlier123',
    expected: 'feed-entry',
  },
  {
    name: 'twitter.com counts as the same site as x.com',
    site: 'x-timeline',
    href: 'https://x.com/home',
    referrer: 'https://twitter.com/home',
    expected: 'feed-entry',
  },
  {
    name: 'another page on the same site is aimed at something',
    site: 'instagram-reels',
    href: 'https://www.instagram.com/reels/ABC123/',
    referrer: 'https://www.instagram.com/some.account/',
    expected: 'deep-link',
  },
  {
    name: 'an unparseable referrer fails toward legitimate',
    site: 'instagram-reels',
    href: 'https://www.instagram.com/reels/',
    referrer: 'not a url',
    expected: 'deep-link',
  },
  {
    name: 'an unparseable location fails toward legitimate',
    site: 'instagram-reels',
    href: 'nonsense',
    referrer: '',
    expected: 'deep-link',
  },
];

describe('entry provenance', () => {
  for (const testCase of CASES) {
    it(testCase.name, () => {
      expect(
        classifyEntry({
          site: testCase.site,
          href: testCase.href,
          referrer: testCase.referrer,
        }),
      ).toBe(testCase.expected);
    });
  }

  it('never returns feed-entry when an item was named and the referrer is unusable', () => {
    // The one direction that matters: uncertainty must not create a prompt.
    for (const referrer of ['', '   ', 'about:blank', 'chrome://newtab']) {
      expect(
        classifyEntry({
          site: 'youtube-shorts',
          href: 'https://www.youtube.com/shorts/xyz789',
          referrer,
        }),
      ).toBe('deep-link');
    }
  });
});
