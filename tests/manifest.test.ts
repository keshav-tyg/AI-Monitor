import manifest from '../manifest.config';

describe('manifest privacy boundary', () => {
  it('contains only the permissions required for local enforcement', () => {
    expect(manifest.manifest_version).toBe(3);
    // `alarms` earns its place: without it an until-tomorrow block can outlive
    // its expiry, because a blocked page loads no content script and so never
    // wakes the worker to clear the rule.
    expect(manifest.permissions).toEqual([
      'storage', 'tabs', 'declarativeNetRequest', 'notifications', 'alarms', 'offscreen',
    ]);
    expect(manifest.host_permissions).toEqual([
      '*://*.instagram.com/*', '*://x.com/*', '*://twitter.com/*', '*://*.youtube.com/*',
    ]);
    expect(JSON.stringify(manifest)).not.toMatch(/https?:\/\/[^*]/);
  });
});
