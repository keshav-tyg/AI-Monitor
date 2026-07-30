import { readFileSync } from 'node:fs';
import { globSync } from 'glob';

it('contains no fetch clients, remote URLs, telemetry, or screenshot API usage in extension source', () => {
  const source = globSync('src/**/*.{ts,css,html}').map((path) => readFileSync(path, 'utf8')).join('\n');
  expect(source).not.toMatch(/\b(fetch|XMLHttpRequest|WebSocket|sendBeacon|captureVisibleTab)\b/);
  expect(source).not.toMatch(/analytics|telemetry|sentry/i);
});
