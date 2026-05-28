package xyz.melodylsp.codec.host;

import android.content.Context;
import android.provider.Settings;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.MLog;

/**
 * Last-resort writer that uses the developer-options {@code Settings.Global} keys. Never writes
 * {@code bluetooth_select_a2dp_codec_type}—the panel does not expose codec switching.
 */
public final class SettingsGlobalFallback {

    public static final String KEY_LDAC_QUALITY = "bluetooth_select_a2dp_ldac_playback_quality";
    public static final String KEY_SAMPLE_RATE = "bluetooth_select_a2dp_sample_rate";

    private final Context context;

    public SettingsGlobalFallback(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Returns true if anything was written (LDAC quality and / or sample rate). */
    public boolean apply(CodecRequest req) {
        boolean wrote = false;
        try {
            if (req.codecType == CodecLabelTable.CODEC_LDAC) {
                int qualityIndex = mapLdacQualityToIndex(req.codecSpecific1);
                if (qualityIndex >= 0) {
                    Settings.Global.putInt(context.getContentResolver(),
                            KEY_LDAC_QUALITY, qualityIndex);
                    wrote = true;
                }
            }
            int rateIndex = mapSampleRateToIndex(req.sampleRate);
            if (rateIndex >= 0) {
                Settings.Global.putInt(context.getContentResolver(),
                        KEY_SAMPLE_RATE, rateIndex);
                wrote = true;
            }
        } catch (SecurityException e) {
            MLog.e("SettingsGlobalFallback.apply denied (need WRITE_SECURE_SETTINGS)", e);
            return false;
        } catch (Throwable t) {
            MLog.e("SettingsGlobalFallback.apply failed", t);
            return false;
        }
        if (wrote) {
            MLog.event("settings.global.write",
                    "codec", req.codecType,
                    "specific1", req.codecSpecific1,
                    "rate", req.sampleRate);
        }
        return wrote;
    }

    private static int mapLdacQualityToIndex(long codecSpecific1) {
        // The dev-options menu maps {1000,1001,1002} to {0,1,2} (high → low).
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_HIGH) return 0;
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_MID) return 1;
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_LOW) return 2;
        return -1;
    }

    private static int mapSampleRateToIndex(int sampleRateBit) {
        // The dev-options menu maps the sample-rate radio entries to:
        // 1 = 44.1k, 2 = 48k, 3 = 88.2k, 4 = 96k.
        // This is best-effort: the menu does not expose 176.4k/192k as global keys.
        switch (sampleRateBit) {
            case 1 << 0: return 1;
            case 1 << 1: return 2;
            case 1 << 2: return 3;
            case 1 << 3: return 4;
            default: return -1;
        }
    }
}
