package xyz.melodylsp.codec.bridge;

/**
 * Broadcast IPC constants for the A2DP codec bridge.
 *
 * <p>The ServiceManager/AIDL path is still preferred when a ROM allows
 * {@code com.android.bluetooth} to register a process-local service. Some ColorOS 16 builds block
 * that registration with SELinux while also enforcing CDM on Melody-side direct A2DP calls, so we
 * keep this targeted broadcast channel as the stable fallback.</p>
 */
public final class CodecIpc {

    public static final String MELODY_PKG = "com.oplus.melody";
    public static final String BLUETOOTH_PKG = "com.android.bluetooth";

    public static final String ACTION_QUERY_CODEC = "xyz.melodylsp.codec.action.QUERY_CODEC";
    public static final String ACTION_SET_CODEC = "xyz.melodylsp.codec.action.SET_CODEC";
    public static final String ACTION_CODEC_STATE = "xyz.melodylsp.codec.action.CODEC_STATE";

    public static final String EXTRA_TOKEN = "token";
    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_MAC = "mac";
    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_RESULT = "result";

    public static final String EXTRA_CODEC_TYPE = "codec_type";
    public static final String EXTRA_SAMPLE_RATE = "sample_rate";
    public static final String EXTRA_BITS_PER_SAMPLE = "bits_per_sample";
    public static final String EXTRA_CHANNEL_MODE = "channel_mode";
    public static final String EXTRA_CODEC_SPECIFIC_1 = "codec_specific_1";
    public static final String EXTRA_CODEC_SPECIFIC_2 = "codec_specific_2";
    public static final String EXTRA_CODEC_SPECIFIC_3 = "codec_specific_3";
    public static final String EXTRA_CODEC_SPECIFIC_4 = "codec_specific_4";
    public static final String EXTRA_SELECTABLE_SPECIFIC_1 = "selectable_specific_1";
    public static final String EXTRA_SELECTABLE_SAMPLE_RATE_MASK = "selectable_sample_rate_mask";
    public static final String EXTRA_READ_TIMESTAMP_MS = "read_timestamp_ms";

    public static final String TOKEN = "mlcdc-codec-v1";

    private CodecIpc() {
    }
}
