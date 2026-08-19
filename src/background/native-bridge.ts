import {
  acceptDesktopSnapshot,
  loadDesktopSettingsSnapshot,
  parseServiceFocusSettingsMessage,
  toDesktopSettingsPayload,
  type DesktopSettingsPayload,
} from '../shared/desktop-settings';
import { extensionNativeMessage, NATIVE_PROTOCOL_VERSION } from '../shared/native-protocol';
import { legacySettingsForImport } from '../shared/storage';
import type { DesktopSettingsSnapshot } from '../shared/types';

const PRODUCTION_NATIVE_HOST_NAME = 'com.localfocuscoach.strict_mode';
const DEVELOPMENT_NATIVE_HOST_NAME = 'com.localfocuscoach.strict_mode_dev';
const HEARTBEAT_INTERVAL_MS = 5_000;
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000] as const;

export type NativeBridgeState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED';

type ReviewedNativeMessage =
  | ReturnType<typeof extensionNativeMessage>
  | {
      version: typeof NATIVE_PROTOCOL_VERSION;
      type: 'extension.focusSettings.sync';
      payload: { appliedRevision: number; legacySettings?: DesktopSettingsPayload };
    }
  | {
      version: typeof NATIVE_PROTOCOL_VERSION;
      type: 'extension.openDashboard';
      payload: Record<string, never>;
    };

let state: NativeBridgeState = 'DISCONNECTED';
let running = false;
let retryIndex = 0;
let activePort: chrome.runtime.Port | undefined;
let activeDisconnectListener: (() => void) | undefined;
let activeMessageListener: ((message: unknown) => void) | undefined;
let heartbeatTimer: ReturnType<typeof setInterval> | undefined;
let retryTimer: ReturnType<typeof setTimeout> | undefined;
let onSettingsSnapshot: (snapshot: DesktopSettingsSnapshot) => void | Promise<void> = () => undefined;

function clearHeartbeat(): void {
  if (heartbeatTimer !== undefined) clearInterval(heartbeatTimer);
  heartbeatTimer = undefined;
}

function clearRetry(): void {
  if (retryTimer !== undefined) clearTimeout(retryTimer);
  retryTimer = undefined;
}

function detachPort(port: chrome.runtime.Port): void {
  if (activeMessageListener) port.onMessage.removeListener(activeMessageListener);
  if (activeDisconnectListener) port.onDisconnect.removeListener(activeDisconnectListener);
  activeMessageListener = undefined;
  activeDisconnectListener = undefined;
}

function scheduleReconnect(): void {
  if (!running || retryTimer !== undefined) return;

  const delay = RETRY_DELAYS_MS[Math.min(retryIndex, RETRY_DELAYS_MS.length - 1)];
  retryIndex = Math.min(retryIndex + 1, RETRY_DELAYS_MS.length - 1);
  retryTimer = setTimeout(() => {
    retryTimer = undefined;
    connect();
  }, delay);
}

function handleDisconnect(port: chrome.runtime.Port): void {
  if (port !== activePort) return;

  clearHeartbeat();
  detachPort(port);
  activePort = undefined;
  state = 'DISCONNECTED';
  scheduleReconnect();
}

function post(port: chrome.runtime.Port, message: ReviewedNativeMessage): void {
  try {
    port.postMessage(message);
  } catch {
    handleDisconnect(port);
    try {
      port.disconnect();
    } catch {
      // The port is already unusable; the reconnect timer is sufficient.
    }
  }
}

function settingsSyncMessage(
  appliedRevision: number,
  legacySettings?: DesktopSettingsPayload,
): ReviewedNativeMessage {
  return {
    version: NATIVE_PROTOCOL_VERSION,
    type: 'extension.focusSettings.sync',
    payload: legacySettings === undefined
      ? { appliedRevision }
      : { appliedRevision, legacySettings },
  };
}

async function postSettingsSync(port: chrome.runtime.Port): Promise<void> {
  const cached = await loadDesktopSettingsSnapshot();
  let legacySettings: DesktopSettingsPayload | undefined;
  if (!cached) {
    try {
      const legacy = await legacySettingsForImport();
      legacySettings = legacy ? toDesktopSettingsPayload(legacy) : undefined;
    } catch {
      // Storage failure is indistinguishable from a fresh install for safety:
      // ask the service for its disabled defaults without migration data.
    }
  }
  if (!running || activePort !== port) return;
  post(port, settingsSyncMessage(cached?.revision ?? 0, legacySettings));
}

async function handleNativeMessage(port: chrome.runtime.Port, value: unknown): Promise<void> {
  const snapshot = parseServiceFocusSettingsMessage(value);
  if (!snapshot || activePort !== port) return;
  try {
    if (!(await acceptDesktopSnapshot(snapshot)) || activePort !== port) return;
    await onSettingsSnapshot(snapshot);
  } catch {
    // A failed cache write or consumer notification cannot weaken the last
    // applied settings and must not break the native connection.
  }
}

function connect(): void {
  if (!running || state !== 'DISCONNECTED') return;

  const runtime = (globalThis as { chrome?: typeof chrome }).chrome?.runtime;
  if (!runtime?.connectNative) return;

  state = 'CONNECTING';
  try {
    const manifest = runtime.getManifest();
    const hostName = typeof manifest.key === 'string' && manifest.key.length > 0
      ? PRODUCTION_NATIVE_HOST_NAME
      : DEVELOPMENT_NATIVE_HOST_NAME;
    const port = runtime.connectNative(hostName);
    const disconnectListener = (): void => handleDisconnect(port);
    const messageListener = (message: unknown): void => {
      void handleNativeMessage(port, message);
    };
    activePort = port;
    activeDisconnectListener = disconnectListener;
    activeMessageListener = messageListener;
    state = 'CONNECTED';

    port.onMessage.addListener(messageListener);
    port.onDisconnect.addListener(disconnectListener);
    post(port, extensionNativeMessage('extension.hello'));
    void postSettingsSync(port);

    if (activePort === port) {
      heartbeatTimer = setInterval(() => {
        post(port, extensionNativeMessage('extension.heartbeat'));
        void postSettingsSync(port);
      }, HEARTBEAT_INTERVAL_MS);
    }
  } catch {
    activePort = undefined;
    activeDisconnectListener = undefined;
    state = 'DISCONNECTED';
    scheduleReconnect();
  }
}

export function startNativeBridge(
  callback: (snapshot: DesktopSettingsSnapshot) => void | Promise<void> = () => undefined,
): void {
  if (running) return;
  onSettingsSnapshot = callback;
  running = true;
  connect();
}

export function requestOpenDashboard(): void {
  if (!activePort || state !== 'CONNECTED') return;
  post(activePort, {
    version: NATIVE_PROTOCOL_VERSION,
    type: 'extension.openDashboard',
    payload: {},
  });
}

export function stopNativeBridge(): void {
  running = false;
  clearHeartbeat();
  clearRetry();
  retryIndex = 0;
  onSettingsSnapshot = () => undefined;

  const port = activePort;
  activePort = undefined;
  state = 'DISCONNECTED';
  if (!port) return;

  detachPort(port);
  try {
    port.disconnect();
  } catch {
    // Stopping is idempotent even if Chrome already invalidated the port.
  }
}

export function resetNativeBridgeForTest(): void {
  stopNativeBridge();
}
