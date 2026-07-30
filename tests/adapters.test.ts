// @vitest-environment jsdom
import { createBaseAdapter, detectSite } from '../src/content/adapters/base';

afterEach(() => {
  vi.useRealTimers();
});

it.each([
  ['https://www.instagram.com/reels/', 'instagram-reels'],
  ['https://x.com/home', 'x-timeline'],
  ['https://twitter.com/home', 'x-timeline'],
  ['https://www.youtube.com/shorts/abc123', 'youtube-shorts'],
])('identifies only supported routes', (url, site) => {
  expect(detectSite(new URL(url))).toBe(site);
});

it.each(['https://www.instagram.com/direct/inbox/', 'https://x.com/messages', 'https://www.youtube.com/watch?v=abc'])('fails open for unsupported routes', (url) => {
  expect(detectSite(new URL(url))).toBeUndefined();
});

it('keeps an active supported view alive for usage accounting', () => {
  vi.useFakeTimers();
  const events: string[] = [];
  const adapter = createBaseAdapter(
    {
      site: 'instagram-reels',
      advanced: () => false,
      classifyClick: () => undefined,
    },
    (event) => events.push(event.kind),
  );

  adapter.start();
  vi.advanceTimersByTime(1_000);
  adapter.stop();

  expect(events).toContain('heartbeat');
});
