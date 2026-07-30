import type { ManifestV3Export } from '@crxjs/vite-plugin';

/**
 * Local-only manifest. Every permission here exists to enforce rules on this
 * device: no remote endpoints, no analytics host, no optional permissions.
 *
 * `satisfies` rather than `defineManifest`: the helper's return type is a union
 * that includes a factory function, which erases the concrete keys the privacy
 * test asserts on.
 */
export default {
  manifest_version: 3,
  name: 'Local Focus Coach',
  version: '0.1.0',
  description:
    'Detects sustained passive feed use on this device and applies the intervention you configured. Nothing leaves this device.',
  permissions: ['storage', 'tabs', 'declarativeNetRequest', 'notifications'],
  host_permissions: [
    '*://*.instagram.com/*',
    '*://x.com/*',
    '*://twitter.com/*',
    '*://*.youtube.com/*',
  ],
  content_scripts: [
    {
      matches: [
        '*://*.instagram.com/*',
        '*://x.com/*',
        '*://twitter.com/*',
        '*://*.youtube.com/*',
      ],
      js: ['src/content/index.ts'],
      run_at: 'document_idle',
    },
  ],
  action: { default_popup: 'src/popup/index.html', default_title: 'Local Focus Coach' },
  options_page: 'src/options/index.html',
} satisfies ManifestV3Export;
