import { readFileSync } from 'node:fs';
import { globSync } from 'glob';

it('contains no network clients, remote URLs, telemetry, or screenshot API usage in extension source', () => {
  const source = globSync('src/**/*.{ts,css,html}').map((path) => readFileSync(path, 'utf8')).join('\n');
  expect(source).not.toMatch(/\b(fetch|XMLHttpRequest|WebSocket|sendBeacon|captureVisibleTab)\b/);
  expect(source).not.toMatch(/analytics|telemetry|sentry/i);
});

it('keeps browser and session data out of the native bridge protocol', () => {
  const bridgeSource = [
    readFileSync('src/shared/native-protocol.ts', 'utf8'),
    readFileSync('src/background/native-bridge.ts', 'utf8'),
  ].join('\n');

  expect(bridgeSource).not.toMatch(/ContentCommand|NormalizedEvent|SessionState|summarizeSession/);
  expect(bridgeSource).not.toMatch(/tabs\.|storage\.|document\.|location\.|innerText|textContent/);
});
