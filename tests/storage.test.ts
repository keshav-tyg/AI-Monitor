import { addUsage, appendIntervention, getUsage, listInterventions } from '../src/shared/storage';
import { installChromeStorageStub } from './chrome-storage';

beforeEach(() => {
  installChromeStorageStub();
});

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
