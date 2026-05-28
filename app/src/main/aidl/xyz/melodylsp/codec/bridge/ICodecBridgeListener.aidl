package xyz.melodylsp.codec.bridge;

import xyz.melodylsp.codec.bridge.CodecSnapshot;

interface ICodecBridgeListener {
    void onCodecChanged(in CodecSnapshot snapshot);
    void onConnectionChanged(String mac, int state);
}
