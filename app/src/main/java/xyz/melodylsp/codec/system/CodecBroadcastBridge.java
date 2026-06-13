package xyz.melodylsp.codec.system;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import xyz.melodylsp.codec.bridge.CodecIpc;
import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.util.MLog;

/** A2DP codec endpoint running inside {@code com.android.bluetooth}. */
public final class CodecBroadcastBridge {

    private final Context context;
    private final CodecBridgeService service;
    private volatile boolean registered;

    public CodecBroadcastBridge(Context context, CodecBridgeService service) {
        this.context = context.getApplicationContext();
        this.service = service;
    }

    public synchronized void register() {
        if (registered) return;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleAsync(intent);
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(CodecIpc.ACTION_QUERY_CODEC);
        filter.addAction(CodecIpc.ACTION_SET_CODEC);
        filter.addAction(CodecIpc.ACTION_SET_OPTIONAL_CODECS);
        filter.addAction(CodecIpc.ACTION_QUERY_NATIVE_PATCH);
        try {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            context.registerReceiver(receiver, filter);
        }
        registered = true;
        MLog.event("codec.bt.receiver.registered");
    }

    private void handleAsync(Intent intent) {
        new Thread(() -> handle(intent), "MelodyCodecLsp-codec").start();
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        if (!CodecIpc.TOKEN.equals(intent.getStringExtra(CodecIpc.EXTRA_TOKEN))) {
            MLog.w("codec bluetooth request rejected: bad token");
            return;
        }
        String requestId = intent.getStringExtra(CodecIpc.EXTRA_REQUEST_ID);
        String mac = intent.getStringExtra(CodecIpc.EXTRA_MAC);
        try {
            String action = intent.getAction();
            if (CodecIpc.ACTION_QUERY_NATIVE_PATCH.equals(action)) {
                replyNativePatchState();
            } else if (mac == null || mac.isEmpty()) {
                return;
            } else if (CodecIpc.ACTION_QUERY_CODEC.equals(action)) {
                CodecSnapshot snapshot = service.getStatusUnchecked(mac);
                reply(requestId, mac, snapshot, snapshot != null, CodecRequest.RESULT_OK);
                MLog.event("codec.bt.reply", "ok", snapshot != null, "mac", mac);
            } else if (CodecIpc.ACTION_SET_CODEC.equals(action)) {
                CodecRequest request = readRequest(intent);
                int result = service.setCodecUnchecked(request);
                CodecSnapshot snapshot = result == CodecRequest.RESULT_OK
                        ? service.getStatusUnchecked(mac)
                        : null;
                reply(requestId, mac, snapshot, result == CodecRequest.RESULT_OK, result);
                MLog.event("codec.bt.set", "result", result, "mac", mac);
            } else if (CodecIpc.ACTION_SET_OPTIONAL_CODECS.equals(action)) {
                boolean enable = intent.getBooleanExtra(
                        CodecIpc.EXTRA_OPTIONAL_CODECS_ENABLE, false);
                int result = service.setOptionalCodecsUnchecked(mac, enable);
                CodecSnapshot snapshot = result == CodecRequest.RESULT_OK
                        ? service.getStatusUnchecked(mac)
                        : null;
                reply(requestId, mac, snapshot, result == CodecRequest.RESULT_OK, result);
                MLog.event("codec.bt.set_optional", "result", result,
                        "enable", enable, "mac", mac);
            }
        } catch (Throwable t) {
            MLog.e("codec bluetooth request failed", t);
            reply(requestId, mac, null, false, CodecRequest.RESULT_ERROR);
        }
    }

    private static CodecRequest readRequest(Intent intent) {
        return new CodecRequest(
                intent.getStringExtra(CodecIpc.EXTRA_MAC),
                intent.getIntExtra(CodecIpc.EXTRA_CODEC_TYPE, 0),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_1, 0L),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_2, 0L),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_3, 0L),
                intent.getLongExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_4, 0L),
                intent.getIntExtra(CodecIpc.EXTRA_SAMPLE_RATE, 0),
                intent.getIntExtra(CodecIpc.EXTRA_BITS_PER_SAMPLE, 0),
                intent.getIntExtra(CodecIpc.EXTRA_CHANNEL_MODE, 0));
    }

    private void reply(
            String requestId,
            String mac,
            CodecSnapshot snapshot,
            boolean ok,
            int result) {
        Intent reply = new Intent(CodecIpc.ACTION_CODEC_STATE);
        reply.setPackage(CodecIpc.MELODY_PKG);
        reply.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        reply.putExtra(CodecIpc.EXTRA_REQUEST_ID, requestId);
        reply.putExtra(CodecIpc.EXTRA_MAC, mac);
        reply.putExtra(CodecIpc.EXTRA_OK, ok);
        reply.putExtra(CodecIpc.EXTRA_RESULT, result);
        if (snapshot != null) {
            writeSnapshot(reply, snapshot);
        }
        writeNativePatchState(reply);
        try {
            context.sendBroadcast(reply);
        } catch (Throwable t) {
            MLog.w("codec.bt.reply send failed", t);
        }
    }

    void replyNativePatchState() {
        Intent reply = new Intent(CodecIpc.ACTION_NATIVE_PATCH_STATE);
        reply.setPackage(CodecIpc.MELODY_PKG);
        reply.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        writeNativePatchState(reply);
        try {
            context.sendBroadcast(reply);
        } catch (Throwable t) {
            MLog.w("codec.bt.native_patch.reply send failed", t);
        }
    }

    private static void writeSnapshot(Intent intent, CodecSnapshot snapshot) {
        intent.putExtra(CodecIpc.EXTRA_CODEC_TYPE, snapshot.activeCodecType);
        intent.putExtra(CodecIpc.EXTRA_SAMPLE_RATE, snapshot.activeSampleRate);
        intent.putExtra(CodecIpc.EXTRA_BITS_PER_SAMPLE, snapshot.activeBitsPerSample);
        intent.putExtra(CodecIpc.EXTRA_CHANNEL_MODE, snapshot.activeChannelMode);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_1, snapshot.activeCodecSpecific1);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_2, snapshot.activeCodecSpecific2);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_3, snapshot.activeCodecSpecific3);
        intent.putExtra(CodecIpc.EXTRA_CODEC_SPECIFIC_4, snapshot.activeCodecSpecific4);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_SPECIFIC_1, snapshot.selectableCodecSpecific1);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_SAMPLE_RATE_MASK,
                snapshot.selectableSampleRateMask);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_TYPES, snapshot.selectableCodecTypes);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_SAMPLE_RATES,
                snapshot.selectableCodecSampleRates);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_BITS_PER_SAMPLE,
                snapshot.selectableCodecBitsPerSample);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_CHANNEL_MODES,
                snapshot.selectableCodecChannelModes);
        intent.putExtra(CodecIpc.EXTRA_SELECTABLE_CODEC_SPECIFIC_1_VALUES,
                snapshot.selectableCodecSpecific1Values);
        intent.putExtra(CodecIpc.EXTRA_OPTIONAL_CODECS_SUPPORTED,
                snapshot.optionalCodecsSupported);
        intent.putExtra(CodecIpc.EXTRA_OPTIONAL_CODECS_ENABLED,
                snapshot.optionalCodecsEnabled);
        intent.putExtra(CodecIpc.EXTRA_READ_TIMESTAMP_MS, snapshot.readTimestampMs);
    }

    private static void writeNativePatchState(Intent intent) {
        NativeLhdcMemoryPatch.PatchResult result = NativeLhdcMemoryPatch.lastResult();
        if (result == null) return;
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_STATUS, result.status);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_DETAIL, result.reason);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_PATCHED, result.patchedCount);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_ORIGINAL, result.originalCount);
        intent.putExtra(CodecIpc.EXTRA_NATIVE_PATCH_SUCCESS, result.success);
    }
}
