export interface RouteWatcherOptions {
  getHref: () => string;
  onChange: (href: string) => void;
  intervalMs?: number;
}

export interface RouteWatcher {
  start(): void;
  stop(): void;
}

const DEFAULT_INTERVAL_MS = 1_000;

/**
 * These feeds navigate with `history.pushState`, which fires neither
 * `popstate` nor `hashchange`. A content script also cannot observe the page's
 * own History API calls, because it runs in an isolated world with its own
 * `history` object. Polling the URL is the one signal that survives both
 * facts, and it is what keeps an adapter from running on a route it does not
 * support.
 */
export function createRouteWatcher(options: RouteWatcherOptions): RouteWatcher {
  const intervalMs = options.intervalMs ?? DEFAULT_INTERVAL_MS;
  let lastHref: string | undefined;
  let timer: ReturnType<typeof setInterval> | undefined;

  const check = (): void => {
    const href = options.getHref();
    if (href === lastHref) return;
    lastHref = href;
    options.onChange(href);
  };

  return {
    start(): void {
      if (timer !== undefined) return;
      lastHref = options.getHref();
      timer = setInterval(check, intervalMs);
    },
    stop(): void {
      if (timer === undefined) return;
      clearInterval(timer);
      timer = undefined;
    },
  };
}
