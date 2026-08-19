import { generateKeyPairSync } from 'node:crypto';
import manifest, { createManifest, productionIdentity } from '../manifest.config';

describe('manifest privacy boundary', () => {
  it('contains only the permissions required for local enforcement', () => {
    expect(manifest.manifest_version).toBe(3);
    // `alarms` earns its place: without it an until-tomorrow block can outlive
    // its expiry, because a blocked page loads no content script and so never
    // wakes the worker to clear the rule.
    expect(manifest.permissions).toEqual([
      'storage', 'tabs', 'declarativeNetRequest', 'notifications', 'alarms', 'offscreen',
      'nativeMessaging',
    ]);
    expect(manifest.host_permissions).toEqual([
      '*://*.instagram.com/*', '*://x.com/*', '*://twitter.com/*', '*://*.youtube.com/*',
    ]);
    expect(JSON.stringify(manifest)).not.toMatch(/https?:\/\/[^*]/);
  });

  it('keeps development builds separate from the production extension identity', () => {
    const development = createManifest({});

    expect(development.name).toBe('Local Focus Coach (Development)');
    expect('key' in development).toBe(false);
    expect(productionIdentity({})).toBeUndefined();
  });

  it('requires a valid public key before creating a production manifest identity', () => {
    expect(() => createManifest({ LFC_EXTENSION_CHANNEL: 'production' })).toThrow(
      /LFC_EXTENSION_PUBLIC_KEY/,
    );
    expect(() =>
      createManifest({
        LFC_EXTENSION_CHANNEL: 'production',
        LFC_EXTENSION_PUBLIC_KEY: 'not-a-public-key',
      }),
    ).toThrow(/public key/i);
  });

  it('embeds one production public key and derives a stable Chrome extension ID', () => {
    const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 1024 });
    const encodedPublicKey = publicKey
      .export({ format: 'der', type: 'spki' })
      .toString('base64');
    const environment = {
      LFC_EXTENSION_CHANNEL: 'production',
      LFC_EXTENSION_PUBLIC_KEY: encodedPublicKey,
    };

    const production = createManifest(environment);
    const firstIdentity = productionIdentity(environment);
    const secondIdentity = productionIdentity(environment);

    expect(production.name).toBe('Local Focus Coach');
    expect(production.key).toBe(encodedPublicKey);
    expect(firstIdentity).toEqual(secondIdentity);
    expect(firstIdentity?.extensionId).toMatch(/^[a-p]{32}$/);
    expect(firstIdentity?.nativeHostName).toBe('com.localfocuscoach.strict_mode');
  });
});
