import type { ManifestV3Export } from '@crxjs/vite-plugin';
import { createHash, createPublicKey } from 'node:crypto';

export const PRODUCTION_NATIVE_HOST_NAME = 'com.localfocuscoach.strict_mode';
export const DEVELOPMENT_NATIVE_HOST_NAME = 'com.localfocuscoach.strict_mode_dev';

type BuildEnvironment = Readonly<Record<string, string | undefined>>;

export interface ProductionIdentity {
  version: 1;
  channel: 'production';
  extensionId: string;
  nativeHostName: typeof PRODUCTION_NATIVE_HOST_NAME;
}

/**
 * Local-only manifest. Every permission here exists to enforce rules on this
 * device: no remote endpoints, no analytics host, no optional permissions.
 *
 * Three environment shapes are supported:
 *
 * - **Development** (default) — name is `Local Focus Coach (Development)`, no
 *   `key` field. Chrome assigns a random ID that changes per machine. Good
 *   for iterating; useless for matching a real production install.
 *
 * - **Production for the Chrome Web Store** — `LFC_EXTENSION_CHANNEL=production`
 *   alone. Name is `Local Focus Coach`, no `key` field. This is what you
 *   upload as a `.zip`: the store rejects a `key` in a new listing and
 *   assigns the extension ID itself.
 *
 * - **Production for local testing under the CWS-assigned ID** —
 *   `LFC_EXTENSION_CHANNEL=production` plus `LFC_EXTENSION_PUBLIC_KEY=<the
 *   base64 key the store gave you back>`. Same production name but with the
 *   `key` field embedded so an unpacked load reproduces the store's ID.
 *   Only for local dev — never upload this build to the store.
 *
 * `satisfies` rather than `defineManifest`: the helper's return type is a
 * union that includes a factory function, which erases the concrete keys
 * the privacy test asserts on.
 */
export function createManifest(environment: BuildEnvironment) {
  const channel = productionChannel(environment);
  const publicKey = channel === 'production' ? optionalPublicKey(environment) : undefined;
  return {
    manifest_version: 3,
    name: channel === 'production' ? 'Local Focus Coach' : 'Local Focus Coach (Development)',
    version: '0.1.1',
    description:
      'Detects sustained passive feed use on this device and applies the intervention you configured. Nothing leaves this device.',
    permissions: [
      'storage',
      'declarativeNetRequest',
      'notifications',
      'alarms',
      'offscreen',
      'nativeMessaging',
    ],
    icons: {
      '16': 'icons/icon-16.png',
      '32': 'icons/icon-32.png',
      '48': 'icons/icon-48.png',
      '128': 'icons/icon-128.png',
    },
    host_permissions: [
      '*://*.instagram.com/*',
      '*://x.com/*',
      '*://twitter.com/*',
      '*://*.youtube.com/*',
    ],
    // Entry filenames must stay distinct. The bundler derives chunk names from
    // the basename, so two entries both called index.ts collide and the worker
    // loader can end up importing the content script.
    background: { service_worker: 'src/background/service-worker.ts', type: 'module' },
    content_scripts: [
      {
        matches: [
          '*://*.instagram.com/*',
          '*://x.com/*',
          '*://twitter.com/*',
          '*://*.youtube.com/*',
        ],
        js: ['src/content/content-script.ts'],
        run_at: 'document_idle',
      },
    ],
    action: {
      default_popup: 'src/popup/index.html',
      default_title: 'Local Focus Coach',
      // Toolbar icon. Chrome picks the closest match for the current density,
      // so all three exist for crisp rendering on standard and Retina displays.
      default_icon: {
        '16': 'icons/icon-16.png',
        '32': 'icons/icon-32.png',
        '48': 'icons/icon-48.png',
      },
    },
    options_page: 'src/options/index.html',
    ...(publicKey ? { key: publicKey } : {}),
  } satisfies ManifestV3Export;
}

/**
 * The identity the desktop app has to know about to register a native-host
 * manifest for the production extension.
 *
 * Sources, in order:
 *
 * 1. `LFC_EXTENSION_ID` — the 32-character ID the Chrome Web Store assigned to
 *    the listing on first upload. This is the authoritative source once a
 *    listing exists.
 * 2. `LFC_EXTENSION_PUBLIC_KEY` — the base64 DER key. Chrome derives an ID
 *    from this by the same algorithm the store uses, so a build with this
 *    key set produces the same ID the store would.
 *
 * Only makes sense in the production channel. Returns undefined otherwise.
 */
export function productionIdentity(environment: BuildEnvironment): ProductionIdentity | undefined {
  if (productionChannel(environment) !== 'production') return undefined;

  const explicit = environment['LFC_EXTENSION_ID'];
  if (explicit) {
    if (!/^[a-p]{32}$/.test(explicit)) {
      throw new Error('LFC_EXTENSION_ID must be 32 characters of a–p');
    }
    return identityFor(explicit);
  }

  const publicKey = optionalPublicKey(environment);
  if (!publicKey) return undefined;
  return identityFor(deriveIdFromKey(publicKey));
}

function identityFor(extensionId: string): ProductionIdentity {
  return {
    version: 1,
    channel: 'production',
    extensionId,
    nativeHostName: PRODUCTION_NATIVE_HOST_NAME,
  };
}

function deriveIdFromKey(publicKey: string): string {
  const digest = createHash('sha256').update(Buffer.from(publicKey, 'base64')).digest();
  return [...digest.subarray(0, 16)]
    .map((byte) => `${String.fromCharCode(97 + (byte >> 4))}${String.fromCharCode(97 + (byte & 15))}`)
    .join('');
}

function productionChannel(environment: BuildEnvironment): 'production' | 'development' {
  const channel = environment['LFC_EXTENSION_CHANNEL'] ?? 'development';
  if (channel === 'production' || channel === 'development') return channel;
  throw new Error('LFC_EXTENSION_CHANNEL must be development or production');
}

/**
 * Validates and returns the base64 DER public key when one is present, or
 * undefined when it is not. Absence is not an error — the store-upload build
 * is expected to have no key.
 */
function optionalPublicKey(environment: BuildEnvironment): string | undefined {
  const encoded = environment['LFC_EXTENSION_PUBLIC_KEY'];
  if (!encoded) return undefined;
  try {
    const key = createPublicKey({ key: Buffer.from(encoded, 'base64'), format: 'der', type: 'spki' });
    const canonical = key.export({ format: 'der', type: 'spki' }).toString('base64');
    if (canonical !== encoded) throw new Error('non-canonical encoding');
    return canonical;
  } catch (error) {
    throw new Error('LFC_EXTENSION_PUBLIC_KEY must be a canonical base64 DER public key', {
      cause: error,
    });
  }
}

export default createManifest(process.env);
