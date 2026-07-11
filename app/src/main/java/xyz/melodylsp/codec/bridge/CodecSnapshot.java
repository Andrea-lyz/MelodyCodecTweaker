package xyz.melodylsp.codec.bridge;

import android.bluetooth.BluetoothCodecConfig;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;

import java.util.Arrays;
import java.util.Locale;

/**
 * Immutable snapshot of the active A2DP codec status for a given device MAC.
 *
 * <p>This is the only data structure that crosses the {@code com.oplus.melody:fg} ↔
 * {@code com.android.bluetooth} process boundary.
 */
public final class CodecSnapshot implements Parcelable {

    public final String mac;
    public final int activeCodecType;
    public final int activeSampleRate;
    public final int activeBitsPerSample;
    public final int activeChannelMode;
    public final long activeCodecSpecific1;
    public final long activeCodecSpecific2;
    public final long activeCodecSpecific3;
    public final long activeCodecSpecific4;

    /** Selectable {@code codecSpecific1} values for the active codec. */
    public final long[] selectableCodecSpecific1;
    /** Selectable sample-rate bitmask for the active codec ({@link BluetoothCodecConfig} bits). */
    public final int selectableSampleRateMask;
    /** Codec types exposed by {@code getCodecsSelectableCapabilities()}, in platform order. */
    public final int[] selectableCodecTypes;
    /** Sample-rate masks for {@link #selectableCodecTypes}, same index order. */
    public final int[] selectableCodecSampleRates;
    /** Bits-per-sample masks for {@link #selectableCodecTypes}, same index order. */
    public final int[] selectableCodecBitsPerSample;
    /** Channel-mode masks for {@link #selectableCodecTypes}, same index order. */
    public final int[] selectableCodecChannelModes;
    /** Raw codecSpecific1 values for {@link #selectableCodecTypes}, same index order. */
    public final long[] selectableCodecSpecific1Values;
    /** Optional codec support: 1 supported, 0 unsupported, -1 unknown. */
    public final int optionalCodecsSupported;
    /** Optional codec state: 1 high-quality, 0 standard, -1 unknown. */
    public final int optionalCodecsEnabled;

    public final long readTimestampMs;

    public CodecSnapshot(
            String mac,
            int activeCodecType,
            int activeSampleRate,
            int activeBitsPerSample,
            int activeChannelMode,
            long activeCodecSpecific1,
            long activeCodecSpecific2,
            long activeCodecSpecific3,
            long activeCodecSpecific4,
            long[] selectableCodecSpecific1,
            int selectableSampleRateMask,
            long readTimestampMs) {
        this(
                mac,
                activeCodecType,
                activeSampleRate,
                activeBitsPerSample,
                activeChannelMode,
                activeCodecSpecific1,
                activeCodecSpecific2,
                activeCodecSpecific3,
                activeCodecSpecific4,
                selectableCodecSpecific1,
                selectableSampleRateMask,
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new long[0],
                -1,
                -1,
                readTimestampMs);
    }

    public CodecSnapshot(
            String mac,
            int activeCodecType,
            int activeSampleRate,
            int activeBitsPerSample,
            int activeChannelMode,
            long activeCodecSpecific1,
            long activeCodecSpecific2,
            long activeCodecSpecific3,
            long activeCodecSpecific4,
            long[] selectableCodecSpecific1,
            int selectableSampleRateMask,
            int[] selectableCodecTypes,
            int[] selectableCodecSampleRates,
            int[] selectableCodecBitsPerSample,
            int[] selectableCodecChannelModes,
            long[] selectableCodecSpecific1Values,
            int optionalCodecsSupported,
            int optionalCodecsEnabled,
            long readTimestampMs) {
        this.mac = mac;
        this.activeCodecType = activeCodecType;
        this.activeSampleRate = activeSampleRate;
        this.activeBitsPerSample = activeBitsPerSample;
        this.activeChannelMode = activeChannelMode;
        this.activeCodecSpecific1 = activeCodecSpecific1;
        this.activeCodecSpecific2 = activeCodecSpecific2;
        this.activeCodecSpecific3 = activeCodecSpecific3;
        this.activeCodecSpecific4 = activeCodecSpecific4;
        this.selectableCodecSpecific1 = selectableCodecSpecific1 == null
                ? new long[0]
                : selectableCodecSpecific1.clone();
        this.selectableSampleRateMask = selectableSampleRateMask;
        this.selectableCodecTypes = selectableCodecTypes == null
                ? new int[0]
                : selectableCodecTypes.clone();
        this.selectableCodecSampleRates = selectableCodecSampleRates == null
                ? new int[0]
                : selectableCodecSampleRates.clone();
        this.selectableCodecBitsPerSample = selectableCodecBitsPerSample == null
                ? new int[0]
                : selectableCodecBitsPerSample.clone();
        this.selectableCodecChannelModes = selectableCodecChannelModes == null
                ? new int[0]
                : selectableCodecChannelModes.clone();
        this.selectableCodecSpecific1Values = selectableCodecSpecific1Values == null
                ? new long[0]
                : selectableCodecSpecific1Values.clone();
        this.optionalCodecsSupported = optionalCodecsSupported;
        this.optionalCodecsEnabled = optionalCodecsEnabled;
        this.readTimestampMs = readTimestampMs;
    }

    public static CodecSnapshot now(
            String mac,
            int activeCodecType,
            int activeSampleRate,
            int activeBitsPerSample,
            int activeChannelMode,
            long activeCodecSpecific1,
            long activeCodecSpecific2,
            long activeCodecSpecific3,
            long activeCodecSpecific4,
            long[] selectableCodecSpecific1,
            int selectableSampleRateMask) {
        return now(
                mac,
                activeCodecType,
                activeSampleRate,
                activeBitsPerSample,
                activeChannelMode,
                activeCodecSpecific1,
                activeCodecSpecific2,
                activeCodecSpecific3,
                activeCodecSpecific4,
                selectableCodecSpecific1,
                selectableSampleRateMask,
                new int[0],
                new int[0],
                new int[0],
                new int[0],
                new long[0],
                -1,
                -1);
    }

    public static CodecSnapshot now(
            String mac,
            int activeCodecType,
            int activeSampleRate,
            int activeBitsPerSample,
            int activeChannelMode,
            long activeCodecSpecific1,
            long activeCodecSpecific2,
            long activeCodecSpecific3,
            long activeCodecSpecific4,
            long[] selectableCodecSpecific1,
            int selectableSampleRateMask,
            int[] selectableCodecTypes,
            int[] selectableCodecSampleRates,
            int[] selectableCodecBitsPerSample,
            int[] selectableCodecChannelModes,
            long[] selectableCodecSpecific1Values,
            int optionalCodecsSupported,
            int optionalCodecsEnabled) {
        return new CodecSnapshot(
                mac,
                activeCodecType,
                activeSampleRate,
                activeBitsPerSample,
                activeChannelMode,
                activeCodecSpecific1,
                activeCodecSpecific2,
                activeCodecSpecific3,
                activeCodecSpecific4,
                selectableCodecSpecific1,
                selectableSampleRateMask,
                selectableCodecTypes,
                selectableCodecSampleRates,
                selectableCodecBitsPerSample,
                selectableCodecChannelModes,
                selectableCodecSpecific1Values,
                optionalCodecsSupported,
                optionalCodecsEnabled,
                SystemClock.elapsedRealtime());
    }

    public boolean supportsOptionalCodecs() {
        return optionalCodecsSupported == 1;
    }

    public boolean optionalCodecsEnabled() {
        if (optionalCodecsEnabled == 1) return true;
        if (optionalCodecsEnabled == 0) return false;
        return activeCodecType != 0;
    }

    /**
     * Decode a {@link BluetoothCodecConfig} sample-rate bit mask into the actual rates in Hz, in
     * ascending order. Unknown bits keep their hex form via {@code -bit} in the array (callers
     * should fall back through {@code CodecLabelTable.sampleRateLabel}).
     */
    public static int[] decodeSampleRateBits(int mask) {
        // Standard bits, source: AOSP frameworks/base BluetoothCodecConfig.
        // Use reflection-friendly literals so the unit test never depends on the device API level.
        int[] knownBits = {
                /* SAMPLE_RATE_44100  */ 0x1 << 0,
                /* SAMPLE_RATE_48000  */ 0x1 << 1,
                /* SAMPLE_RATE_88200  */ 0x1 << 2,
                /* SAMPLE_RATE_96000  */ 0x1 << 3,
                /* SAMPLE_RATE_176400 */ 0x1 << 4,
                /* SAMPLE_RATE_192000 */ 0x1 << 5,
        };
        int[] knownHz = {44100, 48000, 88200, 96000, 176400, 192000};

        int[] out = new int[Integer.bitCount(mask)];
        int idx = 0;
        for (int i = 0; i < knownBits.length; i++) {
            if ((mask & knownBits[i]) != 0) {
                out[idx++] = knownHz[i];
            }
        }
        // Any leftover bits beyond what we know about end up as -bit so labels can fall back.
        int unknownMask = mask;
        for (int b : knownBits) {
            unknownMask &= ~b;
        }
        for (int bit = 0; bit < 32 && unknownMask != 0; bit++) {
            int bitVal = 1 << bit;
            if ((unknownMask & bitVal) != 0) {
                if (idx >= out.length) {
                    int[] grown = Arrays.copyOf(out, idx + 1);
                    out = grown;
                }
                out[idx++] = -bitVal;
                unknownMask &= ~bitVal;
            }
        }
        return Arrays.copyOf(out, idx);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mac);
        dest.writeInt(activeCodecType);
        dest.writeInt(activeSampleRate);
        dest.writeInt(activeBitsPerSample);
        dest.writeInt(activeChannelMode);
        dest.writeLong(activeCodecSpecific1);
        dest.writeLong(activeCodecSpecific2);
        dest.writeLong(activeCodecSpecific3);
        dest.writeLong(activeCodecSpecific4);
        dest.writeLongArray(selectableCodecSpecific1);
        dest.writeInt(selectableSampleRateMask);
        dest.writeIntArray(selectableCodecTypes);
        dest.writeIntArray(selectableCodecSampleRates);
        dest.writeIntArray(selectableCodecBitsPerSample);
        dest.writeIntArray(selectableCodecChannelModes);
        dest.writeLongArray(selectableCodecSpecific1Values);
        dest.writeInt(optionalCodecsSupported);
        dest.writeInt(optionalCodecsEnabled);
        dest.writeLong(readTimestampMs);
    }

    public static final Creator<CodecSnapshot> CREATOR = new Creator<CodecSnapshot>() {
        @Override
        public CodecSnapshot createFromParcel(Parcel in) {
            return new CodecSnapshot(
                    in.readString(),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    in.readInt(),
                    in.readLong(),
                    in.readLong(),
                    in.readLong(),
                    in.readLong(),
                    in.createLongArray(),
                    in.readInt(),
                    in.createIntArray(),
                    in.createIntArray(),
                    in.createIntArray(),
                    in.createIntArray(),
                    in.createLongArray(),
                    in.readInt(),
                    in.readInt(),
                    in.readLong());
        }

        @Override
        public CodecSnapshot[] newArray(int size) {
            return new CodecSnapshot[size];
        }
    };

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "CodecSnapshot{mac=%s codec=0x%x rate=0x%x specific1=%d selSpec1=%s selCodec=%s selRateMask=0x%x selRate=%s selBits=%s selChannel=%s selRawSpec1=%s optional=%d/%d ts=%d}",
                redactedMac(mac), activeCodecType, activeSampleRate, activeCodecSpecific1,
                Arrays.toString(selectableCodecSpecific1), Arrays.toString(selectableCodecTypes),
                selectableSampleRateMask,
                Arrays.toString(selectableCodecSampleRates),
                Arrays.toString(selectableCodecBitsPerSample),
                Arrays.toString(selectableCodecChannelModes),
                Arrays.toString(selectableCodecSpecific1Values),
                optionalCodecsSupported, optionalCodecsEnabled,
                readTimestampMs);
    }

    private static String redactedMac(String value) {
        if (value == null || value.length() < 5) return "??";
        return value.substring(0, 2) + "**" + value.substring(value.length() - 2);
    }
}
