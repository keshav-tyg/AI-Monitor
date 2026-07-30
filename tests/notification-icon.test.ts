import { existsSync, readFileSync } from 'node:fs';

const ICON_PATH = 'public/icons/icon-128.png';

it('packages the icon the notify intervention asks Chrome to render', () => {
  // chrome.notifications.create rejects a basic notification whose iconUrl
  // cannot be loaded, which silently swallowed every notify intervention.
  expect(existsSync(ICON_PATH)).toBe(true);

  const source = readFileSync('src/background/index.ts', 'utf8');
  const iconUrl = source.match(/iconUrl:\s*'([^']+)'/)?.[1];
  expect(iconUrl).toBeDefined();
  expect(existsSync(`public/${iconUrl}`)).toBe(true);
});

it('ships a real PNG, not an empty placeholder', () => {
  const bytes = readFileSync(ICON_PATH);
  expect(bytes.subarray(0, 8)).toEqual(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]));
  expect(bytes.byteLength).toBeGreaterThan(100);
});
