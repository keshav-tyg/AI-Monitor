export const NATIVE_PROTOCOL_VERSION = 1 as const;

export type ExtensionNativeMessageType = 'extension.hello' | 'extension.heartbeat';

export interface ExtensionNativeMessage {
  version: typeof NATIVE_PROTOCOL_VERSION;
  type: ExtensionNativeMessageType;
  payload: Record<string, never>;
}

export function extensionNativeMessage(type: ExtensionNativeMessageType): ExtensionNativeMessage {
  return { version: NATIVE_PROTOCOL_VERSION, type, payload: {} };
}
