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
    /**
     * OPPO / OnePlus vendor stack assigns LHDC variants to a vendor id range. The exact sub-id
     * depends on the vendor build (0x13 for the OPPO Enco X3, 0x14 for some Reno builds). We
     * treat any id in this range as LHDC unless evidence says otherwise.
     *
     * <p>The window was originally {@code 0x10..0x1F}; it is widened to {@code 0x10..0x3F} so a
     * future ROM that hands LHDC a fresh vendor id (e.g. 0x20+) is still recognised instead of
     * collapsing the picker to {@code Codec(0x20)} (TODO A4). The {@link #looksLikeLhdc} probe
     * adds a second confirmation step for ids outside the original window.</p>
     */
    public static final int OPLUS_VENDOR_LHDC_RANGE_LOW = 0x10;
    public static final int OPLUS_VENDOR_LHDC_RANGE_HIGH = 0x3F;
    /** The original, high-confidence LHDC window. Ids here are LHDC without further checks. */
    private static final int OPLUS_VENDOR_LHDC_CORE_HIGH = 0x1F;

    // LDAC quality (codecSpecific1 values, source: bluetooth/ldac vendor headers).
    public static final long LDAC_QUALITY_HIGH = 1000L;
    public static final long LDAC_QUALITY_MID = 1001L;
    public static final long LDAC_QUALITY_LOW = 1002L;

    // LHDC playback-quality codes live in codecSpecific1's low byte. The high bits carry
    // vendor flags and must be preserved when writing a new value.
    public static final long LHDC_QUALITY_CONNECTION = 0L;
    public static final long LHDC_QUALITY_STANDARD = 3L;
    public static final long LHDC_QUALITY_HIGH_LEGACY = 5L;
    public static final long LHDC_QUALITY_HIGH = 8L;
    public static final long LHDC_QUALITY_BALANCED = 9L;

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
                if (codecType >= OPLUS_VENDOR_LHDC_RANGE_LOW
                        && codecType <= OPLUS_VENDOR_LHDC_CORE_HIGH) {
                    return Strings.CODEC_LABEL_LHDC;
                }
                return "Codec(0x" + Integer.toHexString(codecType) + ")";
        }
    }

    /**
     * Codec name resolution that also consults {@code codecSpecific1} (TODO A4). For ids in the
     * widened {@code 0x20..0x3F} tail this returns the LHDC label only when the vendor word
     * confirms LHDC via {@link #looksLikeLhdc}; otherwise it defers to the type-only resolution.
     */
    public static String codecLabel(Context context, int codecType, long codecSpecific1) {
        if (codecType > OPLUS_VENDOR_LHDC_CORE_HIGH
                && codecType <= OPLUS_VENDOR_LHDC_RANGE_HIGH
                && looksLikeLhdc(codecType, codecSpecific1)) {
            return Strings.CODEC_LABEL_LHDC;
        }
        return codecLabel(context, codecType);
    }

    /**
     * Quality steps the LDAC protocol allows (codecSpecific1 = 1000/1001/1002 = HIGH/MID/LOW).
     * Used as a fallback when {@code BluetoothA2dp.getCodecStatus} reports an empty
     * {@code selectableCodecSpecific1} array — which AOSP does for every vendor codec where
     * the vendor stack did not bother to register the capability set with the codec manager.
     */
    public static final long[] LDAC_QUALITY_STEPS = {
            LDAC_QUALITY_HIGH, LDAC_QUALITY_MID, LDAC_QUALITY_LOW
    };

    /**
     * Quality steps the LHDC family exposes on the OPPO stack. The active Enco X3 default in
     * logs is 0x8009, which corresponds to the balanced profile on this ROM.
     */
    public static final long[] LHDC_QUALITY_STEPS = {
            LHDC_QUALITY_CONNECTION,
            LHDC_QUALITY_BALANCED,
            LHDC_QUALITY_HIGH
    };

    /** Returns the protocol-defined quality steps for {@code codecType} when the platform
     * cannot enumerate them through {@code getCodecStatus}. Empty array means the codec does
     * not expose adjustable quality (e.g. SBC, AAC, OPUS).
     */
    public static long[] qualityFallback(int codecType) {
        if (codecType == CODEC_LDAC) return LDAC_QUALITY_STEPS.clone();
        if (isLhdc(codecType)) return LHDC_QUALITY_STEPS.clone();
        return new long[0];
    }

    /** Returns true when the codec id is one of the LDAC / LHDC family that exposes quality steps. */
    public static boolean isQualityCapable(int codecType) {
        if (codecType == CODEC_LDAC) return true;
        return isLhdc(codecType);
    }

    /** Returns true when the codec id should be treated as LHDC for quality decoding. */
    public static boolean isLhdc(int codecType) {
        if (codecType == CODEC_LHDC) return true;
        return codecType >= OPLUS_VENDOR_LHDC_RANGE_LOW
                && codecType <= OPLUS_VENDOR_LHDC_RANGE_HIGH;
    }

    /**
     * Second-stage LHDC confirmation for codec ids that fall outside the original
     * {@code 0x10..0x1F} window (TODO A4). Ids inside the core window are accepted outright.
     * For ids in the widened {@code 0x20..0x3F} tail we additionally require the
     * {@code codecSpecific1} word to look like an LHDC vendor word: the low byte must match a
     * known LHDC quality code (CONNECTION / STANDARD / HIGH_LEGACY / HIGH / BALANCED), which is
     * how every shipping OPPO LHDC build encodes the playback quality. This keeps a brand-new
     * vendor id recognised while avoiding mis-labelling an unrelated future codec as LHDC.
     *
     * @param codecType      the active codec id from {@code BluetoothCodecConfig.getCodecType()}
     * @param codecSpecific1 the active {@code codecSpecific1} vendor word
     */
    public static boolean looksLikeLhdc(int codecType, long codecSpecific1) {
        if (codecType == CODEC_LHDC) return true;
        if (codecType < OPLUS_VENDOR_LHDC_RANGE_LOW || codecType > OPLUS_VENDOR_LHDC_RANGE_HIGH) {
            return false;
        }
        if (codecType <= OPLUS_VENDOR_LHDC_CORE_HIGH) {
            return true;
        }
        long lowByte = codecSpecific1 & 0xFFL;
        return lowByte == LHDC_QUALITY_CONNECTION
                || lowByte == LHDC_QUALITY_STANDARD
                || lowByte == LHDC_QUALITY_HIGH_LEGACY
                || lowByte == LHDC_QUALITY_HIGH
                || lowByte == LHDC_QUALITY_BALANCED;
    }

    /** Resolve the LDAC / LHDC quality label, or fall back to {@code "档位 (rawValue)"}. */
    public static String qualityLabel(Context context, int codecType, long specific1) {
        if (codecType == CODEC_LDAC) {
            if (specific1 == LDAC_QUALITY_HIGH) return Strings.QUALITY_LDAC_990;
            if (specific1 == LDAC_QUALITY_MID) return Strings.QUALITY_LDAC_660;
            if (specific1 == LDAC_QUALITY_LOW) return Strings.QUALITY_LDAC_330;
        }
        if (isLhdc(codecType)) {
            // The vendor encodes the version in the low byte; mask it before lookup so that
            // future bit fields (e.g. lossless toggle) do not break label resolution.
            long versionByte = specific1 & 0xFFL;
            if (versionByte == LHDC_QUALITY_CONNECTION) return Strings.QUALITY_LHDC_CONNECTION;
            if (versionByte == LHDC_QUALITY_STANDARD) return Strings.QUALITY_LHDC_BALANCED;
            if (versionByte == LHDC_QUALITY_BALANCED) return Strings.QUALITY_LHDC_BALANCED;
            if (versionByte == LHDC_QUALITY_HIGH_LEGACY) return Strings.QUALITY_LHDC_HIGH;
            if (versionByte == LHDC_QUALITY_HIGH) return Strings.QUALITY_LHDC_HIGH;
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
