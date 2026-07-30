import { createRouteWatcher } from '../src/content/route-watcher';

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

it('reports a pushState navigation that fires no popstate event', () => {
  let href = 'https://x.com/home';
  const seen: string[] = [];
  const watcher = createRouteWatcher({
    getHref: () => href,
    onChange: (next) => seen.push(next),
    intervalMs: 500,
  });

  watcher.start();
  // What a single-page app does: swap the URL with no navigation event.
  href = 'https://x.com/someone';
  vi.advanceTimersByTime(500);

  expect(seen).toEqual(['https://x.com/someone']);
  watcher.stop();
});

it('stays quiet while the route is unchanged', () => {
  const seen: string[] = [];
  const watcher = createRouteWatcher({
    getHref: () => 'https://x.com/home',
    onChange: (next) => seen.push(next),
    intervalMs: 500,
  });

  watcher.start();
  vi.advanceTimersByTime(5_000);

  expect(seen).toEqual([]);
  watcher.stop();
});

it('stops polling once stopped', () => {
  let href = 'https://www.youtube.com/shorts/a';
  const seen: string[] = [];
  const watcher = createRouteWatcher({
    getHref: () => href,
    onChange: (next) => seen.push(next),
    intervalMs: 500,
  });

  watcher.start();
  watcher.stop();
  href = 'https://www.youtube.com/watch?v=b';
  vi.advanceTimersByTime(5_000);

  expect(seen).toEqual([]);
});
