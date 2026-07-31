import type { ContentCommand, NormalizedEvent, SiteId } from '../shared/types';
import { createAdapter, detectSite, type PageAdapter } from './adapters/base';
import { createEngagementTracker, type EngagementTracker } from './engagement';
import { classifyEntry } from './entry-provenance';
import { dismissIntentSurfaces, showFeedWall, showIntentPrompt } from './intent-prompt';
import { dismissOverlays, showNotice, showPauseOverlay } from './overlay';
import { createRouteWatcher } from './route-watcher';

let adapter: PageAdapter | undefined;
let activeSite: SiteId | undefined;
let engagement: EngagementTracker | undefined;

function currentSite(): SiteId | undefined {
  try {
    return detectSite(new URL(window.location.href));
  } catch {
    // An unparseable location is not a supported view.
    return undefined;
  }
}

function send(message: unknown): void {
  try {
    chrome.runtime.sendMessage(message);
  } catch {
    // A torn-down service worker must never break the page.
  }
}

function sendEvent(event: NormalizedEvent): void {
  send({ type: 'event', event });
}

function stopAdapter(): void {
  adapter?.stop();
  adapter = undefined;
  activeSite = undefined;
  engagement = undefined;
  dismissOverlays();
  dismissIntentSurfaces();
}

function syncToRoute(): void {
  const site = currentSite();
  if (site === activeSite) return;

  stopAdapter();
  if (!site) return;

  // Read once, on arrival, before anything has advanced: after the first
  // advance the URL no longer describes how this session started.
  const entryKind = classifyEntry({
    site,
    href: window.location.href,
    referrer: document.referrer,
  });

  const tracker = createEngagementTracker({
    now: () => Date.now(),
    getVideo: () => document.querySelector('video'),
  });

  const started = createAdapter(site, (partial) => {
    // Engagement is derived here and sent first, so the worker already has the
    // finished item when it evaluates the advance that ended it.
    if (partial.kind === 'scroll') tracker.noteGesture('scroll');
    else if (partial.kind === 'purposeful-action') tracker.noteGesture('click');
    else if (partial.kind === 'heartbeat') tracker.sample();
    else if (partial.kind === 'content-advance') {
      send({ type: 'engagement', site, record: tracker.finishItem() });
    }

    sendEvent({ site, ...partial });
  });
  if (!started) return;

  adapter = started;
  activeSite = site;
  engagement = tracker;
  send({ type: 'arrive', site, entryKind });
  started.start();
}

/** Only a well-formed command naming the site this tab is on is rendered. */
function isCommandForThisTab(value: unknown): value is ContentCommand {
  if (typeof value !== 'object' || value === null) return false;
  const command = value as Partial<ContentCommand>;
  if (command.site !== activeSite) return false;

  switch (command.type) {
    case 'notify':
    case 'pause':
    case 'wall':
      return typeof (command as { reason?: unknown }).reason === 'string';
    case 'prompt-intent':
      return typeof (command as { budgetMinutes?: unknown }).budgetMinutes === 'number';
    default:
      return false;
  }
}

chrome.runtime.onMessage.addListener((message: unknown) => {
  if (!isCommandForThisTab(message)) return;

  switch (message.type) {
    case 'notify':
      showNotice({ site: message.site, reason: message.reason });
      return;
    case 'pause':
      showPauseOverlay({
        site: message.site,
        reason: message.reason,
        allowContinue: message.allowContinue,
      });
      return;
    case 'prompt-intent':
      showIntentPrompt({ site: message.site, budgetMinutes: message.budgetMinutes });
      return;
    case 'wall':
      // The wall replaces whatever else is on screen: it is the end of this
      // session, not another thing to read past.
      dismissOverlays();
      showFeedWall({ site: message.site, reason: message.reason });
      engagement?.reset();
      return;
  }
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
