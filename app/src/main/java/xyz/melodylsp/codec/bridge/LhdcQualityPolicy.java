package xyz.melodylsp.codec.bridge;

import android.content.Context;
import android.content.Intent;

import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * User-facing LHDC playback policies and their transport representation.
 *
 * <p>Quality priority is transported as the real fixed 1000 kbps mode, so Android developer
 * options and the codec configuration remain truthful. The native governor captures the active
 * handle from the fixed-bitrate encoder path, then applies temporary congestion protection only
 * inside the encoder. Playback-time protection never calls {@code setCodecConfigPreference()} and
 * therefore does not rebuild the A2DP session.</p>
 */
public final class LhdcQualityPolicy {

    public static final int ADAPTIVE = (int) CodecLabelTable.LHDC_QUALITY_ABR;
    public static final int CONNECTION = (int) CodecLabelTable.LHDC_QUALITY_MID_500;
    public static final int QUALITY = (int) CodecLabelTable.LHDC_QUALITY_FIXED_1000;

    private LhdcQualityPolicy() {
    }

    public static int fromSpecific1(long specific1) {
        int lowByte = (int) (specific1 & 0xFFL);
        if (lowByte == CodecLabelTable.LHDC_QUALITY_FIXED_900
                || lowByte == CodecLabelTable.LHDC_QUALITY_FIXED_1000) {
            return QUALITY;
        }
        if (lowByte == CodecLabelTable.LHDC_QUALITY_MID_500) {
            return CONNECTION;
        }
        return ADAPTIVE;
    }

    public static int normalize(int policy) {
        if (policy == CONNECTION || policy == QUALITY) return policy;
        return ADAPTIVE;
    }

    public static long logicalSpecific1(long specific1, int policy) {
        return (specific1 & ~0xFFL) | (normalize(policy) & 0xFFL);
    }

    public static long transportSpecific1(long specific1, int policy) {
        int normalized = normalize(policy);
        return (specific1 & ~0xFFL) | (normalized & 0xFFL);
    }

    public static CodecRequest transportRequest(CodecRequest request, int policy) {
        if (request == null || !CodecLabelTable.isLhdc(request.codecType)) return request;
        long transportSpecific1 = transportSpecific1(request.codecSpecific1, policy);
        if (transportSpecific1 == request.codecSpecific1) return request;
        return new CodecRequest(
                request.mac,
                request.codecType,
                transportSpecific1,
                request.codecSpecific2,
                request.codecSpecific3,
                request.codecSpecific4,
                request.sampleRate,
                request.bitsPerSample,
                request.channelMode);
    }

    /** Sends the active logical policy to the governor in {@code com.android.bluetooth}. */
    public static boolean send(Context context, String mac, int policy, String reason) {
        if (context == null || mac == null || mac.isEmpty()) return false;
        int normalized = normalize(policy);
        Intent intent = new Intent(CodecIpc.ACTION_SET_LHDC_POLICY);
        intent.setPackage(CodecIpc.BLUETOOTH_PKG);
        intent.putExtra(CodecIpc.EXTRA_TOKEN, CodecIpc.TOKEN);
        intent.putExtra(CodecIpc.EXTRA_MAC, mac);
        intent.putExtra(CodecIpc.EXTRA_LHDC_POLICY, normalized);
        intent.putExtra(CodecIpc.EXTRA_LHDC_POLICY_REASON,
                reason != null && !reason.isEmpty() ? reason : "unknown");
        boolean sent = TrustedBroadcasts.send(context, intent);
        MLog.event("lhdc.policy.send",
                "mac", redact(mac),
                "policy", normalized,
                "reason", reason,
                "sent", sent);
        return sent;
    }

    private static String redact(String mac) {
        if (mac == null || mac.length() < 5) return "??";
        return mac.substring(0, 2) + "**" + mac.substring(mac.length() - 2);
    }
}
