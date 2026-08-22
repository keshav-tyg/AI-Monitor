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

  it('builds a keyless production manifest for a Chrome Web Store upload', () => {
    // No LFC_EXTENSION_PUBLIC_KEY on purpose — a new CWS listing rejects a
    // manifest that ships a `key` field, so this is the shape the .zip must
    // have.
    const production = createManifest({ LFC_EXTENSION_CHANNEL: 'production' });

    expect(production.name).toBe('Local Focus Coach');
    expect('key' in production).toBe(false);
    // No identity is derivable from this environment alone — the ID does not
    // exist until CWS assigns one on first upload.
    expect(productionIdentity({ LFC_EXTENSION_CHANNEL: 'production' })).toBeUndefined();
  });

  it('accepts an explicit CWS-assigned extension id for the release identity', () => {
    // The store hands out the ID on first upload. Every future .app package
    // needs to know it so the bundled installer registers the native-host
    // manifest against the right allowed_origins entry.
    const identity = productionIdentity({
      LFC_EXTENSION_CHANNEL: 'production',
      LFC_EXTENSION_ID: 'llgkbdfkmgjpmlammmnidndocedopmol',
    });

    expect(identity).toEqual({
      version: 1,
      channel: 'production',
      extensionId: 'llgkbdfkmgjpmlammmnidndocedopmol',
      nativeHostName: 'com.localfocuscoach.strict_mode',
    });
  });

  it('rejects a malformed extension id rather than storing junk', () => {
    expect(() =>
      productionIdentity({
        LFC_EXTENSION_CHANNEL: 'production',
        LFC_EXTENSION_ID: 'not-an-extension-id',
      }),
    ).toThrow(/LFC_EXTENSION_ID/);
  });

  it('embeds a locally supplied public key for dev builds that must match the store ID', () => {
    // Optional convenience: paste the CWS-generated public key back into the
    // env var and an unpacked load reproduces the store's ID. This build is
    // for local iteration only — uploading it to CWS would be rejected.
    const { publicKey } = generateKeyPairSync('rsa', { modulusLength: 1024 });
    const encodedPublicKey = publicKey
      .export({ format: 'der', type: 'spki' })
      .toString('base64');
    const environment = {
      LFC_EXTENSION_CHANNEL: 'production',
      LFC_EXTENSION_PUBLIC_KEY: encodedPublicKey,
    };

    const production = createManifest(environment);
    const identity = productionIdentity(environment);

    expect(production.name).toBe('Local Focus Coach');
    expect(production.key).toBe(encodedPublicKey);
    expect(identity?.extensionId).toMatch(/^[a-p]{32}$/);
    expect(identity?.nativeHostName).toBe('com.localfocuscoach.strict_mode');
  });

  it('rejects a malformed public key so a bad env var never enters the build', () => {
    expect(() =>
      createManifest({
        LFC_EXTENSION_CHANNEL: 'production',
        LFC_EXTENSION_PUBLIC_KEY: 'not-a-public-key',
      }),
    ).toThrow(/public key/i);
  });

  it('rejects an unknown channel', () => {
    expect(() => createManifest({ LFC_EXTENSION_CHANNEL: 'staging' })).toThrow(/development or production/);
  });
});
