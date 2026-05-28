package xyz.melodylsp.codec.label;

import android.content.Context;

import xyz.melodylsp.codec.host.Strings;

/**
 * Static label tables and fallback formatters. Selectable sets always come from system
 * {@code getCodecsSelectableCapabilities()}; this class only knows how to render values.
 *
 * <p>Codec / quality / sample-rate ids correspond to {@code BluetoothCodecConfig}. Because the
 * module targets Android 13+, the constants are inlined to avoid {@code @hide} resolution
 * problems at compile time.
 *
 * <p>Strings are hard-coded via {@link Strings} rather than fetched through {@code R.string}.
 * The module APK gets its own resource id range that overlaps with the host APK; reading
 * strings via {@code Context.getString(int)} on a host {@code Context} returns whatever
 * happens to live at that id in the host APK (usually the path to a host XML resource), not
 * our string. Hard-coding sidesteps the entire resource lookup.</p>
 */
public final class CodecLabelTable {

    // Codec types — mirror BluetoothCodecConfig.SOURCE_CODEC_TYPE_*
    public static final int CODEC_SBC = 0;
    public static final int CODEC_AAC = 1;
    public static final int CODEC_APTX = 2;
    public static final int CODEC_APTX_HD = 3;
    public static final int CODEC_LDAC = 4;
    public static final int CODEC_LC3 = 5;
    public static final int CODEC_OPUS = 6;
    public static final int CODEC_APTX_ADAPTIVE = 7;
    public static final int CODEC_LHDC = 8;

    // LDAC quality (codecSpecific1 values, source: bluetooth/ldac vendor headers).
    public static final long LDAC_QUALITY_HIGH = 1000L;
    public static final long LDAC_QUALITY_MID = 1001L;
    public static final long LDAC_QUALITY_LOW = 1002L;

    // LHDC version codes encoded into codecSpecific1's lower bits per the OPPO BLT vendor stack.
    public static final long LHDC_V1 = 1L;
    public static final long LHDC_V2 = 2L;
    public static final long LHDC_V3 = 3L;
    public static final long LHDC_V5 = 5L;

    private CodecLabelTable() {
    }

    /** Resolve the user-facing codec name. {@code context} is unused but kept for API stability. */
    public static String codecLabel(Context context, int codecType) {
        switch (codecType) {
            case CODEC_SBC:
                return Strings.CODEC_LABEL_SBC;
            case CODEC_AAC:
                return Strings.CODEC_LABEL_AAC;
            case CODEC_APTX:
                return Strings.CODEC_LABEL_APTX;
            case CODEC_APTX_HD:
                return Strings.CODEC_LABEL_APTX_HD;
            case CODEC_LDAC:
                return Strings.CODEC_LABEL_LDAC;
            case CODEC_OPUS:
                return Strings.CODEC_LABEL_OPUS;
            case CODEC_APTX_ADAPTIVE:
                return Strings.CODEC_LABEL_APTX_ADAPTIVE;
            case CODEC_LHDC:
                return Strings.CODEC_LABEL_LHDC;
            default:
                return "Codec(0x" + Integer.toHexString(codecType) + ")";
        }
    }

    /** Resolve the LDAC / LHDC quality label, or fall back to {@code "档位 (rawValue)"}. */
    public static String qualityLabel(Context context, int codecType, long specific1) {
        if (codecType == CODEC_LDAC) {
            if (specific1 == LDAC_QUALITY_HIGH) return Strings.QUALITY_LDAC_990;
            if (specific1 == LDAC_QUALITY_MID) return Strings.QUALITY_LDAC_660;
            if (specific1 == LDAC_QUALITY_LOW) return Strings.QUALITY_LDAC_330;
        }
        if (codecType == CODEC_LHDC) {
            // The vendor encodes the version in the low byte; mask it before lookup so that
            // future bit fields (e.g. lossless toggle) do not break label resolution.
            long versionByte = specific1 & 0xFFL;
            if (versionByte == LHDC_V1) return Strings.QUALITY_LHDC_V1;
            if (versionByte == LHDC_V2) return Strings.QUALITY_LHDC_V2;
            if (versionByte == LHDC_V3) return Strings.QUALITY_LHDC_V3;
            if (versionByte == LHDC_V5) return Strings.QUALITY_LHDC_V5;
        }
        return "档位 (" + specific1 + ")";
    }

    /**
     * Resolve a numeric Hz to a "%.1f kHz" or "%d kHz" label. Negative values represent
     * un-decodable bits emitted by {@code CodecSnapshot.decodeSampleRateBits} — they are rendered
     * via the explicit hex fallback (Requirement 5.5).
     */
    public static String sampleRateLabel(int rateHz) {
        if (rateHz <= 0) {
            int bit = -rateHz;
            return "采样率 (0x" + Integer.toHexString(bit) + ")";
        }
        // Use kHz with .1 precision when there is a fractional part.
        if (rateHz % 1000 == 0) {
            return (rateHz / 1000) + " kHz";
        }
        double khz = rateHz / 1000.0;
        return String.format(java.util.Locale.ROOT, "%.1f kHz", khz);
    }
}
