import type { ContentCommand, NormalizedEvent, SiteId } from '../shared/types';
import { createAdapter, detectSite, type PageAdapter } from './adapters/base';
import { dismissOverlays, showNotice, showPauseOverlay } from './overlay';
import { createRouteWatcher } from './route-watcher';

let adapter: PageAdapter | undefined;
let activeSite: SiteId | undefined;

function currentSite(): SiteId | undefined {
  try {
    return detectSite(new URL(window.location.href));
  } catch {
    // An unparseable location is not a supported view.
    return undefined;
  }
}

function sendEvent(event: NormalizedEvent): void {
  try {
    chrome.runtime.sendMessage({ type: 'event', event });
  } catch {
    // A torn-down service worker must never break the page.
  }
}

function stopAdapter(): void {
  adapter?.stop();
  adapter = undefined;
  activeSite = undefined;
  dismissOverlays();
}

function syncToRoute(): void {
  const site = currentSite();
  if (site === activeSite) return;

  stopAdapter();
  if (!site) return;

  const started = createAdapter(site, (partial) => {
    sendEvent({ site, ...partial });
  });
  if (!started) return;

  adapter = started;
  activeSite = site;
  started.start();
}

/** Only a well-formed command naming the site this tab is on is rendered. */
function isCommandForThisTab(value: unknown): value is ContentCommand {
  if (typeof value !== 'object' || value === null) return false;
  const command = value as Partial<ContentCommand>;
  if (command.type !== 'notify' && command.type !== 'pause') return false;
  if (command.site !== activeSite) return false;
  return typeof command.reason === 'string';
}

chrome.runtime.onMessage.addListener((message: unknown) => {
  if (!isCommandForThisTab(message)) return;

  if (message.type === 'notify') {
    showNotice({ site: message.site, reason: message.reason });
    return;
  }
  showPauseOverlay({
    site: message.site,
    reason: message.reason,
    allowContinue: message.allowContinue,
  });
});

// These feeds are single-page apps. `popstate` and `hashchange` catch only
// some transitions — pushState fires neither — so the watcher is the reliable
// signal and these two are just a faster path for the cases they do cover.
const routeWatcher = createRouteWatcher({
  getHref: () => window.location.href,
  onChange: syncToRoute,
});

window.addEventListener('popstate', syncToRoute);
window.addEventListener('hashchange', syncToRoute);
window.addEventListener('pagehide', () => {
  routeWatcher.stop();
  stopAdapter();
});

routeWatcher.start();
syncToRoute();
