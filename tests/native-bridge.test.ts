import {
  resetNativeBridgeForTest,
  startNativeBridge,
  stopNativeBridge,
} from '../src/background/native-bridge';
import { installChromeApiSpies, type ChromeApiSpies } from './chrome-storage';

const HELLO = { version: 1, type: 'extension.hello', payload: {} };
const HEARTBEAT = { version: 1, type: 'extension.heartbeat', payload: {} };

let spies: ChromeApiSpies;

beforeEach(() => {
  resetNativeBridgeForTest();
  vi.useFakeTimers();
  spies = installChromeApiSpies();
});

afterEach(() => {
  resetNativeBridgeForTest();
  vi.useRealTimers();
});

it('connects to the strict host once and sends hello first', () => {
  startNativeBridge();
  startNativeBridge();

  expect(spies.connectNative).toHaveBeenCalledTimes(1);
  expect(spies.connectNative).toHaveBeenCalledWith('com.localfocuscoach.strict_mode');
  expect(spies.nativePorts[0]?.postMessage).toHaveBeenCalledExactlyOnceWith(HELLO);
});

it('sends only the bounded heartbeat envelope every five seconds', () => {
  startNativeBridge();
  const port = spies.nativePorts[0];

  vi.advanceTimersByTime(4_999);
  expect(port?.postMessage).toHaveBeenCalledTimes(1);

  vi.advanceTimersByTime(1);
  expect(port?.postMessage).toHaveBeenNthCalledWith(2, HEARTBEAT);
  expect(port?.postMessage.mock.calls).toEqual([[HELLO], [HEARTBEAT]]);
});

it('reconnects with capped exponential delays after repeated disconnects', () => {
  startNativeBridge();

  const delays = [1_000, 2_000, 4_000, 8_000, 15_000, 15_000];
  delays.forEach((delay, index) => {
    spies.nativePorts[index]?.emitDisconnect();
    vi.advanceTimersByTime(delay - 1);
    expect(spies.connectNative).toHaveBeenCalledTimes(index + 1);
    vi.advanceTimersByTime(1);
    expect(spies.connectNative).toHaveBeenCalledTimes(index + 2);
    expect(spies.nativePorts[index + 1]?.postMessage).toHaveBeenCalledExactlyOnceWith(HELLO);
  });
});

it('ignores malformed messages received from the native host', () => {
  startNativeBridge();
  const port = spies.nativePorts[0];

  expect(() => port?.emitMessage({ type: 'page.request', payload: { text: 'send me' } })).not.toThrow();
  expect(spies.connectNative).toHaveBeenCalledTimes(1);
  expect(port?.postMessage.mock.calls).toEqual([[HELLO]]);
});

it('stopping clears heartbeats and disconnects the open port', () => {
  startNativeBridge();
  const port = spies.nativePorts[0];

  stopNativeBridge();
  vi.advanceTimersByTime(60_000);

  expect(port?.disconnect).toHaveBeenCalledTimes(1);
  expect(port?.postMessage.mock.calls).toEqual([[HELLO]]);
  expect(spies.connectNative).toHaveBeenCalledTimes(1);
});

it('resetting clears a pending reconnect and restores the initial retry delay', () => {
  startNativeBridge();
  spies.nativePorts[0]?.emitDisconnect();
  resetNativeBridgeForTest();
  vi.advanceTimersByTime(60_000);
  expect(spies.connectNative).toHaveBeenCalledTimes(1);

  startNativeBridge();
  spies.nativePorts[1]?.emitDisconnect();
  vi.advanceTimersByTime(999);
  expect(spies.connectNative).toHaveBeenCalledTimes(2);
  vi.advanceTimersByTime(1);
  expect(spies.connectNative).toHaveBeenCalledTimes(3);
});
