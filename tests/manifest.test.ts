import manifest from '../manifest.config';

describe('manifest privacy boundary', () => {
  it('contains only the permissions required for local enforcement', () => {
    expect(manifest.manifest_version).toBe(3);
    expect(manifest.permissions).toEqual([
      'storage', 'tabs', 'declarativeNetRequest', 'notifications',
    ]);
    expect(manifest.host_permissions).toEqual([
      '*://*.instagram.com/*', '*://x.com/*', '*://twitter.com/*', '*://*.youtube.com/*',
    ]);
    expect(JSON.stringify(manifest)).not.toMatch(/https?:\/\/[^*]/);
  });
});
