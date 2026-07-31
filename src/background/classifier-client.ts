import { CLASSIFIER } from '../shared/constants';
import type { ClassifierPayload, ClassifierResult } from '../shared/types';

/**
 * The worker's half of the classifier. It owns the offscreen document's
 * lifetime and the timeout, and it never touches `LanguageModel` itself —
 * that API does not exist in a service worker.
 *
 * Nothing here references `window`: this module is bundled into the worker,
 * where `window` is undefined and touching it kills the worker on load.
 */
const OFFSCREEN_JUSTIFICATION =
  'Runs the on-device language model, which is unavailable in service workers.';

let documentReady = false;
/** Latched once the model reports it is not there. No further documents. */
let modelUnavailable = false;

export function resetClassifierClient(): void {
  documentReady = false;
  modelUnavailable = false;
}

interface OffscreenApi {
  hasDocument?(): Promise<boolean>;
  createDocument(options: { url: string; reasons: string[]; justification: string }): Promise<void>;
  closeDocument?(): Promise<void>;
}

function offscreenApi(): OffscreenApi | undefined {
  return (globalThis as { chrome?: { offscreen?: OffscreenApi } }).chrome?.offscreen;
}

async function ensureDocument(): Promise<boolean> {
  const offscreen = offscreenApi();
  // Chrome without the offscreen API, or a stripped test environment.
  if (!offscreen?.createDocument) return false;
  if (documentReady) return true;

  try {
    if (await offscreen.hasDocument?.()) {
      documentReady = true;
      return true;
    }
    await offscreen.createDocument({
      url: CLASSIFIER.offscreenUrl,
      // The enum has no AI value; this is the member that actually describes
      // the situation — the work cannot run in the service worker.
      reasons: ['WORKERS'],
      justification: OFFSCREEN_JUSTIFICATION,
    });
    documentReady = true;
    return true;
  } catch {
    // A concurrent create loses this race. Either way, no classification.
    return false;
  }
}

async function closeDocument(): Promise<void> {
  documentReady = false;
  try {
    await offscreenApi()?.closeDocument?.();
  } catch {
    // Already gone.
  }
}

function withTimeout<T>(work: Promise<T>, ms: number): Promise<T | undefined> {
  return new Promise<T | undefined>((resolve) => {
    const timer = globalThis.setTimeout(() => resolve(undefined), ms);
    work
      .then((value) => {
        globalThis.clearTimeout(timer);
        resolve(value);
      })
      .catch(() => {
        globalThis.clearTimeout(timer);
        resolve(undefined);
      });
  });
}

function validate(value: unknown): ClassifierResult | undefined {
  if (typeof value !== 'object' || value === null) return undefined;
  const candidate = value as Partial<ClassifierResult>;
  if (candidate.verdict !== 'matches' && candidate.verdict !== 'contradicts') return undefined;
  if (typeof candidate.confidence !== 'number' || !Number.isFinite(candidate.confidence)) {
    return undefined;
  }
  if (candidate.confidence < 0 || candidate.confidence > 1) return undefined;
  if (typeof candidate.reason !== 'string') return undefined;
  return {
    verdict: candidate.verdict,
    confidence: candidate.confidence,
    reason: candidate.reason.slice(0, CLASSIFIER.maximumReasonLength),
  };
}

/**
 * `undefined` means "no answer", and every caller must treat that as the
 * declaration governing unchanged. There is no error path here that restricts
 * someone further than their own declaration already does.
 */
export async function classify(payload: ClassifierPayload): Promise<ClassifierResult | undefined> {
  if (modelUnavailable) return undefined;
  if (!(await ensureDocument())) return undefined;

  const runtime = (
    globalThis as { chrome?: { runtime?: { sendMessage?: (value: unknown) => Promise<unknown> } } }
  ).chrome?.runtime;
  if (!runtime?.sendMessage) return undefined;

  const response = (await withTimeout(
    Promise.resolve().then(() => runtime.sendMessage?.({ type: 'classify', payload })),
    CLASSIFIER.timeoutMs,
  )) as { ok?: boolean; availability?: string; result?: unknown } | undefined;

  if (!response) return undefined;

  if (response.availability === 'unavailable') {
    // Stop paying for a document that can never answer.
    modelUnavailable = true;
    await closeDocument();
    return undefined;
  }

  if (response.ok !== true) return undefined;
  return validate(response.result);
}
