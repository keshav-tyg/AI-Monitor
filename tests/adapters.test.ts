import { detectSite } from '../src/content/adapters/base';

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
