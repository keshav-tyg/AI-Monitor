import { execSync } from 'node:child_process';
import { readFileSync } from 'node:fs';

interface BuiltManifest {
  background: { service_worker: string };
  content_scripts: { js: string[] }[];
}

let manifest: BuiltManifest;

beforeAll(() => {
  execSync('npm run build', { stdio: 'pipe' });
  manifest = JSON.parse(readFileSync('dist/manifest.json', 'utf8')) as BuiltManifest;
}, 180_000);

function workerChunk(): string {
  const loader = readFileSync(`dist/${manifest.background.service_worker}`, 'utf8');
  const imported = loader.match(/import\s+'\.\/(.+?)'/)?.[1];
  if (!imported) throw new Error(`No chunk imported by ${manifest.background.service_worker}`);
  return imported;
}

it('does not register the content script bundle as the service worker', () => {
  // Both entry points are called index.ts. When the bundler derives chunk names
  // from the basename they collide, and the worker loader ends up importing the
  // content script — which dies instantly on `window is not defined`.
  const contentChunks = manifest.content_scripts.flatMap((script) => script.js);
  expect(contentChunks).not.toContain(workerChunk());
});

it('ships a service worker bundle that never touches window', () => {
  const source = readFileSync(`dist/${workerChunk()}`, 'utf8');
  expect(source).not.toMatch(/\bwindow\b/);
});
