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
