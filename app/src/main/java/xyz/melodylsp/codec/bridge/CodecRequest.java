package xyz.melodylsp.codec.bridge;

import android.os.Parcel;
import android.os.Parcelable;

import java.util.Locale;

/**
 * Codec write request crossing the host ↔ system-process bridge. Constructed via
 * {@link Builder#fromActive(CodecSnapshot)}; {@link Builder#withSpecific1(long)} and
 * {@link Builder#withSampleRate(int)} change exactly one field at a time so the rest of the
 * config matches the currently active codec (Requirement 7.1).
 */
public final class CodecRequest implements Parcelable {

    public static final int RESULT_OK = 0;
    public static final int RESULT_TIMEOUT = -1;
    public static final int RESULT_DENIED = -2;
    public static final int RESULT_INVALID = -3;
    public static final int RESULT_ERROR = -4;

    public final String mac;
    public final int codecType;
    public final long codecSpecific1;
    public final long codecSpecific2;
    public final long codecSpecific3;
    public final long codecSpecific4;
    public final int sampleRate;
    public final int bitsPerSample;
    public final int channelMode;

    public CodecRequest(
            String mac,
            int codecType,
            long codecSpecific1,
            long codecSpecific2,
            long codecSpecific3,
            long codecSpecific4,
            int sampleRate,
            int bitsPerSample,
            int channelMode) {
        this.mac = mac;
        this.codecType = codecType;
        this.codecSpecific1 = codecSpecific1;
        this.codecSpecific2 = codecSpecific2;
        this.codecSpecific3 = codecSpecific3;
        this.codecSpecific4 = codecSpecific4;
        this.sampleRate = sampleRate;
        this.bitsPerSample = bitsPerSample;
        this.channelMode = channelMode;
    }

    public static Builder fromActive(CodecSnapshot snapshot) {
        return new Builder()
                .mac(snapshot.mac)
                .codecType(snapshot.activeCodecType)
                .codecSpecific1(snapshot.activeCodecSpecific1)
                .codecSpecific2(snapshot.activeCodecSpecific2)
                .codecSpecific3(snapshot.activeCodecSpecific3)
                .codecSpecific4(snapshot.activeCodecSpecific4)
                .sampleRate(snapshot.activeSampleRate)
                .bitsPerSample(snapshot.activeBitsPerSample)
                .channelMode(snapshot.activeChannelMode);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(mac);
        dest.writeInt(codecType);
        dest.writeLong(codecSpecific1);
        dest.writeLong(codecSpecific2);
        dest.writeLong(codecSpecific3);
        dest.writeLong(codecSpecific4);
        dest.writeInt(sampleRate);
        dest.writeInt(bitsPerSample);
        dest.writeInt(channelMode);
    }

    public static final Creator<CodecRequest> CREATOR = new Creator<CodecRequest>() {
        @Override
        public CodecRequest createFromParcel(Parcel in) {
            return new CodecRequest(
                    in.readString(),
                    in.readInt(),
                    in.readLong(),
                    in.readLong(),
                    in.readLong(),
                    in.readLong(),
                    in.readInt(),
                    in.readInt(),
                    in.readInt());
        }

        @Override
        public CodecRequest[] newArray(int size) {
            return new CodecRequest[size];
        }
    };

    @Override
    public String toString() {
        return String.format(Locale.ROOT,
                "CodecRequest{mac=%s codec=0x%x specific1=%d rate=0x%x bits=0x%x channel=0x%x}",
                mac, codecType, codecSpecific1, sampleRate, bitsPerSample, channelMode);
    }

    /** Mutable builder; create a fresh one for each derived request. */
    public static final class Builder {
        private String mac;
        private int codecType;
        private long codecSpecific1;
        private long codecSpecific2;
        private long codecSpecific3;
        private long codecSpecific4;
        private int sampleRate;
        private int bitsPerSample;
        private int channelMode;

        public Builder mac(String value) {
            this.mac = value;
            return this;
        }

        public Builder codecType(int value) {
            this.codecType = value;
            return this;
        }

        public Builder codecSpecific1(long value) {
            this.codecSpecific1 = value;
            return this;
        }

        public Builder codecSpecific2(long value) {
            this.codecSpecific2 = value;
            return this;
        }

        public Builder codecSpecific3(long value) {
            this.codecSpecific3 = value;
            return this;
        }

        public Builder codecSpecific4(long value) {
            this.codecSpecific4 = value;
            return this;
        }

        public Builder sampleRate(int value) {
            this.sampleRate = value;
            return this;
        }

        public Builder bitsPerSample(int value) {
            this.bitsPerSample = value;
            return this;
        }

        public Builder channelMode(int value) {
            this.channelMode = value;
            return this;
        }

        public Builder withSpecific1(long value) {
            return codecSpecific1(value);
        }

        public Builder withSampleRate(int value) {
            return sampleRate(value);
        }

        public CodecRequest build() {
            return new CodecRequest(
                    mac, codecType,
                    codecSpecific1, codecSpecific2, codecSpecific3, codecSpecific4,
                    sampleRate, bitsPerSample, channelMode);
        }
    }
}
