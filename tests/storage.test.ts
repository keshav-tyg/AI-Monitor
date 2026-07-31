import {
  addUsage,
  appendIntervention,
  clearDeclaration,
  getDeclaration,
  getUsage,
  listInterventions,
  saveDeclaration,
} from '../src/shared/storage';
import { installChromeStorageStub } from './chrome-storage';
import type { DeclarationEntry } from '../src/shared/types';

beforeEach(() => {
  installChromeStorageStub();
});

function declaration(overrides: Partial<DeclarationEntry> = {}): DeclarationEntry {
  return {
    site: 'instagram-reels',
    intent: 'doomscroll',
    entryKind: 'feed-entry',
    startedAt: 1_000,
    expiresAt: 61_000,
    usageAtStartMs: 0,
    ...overrides,
  };
}

describe('local persistence', () => {
  it('resets one site usage when the local day changes', async () => {
    await addUsage('instagram-reels', 120_000, Date.parse('2026-07-30T23:59:00'));
    expect(await getUsage('instagram-reels', Date.parse('2026-07-31T00:01:00'))).toBe(0);
  });

  it('keeps the newest 200 intervention records', async () => {
    for (let index = 0; index < 201; index += 1) {
      await appendIntervention({ id: String(index), at: index, site: 'x-timeline', kind: 'notify', reason: 'test' });
    }
    const records = await listInterventions();
    expect(records).toHaveLength(200);
    expect(records[0].id).toBe('1');
  });
});

describe('declarations', () => {
  it('round-trips a declaration for one site without touching another', async () => {
    await saveDeclaration(declaration());
    await saveDeclaration(declaration({ site: 'youtube-shorts', intent: 'purposeful' }));

    expect(await getDeclaration('instagram-reels', 2_000)).toMatchObject({ intent: 'doomscroll' });
    expect(await getDeclaration('youtube-shorts', 2_000)).toMatchObject({ intent: 'purposeful' });
    expect(await getDeclaration('x-timeline', 2_000)).toBeUndefined();
  });

  it('replaces the previous declaration for the same site', async () => {
    await saveDeclaration(declaration());
    await saveDeclaration(declaration({ intent: 'purposeful', startedAt: 5_000, expiresAt: 65_000 }));

    expect(await getDeclaration('instagram-reels', 6_000)).toMatchObject({
      intent: 'purposeful',
      startedAt: 5_000,
    });
  });

  it('treats an expired declaration as absent and prunes it', async () => {
    await saveDeclaration(declaration());

    expect(await getDeclaration('instagram-reels', 61_001)).toBeUndefined();
    // Pruned on read, so an abandoned declaration cannot accumulate forever.
    expect(await getDeclaration('instagram-reels', 2_000)).toBeUndefined();
  });

  it('clears a declaration on request', async () => {
    await saveDeclaration(declaration());
    await clearDeclaration('instagram-reels');
    expect(await getDeclaration('instagram-reels', 2_000)).toBeUndefined();
  });
});
