/**
 * @vitest-environment jsdom
 * @vitest-environment-options {"url":"https://www.youtube.com/"}
 */

interface RegisteredWindowListener {
  type: string;
  listener: EventListenerOrEventListenerObject;
  options?: boolean | AddEventListenerOptions;
}

let sendMessage: ReturnType<typeof vi.fn>;
let registeredWindowListeners: RegisteredWindowListener[];
let addEventListenerSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.resetModules();
  vi.useFakeTimers();
  window.history.replaceState(null, '', '/');
  document.body.replaceChildren();

  sendMessage = vi.fn();
  (globalThis as unknown as { chrome: unknown }).chrome = {
    runtime: {
      sendMessage,
      onMessage: { addListener: vi.fn() },
    },
  };

  registeredWindowListeners = [];
  const addEventListener = window.addEventListener.bind(window);
  addEventListenerSpy = vi.spyOn(window, 'addEventListener').mockImplementation(
    (type: string, listener: EventListenerOrEventListenerObject, options?: boolean | AddEventListenerOptions) => {
      registeredWindowListeners.push({ type, listener, options });
      addEventListener(type, listener, options);
    },
  );
});

afterEach(() => {
  window.dispatchEvent(new Event('pagehide'));
  for (const { type, listener, options } of registeredWindowListeners) {
    window.removeEventListener(type, listener, options);
  }
  addEventListenerSpy.mockRestore();
  vi.clearAllTimers();
  vi.useRealTimers();
});

async function loadContentScript(path: string): Promise<void> {
  window.history.replaceState(null, '', path);
  await import('../src/content/content-script');
}

it.each(['/shorts/', '/shorts//first'])(
  'does not activate or emit for malformed YouTube Shorts route %s',
  async (path) => {
    await loadContentScript(path);

    expect(registeredWindowListeners.some(({ type }) => type === 'scroll')).toBe(false);
    expect(sendMessage).not.toHaveBeenCalled();
  },
);

it('records a changed Short before engagement and one advance emission', async () => {
  await loadContentScript('/shorts/short-id-a');

  expect(sendMessage).toHaveBeenNthCalledWith(1, {
    type: 'arrive',
    site: 'youtube-shorts',
    entryKind: 'deep-link',
  });
  expect(sendMessage.mock.calls).not.toContainEqual([
    expect.objectContaining({
      type: 'event',
      event: expect.objectContaining({ kind: 'content-advance' }),
    }),
  ]);

  sendMessage.mockClear();
  let repeatedDuringFirstEmission = false;
  sendMessage.mockImplementation((message: unknown) => {
    if (
      !repeatedDuringFirstEmission
      && typeof message === 'object'
      && message !== null
      && (message as { type?: unknown }).type === 'engagement'
    ) {
      repeatedDuringFirstEmission = true;
      window.dispatchEvent(new PopStateEvent('popstate'));
    }
  });

  window.history.replaceState(null, '', '/shorts/short-id-b');
  window.dispatchEvent(new PopStateEvent('popstate'));
  window.dispatchEvent(new PopStateEvent('popstate'));

  const messages = sendMessage.mock.calls.map(([message]) => message);
  expect(messages).toHaveLength(2);
  expect(messages[0]).toMatchObject({
    type: 'engagement',
    site: 'youtube-shorts',
    record: { advancedBy: 'auto' },
  });
  expect(messages[1]).toMatchObject({
    type: 'event',
    event: { site: 'youtube-shorts', kind: 'content-advance' },
  });
  expect(JSON.stringify(messages)).not.toContain('short-id-a');
  expect(JSON.stringify(messages)).not.toContain('short-id-b');
});
