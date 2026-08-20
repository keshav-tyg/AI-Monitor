import { classify, resetClassifierClient } from '../src/background/classifier-client';
import { handleClassify, resetClassifierSession } from '../src/offscreen/classifier';
import type { ClassifierPayload } from '../src/shared/types';

const PAYLOAD: ClassifierPayload = {
  site: 'instagram-reels',
  declaredIntent: 'purposeful',
  entryKind: 'feed-entry',
  sessionMinutes: 6.2,
  itemCount: 24,
  medianDwellSeconds: 7.4,
  medianCompletion: 0.31,
  fullyWatchedCount: 2,
  unmutedCount: 1,
  replayCount: 0,
  purposefulActionCount: 0,
  scrollBurstCount: 5,
};

interface OffscreenSpies {
  createDocument: ReturnType<typeof vi.fn>;
  closeDocument: ReturnType<typeof vi.fn>;
  sendMessage: ReturnType<typeof vi.fn>;
}

function installChrome(
  respond: (message: unknown) => Promise<unknown>,
  options: { offscreen?: boolean } = {},
): OffscreenSpies {
  let open = false;
  const spies: OffscreenSpies = {
    createDocument: vi.fn(async () => {
      open = true;
    }),
    closeDocument: vi.fn(async () => {
      open = false;
    }),
    sendMessage: vi.fn(respond),
  };

  (globalThis as unknown as { chrome: Record<string, unknown> }).chrome = {
    runtime: { sendMessage: spies.sendMessage },
    ...(options.offscreen === false
      ? {}
      : {
          offscreen: {
            hasDocument: async () => open,
            createDocument: spies.createDocument,
            closeDocument: spies.closeDocument,
          },
        }),
  };

  return spies;
}

beforeEach(() => {
  resetClassifierClient();
  vi.useRealTimers();
});

describe('classifier client', () => {
  it('returns a well-formed verdict and creates the document once', async () => {
    const spies = installChrome(async () => ({
      ok: true,
      result: { verdict: 'contradicts', confidence: 0.85, reason: 'no item held attention' },
    }));

    expect(await classify(PAYLOAD)).toEqual({
      verdict: 'contradicts',
      confidence: 0.85,
      reason: 'no item held attention',
    });
    await classify(PAYLOAD);

    expect(spies.createDocument).toHaveBeenCalledTimes(1);
    expect(spies.createDocument).toHaveBeenCalledWith(
      expect.objectContaining({ url: 'src/offscreen/index.html' }),
    );
  });

  it('stops creating documents once the model reports it is unavailable', async () => {
    const spies = installChrome(async () => ({ ok: false, availability: 'unavailable' }));

    expect(await classify(PAYLOAD)).toBeUndefined();
    expect(await classify(PAYLOAD)).toBeUndefined();
    expect(await classify(PAYLOAD)).toBeUndefined();

    expect(spies.createDocument).toHaveBeenCalledTimes(1);
    expect(spies.closeDocument).toHaveBeenCalledTimes(1);
    expect(spies.sendMessage).toHaveBeenCalledTimes(1);
  });

  it('never creates a document when the browser has no offscreen API', async () => {
    const spies = installChrome(async () => ({ ok: true }), { offscreen: false });

    expect(await classify(PAYLOAD)).toBeUndefined();
    expect(spies.sendMessage).not.toHaveBeenCalled();
  });

  it('gives up when the answer takes longer than the budget', async () => {
    vi.useFakeTimers();
    installChrome(() => new Promise(() => undefined));

    const pending = classify(PAYLOAD);
    await vi.advanceTimersByTimeAsync(1_600);

    expect(await pending).toBeUndefined();
  });

  it('discards output that does not match the schema', async () => {
    const cases: unknown[] = [
      { ok: true, result: { verdict: 'maybe', confidence: 0.9, reason: 'x' } },
      { ok: true, result: { verdict: 'contradicts', confidence: 4, reason: 'x' } },
      { ok: true, result: { verdict: 'contradicts', confidence: 'high', reason: 'x' } },
      { ok: true, result: null },
      { ok: false },
      undefined,
    ];

    for (const response of cases) {
      resetClassifierClient();
      installChrome(async () => response);
      expect(await classify(PAYLOAD)).toBeUndefined();
    }
  });

  it('survives a runtime that throws', async () => {
    installChrome(async () => {
      throw new Error('receiving end does not exist');
    });

    expect(await classify(PAYLOAD)).toBeUndefined();
  });

  it('caps an over-long reason at the stored length', async () => {
    installChrome(async () => ({
      ok: true,
      result: { verdict: 'contradicts', confidence: 0.9, reason: 'x'.repeat(400) },
    }));

    const result = await classify(PAYLOAD);
    expect(result?.reason).toHaveLength(120);
  });
});

describe('offscreen classifier', () => {
  function installModel(model: unknown): void {
    (globalThis as unknown as { LanguageModel?: unknown }).LanguageModel = model;
  }

  beforeEach(() => {
    resetClassifierSession();
  });

  afterEach(() => {
    delete (globalThis as unknown as { LanguageModel?: unknown }).LanguageModel;
  });

  it('reports unavailable when the API is missing entirely', async () => {
    installModel(undefined);
    expect(await handleClassify(PAYLOAD)).toEqual({ ok: false, availability: 'unavailable' });
  });

  it('treats a downloading model as not ready', async () => {
    const create = vi.fn();
    const availability = vi.fn(async () => 'downloading');
    installModel({ availability, create });

    expect(await handleClassify(PAYLOAD)).toEqual({ ok: false, availability: 'downloading' });
    expect(create).not.toHaveBeenCalled();
    expect(availability).toHaveBeenCalledTimes(2);
    expect(availability).toHaveBeenNthCalledWith(1, {
      expectedInputs: [{ type: 'text', languages: ['en'] }],
      expectedOutputs: [{ type: 'text', languages: ['en'] }],
    });
    expect(availability).toHaveBeenNthCalledWith(2, {
      expectedInputs: [{ type: 'text', languages: ['en'] }],
      expectedOutputs: [{ type: 'text', languages: ['en'] }],
    });
  });

  it('declares English input and output languages before checking availability', async () => {
    const prompt = vi.fn(async () =>
      JSON.stringify({ verdict: 'matches', confidence: 0.7, reason: 'deliberate' }),
    );
    const create = vi.fn(async () => ({ prompt }));
    const availability = vi.fn(async () => 'available');
    installModel({ availability, create });

    const response = await handleClassify(PAYLOAD);

    expect(response.result).toEqual({ verdict: 'matches', confidence: 0.7, reason: 'deliberate' });
    expect(availability).toHaveBeenCalledWith({
      expectedInputs: [{ type: 'text', languages: ['en'] }],
      expectedOutputs: [{ type: 'text', languages: ['en'] }],
    });
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({
        expectedInputs: [{ type: 'text', languages: ['en'] }],
        expectedOutputs: [{ type: 'text', languages: ['en'] }],
      }),
    );
    expect(prompt).toHaveBeenCalledWith(
      JSON.stringify(PAYLOAD),
      expect.objectContaining({ responseConstraint: expect.any(Object) }),
    );
  });

  it('sends only the aggregate payload, never page-derived text', async () => {
    let sent = '';
    const prompt = vi.fn(async (input: string) => {
      sent = input;
      return JSON.stringify({ verdict: 'matches', confidence: 0.5, reason: 'ok' });
    });
    installModel({ availability: async () => 'available', create: async () => ({ prompt }) });

    await handleClassify(PAYLOAD);

    expect(JSON.parse(sent)).toEqual(PAYLOAD);
  });

  it('reuses the warm session across calls', async () => {
    const prompt = vi.fn(async () =>
      JSON.stringify({ verdict: 'matches', confidence: 0.5, reason: 'ok' }),
    );
    const create = vi.fn(async () => ({ prompt }));
    installModel({ availability: async () => 'available', create });

    await handleClassify(PAYLOAD);
    await handleClassify(PAYLOAD);

    expect(create).toHaveBeenCalledTimes(1);
    expect(prompt).toHaveBeenCalledTimes(2);
  });

  it('rejects output that is not valid JSON', async () => {
    const prompt = vi.fn(async () => 'I think they are doomscrolling');
    installModel({ availability: async () => 'available', create: async () => ({ prompt }) });

    expect(await handleClassify(PAYLOAD)).toEqual({ ok: false });
  });

  it('drops a session whose prompt threw, without disabling the document', async () => {
    const prompt = vi
      .fn()
      .mockRejectedValueOnce(new Error('session destroyed'))
      .mockResolvedValueOnce(JSON.stringify({ verdict: 'matches', confidence: 0.5, reason: 'ok' }));
    const create = vi.fn(async () => ({ prompt }));
    installModel({ availability: async () => 'available', create });

    expect(await handleClassify(PAYLOAD)).toEqual({ ok: false });
    expect((await handleClassify(PAYLOAD)).result).toBeTruthy();
    expect(create).toHaveBeenCalledTimes(2);
  });
});
