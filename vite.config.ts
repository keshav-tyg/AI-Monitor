import { defineConfig } from 'vitest/config';
import { fileURLToPath } from 'node:url';
import { crx } from '@crxjs/vite-plugin';
import manifest from './manifest.config';

export default defineConfig({
  plugins: [crx({ manifest })],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    rollupOptions: {
      // The offscreen page is not a manifest entry, so Vite must be told to
      // emit it explicitly. Keep its packaged path aligned with the URL the
      // service worker passes to chrome.offscreen.createDocument().
      input: {
        'src/offscreen/index': fileURLToPath(new URL('./src/offscreen/index.html', import.meta.url)),
      },
    },
  },
  test: {
    globals: true,
    // Node is the default; DOM-dependent suites opt in with a
    // `@vitest-environment jsdom` docblock at the top of the file.
    environment: 'node',
    setupFiles: ['tests/setup-dom.ts'],
    include: ['tests/**/*.test.ts'],
  },
});
