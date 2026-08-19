/**
 * In-memory stand-in for `chrome.storage.local`.
 *
 * Merges into any existing `globalThis.chrome` so later suites can layer
 * `tabs` / `runtime` / `declarativeNetRequest` spies on top without losing
 * storage behaviour.
 */
export function installChromeStorageStub(): Map<string, unknown> {
  const store = new Map<string, unknown>();

  const local = {
    async get(keys?: string | string[] | null): Promise<Record<string, unknown>> {
      if (keys === undefined || keys === null) {
        return Object.fromEntries(store);
      }
      const wanted = Array.isArray(keys) ? keys : [keys];
      const result: Record<string, unknown> = {};
      for (const key of wanted) {
        if (store.has(key)) result[key] = store.get(key);
      }
      return result;
    },
    async set(items: Record<string, unknown>): Promise<void> {
      for (const [key, value] of Object.entries(items)) {
        // Structured-clone semantics: callers must not keep a live reference.
        store.set(key, JSON.parse(JSON.stringify(value)));
      }
    },
    async remove(keys: string | string[]): Promise<void> {
      for (const key of Array.isArray(keys) ? keys : [keys]) store.delete(key);
    },
    async clear(): Promise<void> {
      store.clear();
    },
  };

  // `as unknown as` on purpose: the ambient @types/chrome global demands the
  // full LocalStorageArea surface, and the stub deliberately implements only
  // the three methods this extension calls.
  const globalWithChrome = globalThis as unknown as { chrome?: Record<string, unknown> };
  const existing = globalWithChrome.chrome ?? {};
  globalWithChrome.chrome = {
    ...existing,
    storage: { ...(existing['storage'] as object | undefined), local },
  };

  return store;
}

export interface ChromeApiSpies {
  tabsRemove: ReturnType<typeof vi.fn>;
  tabsSendMessage: ReturnType<typeof vi.fn>;
  notificationsCreate: ReturnType<typeof vi.fn>;
  getDynamicRules: ReturnType<typeof vi.fn>;
  updateDynamicRules: ReturnType<typeof vi.fn>;
  alarmsCreate: ReturnType<typeof vi.fn>;
  alarmsClear: ReturnType<typeof vi.fn>;
  connectNative: ReturnType<typeof vi.fn>;
  nativePorts: FakeNativePort[];
}

type NativeMessageListener = (message: unknown) => void;
type NativeDisconnectListener = () => void;

export interface FakeNativePort {
  postMessage: ReturnType<typeof vi.fn>;
  disconnect: ReturnType<typeof vi.fn>;
  onMessage: {
    addListener: ReturnType<typeof vi.fn>;
    removeListener: ReturnType<typeof vi.fn>;
  };
  onDisconnect: {
    addListener: ReturnType<typeof vi.fn>;
    removeListener: ReturnType<typeof vi.fn>;
  };
  emitMessage(message: unknown): void;
  emitDisconnect(): void;
}

function createFakeNativePort(): FakeNativePort {
  const messageListeners = new Set<NativeMessageListener>();
  const disconnectListeners = new Set<NativeDisconnectListener>();

  return {
    postMessage: vi.fn(),
    disconnect: vi.fn(),
    onMessage: {
      addListener: vi.fn((listener: NativeMessageListener) => messageListeners.add(listener)),
      removeListener: vi.fn((listener: NativeMessageListener) => messageListeners.delete(listener)),
    },
    onDisconnect: {
      addListener: vi.fn((listener: NativeDisconnectListener) => disconnectListeners.add(listener)),
      removeListener: vi.fn((listener: NativeDisconnectListener) => disconnectListeners.delete(listener)),
    },
    emitMessage(message: unknown): void {
      for (const listener of messageListeners) listener(message);
    },
    emitDisconnect(): void {
      for (const listener of disconnectListeners) listener();
    },
  };
}

/**
 * Layers enforcement-API spies over whatever `chrome` already exists, so a
 * suite can assert that a fail-open path touched none of them.
 */
export function installChromeApiSpies(): ChromeApiSpies {
  const nativePorts: FakeNativePort[] = [];
  const spies: ChromeApiSpies = {
    tabsRemove: vi.fn(async () => undefined),
    tabsSendMessage: vi.fn(async () => undefined),
    notificationsCreate: vi.fn(async () => 'notification-id'),
    getDynamicRules: vi.fn(async () => []),
    updateDynamicRules: vi.fn(async () => undefined),
    alarmsCreate: vi.fn(() => undefined),
    alarmsClear: vi.fn(async () => true),
    connectNative: vi.fn(() => {
      const port = createFakeNativePort();
      nativePorts.push(port);
      return port as unknown as chrome.runtime.Port;
    }),
    nativePorts,
  };

  const globalWithChrome = globalThis as unknown as { chrome?: Record<string, unknown> };
  const existing = globalWithChrome.chrome ?? {};

  globalWithChrome.chrome = {
    ...existing,
    runtime: {
      onMessage: { addListener: vi.fn() },
      sendMessage: vi.fn(),
      connectNative: spies.connectNative,
    },
    tabs: { remove: spies.tabsRemove, sendMessage: spies.tabsSendMessage },
    notifications: { create: spies.notificationsCreate },
    declarativeNetRequest: {
      getDynamicRules: spies.getDynamicRules,
      updateDynamicRules: spies.updateDynamicRules,
    },
    alarms: {
      create: spies.alarmsCreate,
      clear: spies.alarmsClear,
      onAlarm: { addListener: vi.fn() },
    },
  };

  return spies;
}
