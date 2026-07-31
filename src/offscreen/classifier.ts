import { CLASSIFIER } from '../shared/constants';
import type { ClassifierPayload, ClassifierResult } from '../shared/types';

/**
 * The Prompt API is unavailable in workers, so it cannot run in the MV3 service
 * worker. This document exists only to host it, and it keeps one session warm
 * across worker teardowns.
 *
 * Ambient types are declared locally: `@types/chrome` does not describe
 * `LanguageModel`, and a global `.d.ts` would make it look available in the
 * worker, where it is not.
 */
type Availability = 'unavailable' | 'downloadable' | 'downloading' | 'available';

interface LanguageModelSession {
  prompt(input: string, options?: { responseConstraint?: unknown }): Promise<string>;
}

interface LanguageModelApi {
  availability(): Promise<Availability>;
  create(options: {
    initialPrompts?: { role: 'system'; content: string }[];
    expectedOutputs?: { type: 'text'; languages: string[] }[];
  }): Promise<LanguageModelSession>;
}

/** Output is schema-bound rather than parsed out of free text. */
const RESPONSE_SCHEMA = {
  type: 'object',
  properties: {
    verdict: { type: 'string', enum: ['matches', 'contradicts'] },
    confidence: { type: 'number', minimum: 0, maximum: 1 },
    reason: { type: 'string', maxLength: CLASSIFIER.maximumReasonLength },
  },
  required: ['verdict', 'confidence', 'reason'],
  additionalProperties: false,
} as const;

const SYSTEM_PROMPT = [
  'You judge one thing: whether feed behaviour matches what the person declared.',
  'You receive aggregate statistics only — never titles, captions, or identifiers.',
  '"doomscroll" means they admitted to scrolling; "purposeful" means they said they',
  'are looking for something specific.',
  'Answer "matches" when the statistics fit the declaration, "contradicts" when they do not.',
  'Long dwell times, high completion, replays, and unmuted playback indicate deliberate viewing.',
  'Short dwell, low completion, and many advances indicate passive scrolling.',
  'Keep the reason under 120 characters and state only what the numbers show.',
].join(' ');

function api(): LanguageModelApi | undefined {
  return (globalThis as { LanguageModel?: LanguageModelApi }).LanguageModel;
}

let session: LanguageModelSession | undefined;
/** One failed `create()` disables the classifier for the life of this document. */
let disabled = false;

/** Test seam. The document itself never needs to drop a healthy session. */
export function resetClassifierSession(): void {
  session = undefined;
  disabled = false;
}

async function ensureSession(): Promise<LanguageModelSession | undefined> {
  if (disabled) return undefined;
  if (session) return session;

  const model = api();
  if (!model) return undefined;

  try {
    // Anything short of `available` is treated as unavailable: a download in
    // progress must not stall an answer the declaration does not need.
    if ((await model.availability()) !== 'available') return undefined;

    session = await model.create({
      initialPrompts: [{ role: 'system', content: SYSTEM_PROMPT }],
      // Without a declared output language Chrome degrades output quality and
      // safety attestation.
      expectedOutputs: [{ type: 'text', languages: ['en'] }],
    });
    return session;
  } catch {
    disabled = true;
    return undefined;
  }
}

function parseResult(raw: string): ClassifierResult | undefined {
  let value: unknown;
  try {
    value = JSON.parse(raw);
  } catch {
    return undefined;
  }
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

export interface ClassifyResponse {
  ok: boolean;
  availability?: Availability;
  result?: ClassifierResult;
}

export async function handleClassify(payload: ClassifierPayload): Promise<ClassifyResponse> {
  const model = api();
  if (!model) return { ok: false, availability: 'unavailable' };

  const warm = await ensureSession();
  if (!warm) {
    // Report why, so the worker can stop creating this document at all.
    let availability: Availability = 'unavailable';
    try {
      availability = await model.availability();
    } catch {
      availability = 'unavailable';
    }
    return { ok: false, availability };
  }

  try {
    const raw = await warm.prompt(JSON.stringify(payload), {
      responseConstraint: RESPONSE_SCHEMA,
    });
    const result = parseResult(raw);
    return result ? { ok: true, result } : { ok: false };
  } catch {
    // A thrown prompt invalidates the warm session but not the document.
    session = undefined;
    return { ok: false };
  }
}

function registerListener(): void {
  const runtime = (globalThis as { chrome?: typeof chrome }).chrome?.runtime;
  if (!runtime?.onMessage) return;

  runtime.onMessage.addListener((message, _sender, sendResponse) => {
    const request = message as { type?: string; payload?: ClassifierPayload };
    // Every other extension message — events, status, settings — belongs to the
    // worker. Returning false leaves them entirely alone.
    if (request?.type !== 'classify' || !request.payload) return false;

    handleClassify(request.payload)
      .then(sendResponse)
      .catch(() => sendResponse({ ok: false }));
    return true;
  });
}

registerListener();
