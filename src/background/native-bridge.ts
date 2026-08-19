import { extensionNativeMessage } from '../shared/native-protocol';

const PRODUCTION_NATIVE_HOST_NAME = 'com.localfocuscoach.strict_mode';
const DEVELOPMENT_NATIVE_HOST_NAME = 'com.localfocuscoach.strict_mode_dev';
const HEARTBEAT_INTERVAL_MS = 5_000;
const RETRY_DELAYS_MS = [1_000, 2_000, 4_000, 8_000, 15_000] as const;

export type NativeBridgeState = 'DISCONNECTED' | 'CONNECTING' | 'CONNECTED';

let state: NativeBridgeState = 'DISCONNECTED';
let running = false;
let retryIndex = 0;
let activePort: chrome.runtime.Port | undefined;
let activeDisconnectListener: (() => void) | undefined;
let heartbeatTimer: ReturnType<typeof setInterval> | undefined;
let retryTimer: ReturnType<typeof setTimeout> | undefined;

function clearHeartbeat(): void {
  if (heartbeatTimer !== undefined) clearInterval(heartbeatTimer);
  heartbeatTimer = undefined;
}

function clearRetry(): void {
  if (retryTimer !== undefined) clearTimeout(retryTimer);
  retryTimer = undefined;
}

function ignoreNativeMessage(_message: unknown): void {
  // The native connection is an integrity signal only. Browser state never
  // changes in response to host messages.
}

function detachPort(port: chrome.runtime.Port): void {
  port.onMessage.removeListener(ignoreNativeMessage);
  if (activeDisconnectListener) port.onDisconnect.removeListener(activeDisconnectListener);
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

function post(port: chrome.runtime.Port, type: 'extension.hello' | 'extension.heartbeat'): void {
  try {
    port.postMessage(extensionNativeMessage(type));
  } catch {
    handleDisconnect(port);
    try {
      port.disconnect();
    } catch {
      // The port is already unusable; the reconnect timer is sufficient.
    }
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
    activePort = port;
    activeDisconnectListener = disconnectListener;
    state = 'CONNECTED';

    port.onMessage.addListener(ignoreNativeMessage);
    port.onDisconnect.addListener(disconnectListener);
    post(port, 'extension.hello');

    if (activePort === port) {
      heartbeatTimer = setInterval(() => post(port, 'extension.heartbeat'), HEARTBEAT_INTERVAL_MS);
    }
  } catch {
    activePort = undefined;
    activeDisconnectListener = undefined;
    state = 'DISCONNECTED';
    scheduleReconnect();
  }
}

export function startNativeBridge(): void {
  if (running) return;
  running = true;
  connect();
}

export function stopNativeBridge(): void {
  running = false;
  clearHeartbeat();
  clearRetry();
  retryIndex = 0;

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
