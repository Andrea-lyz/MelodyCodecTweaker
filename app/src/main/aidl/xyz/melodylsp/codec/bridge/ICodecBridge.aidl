package xyz.melodylsp.codec.bridge;

import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.ICodecBridgeListener;

/**
 * Privileged shadow API exposed by the system-side hook to host-side clients.
 *
 * Result codes returned by {@code setCodec}:
 *   0 = OK (write accepted by A2dpService)
 *  -1 = TIMEOUT (set call returned but no echo via codec changed broadcast)
 *  -2 = DENIED (caller is not the Melody host)
 *  -3 = INVALID (bad mac, no active device, capability not selectable)
 *  -4 = ERROR (uncaught exception forwarded from A2dpService)
 */
interface ICodecBridge {
    CodecSnapshot getStatus(String mac);
    int setCodec(in CodecRequest request);
    void register(ICodecBridgeListener listener);
    void unregister(ICodecBridgeListener listener);
}
