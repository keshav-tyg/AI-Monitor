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
 * `satisfies` rather than `defineManifest`: the helper's return type is a union
 * that includes a factory function, which erases the concrete keys the privacy
 * test asserts on.
 */
export function createManifest(environment: BuildEnvironment) {
  const publicKey = productionPublicKey(environment);
  return {
    manifest_version: 3,
    name: publicKey ? 'Local Focus Coach' : 'Local Focus Coach (Development)',
    version: '0.1.0',
    description:
      'Detects sustained passive feed use on this device and applies the intervention you configured. Nothing leaves this device.',
    permissions: [
      'storage',
      'tabs',
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

export function productionIdentity(environment: BuildEnvironment): ProductionIdentity | undefined {
  const publicKey = productionPublicKey(environment);
  if (!publicKey) return undefined;

  const digest = createHash('sha256').update(Buffer.from(publicKey, 'base64')).digest();
  const extensionId = [...digest.subarray(0, 16)]
    .map((byte) => `${String.fromCharCode(97 + (byte >> 4))}${String.fromCharCode(97 + (byte & 15))}`)
    .join('');
  return {
    version: 1,
    channel: 'production',
    extensionId,
    nativeHostName: PRODUCTION_NATIVE_HOST_NAME,
  };
}

function productionPublicKey(environment: BuildEnvironment): string | undefined {
  const channel = environment['LFC_EXTENSION_CHANNEL'] ?? 'development';
  if (channel === 'development') return undefined;
  if (channel !== 'production') {
    throw new Error('LFC_EXTENSION_CHANNEL must be development or production');
  }

  const encoded = environment['LFC_EXTENSION_PUBLIC_KEY'];
  if (!encoded) {
    throw new Error('LFC_EXTENSION_PUBLIC_KEY is required for a production build');
  }
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
