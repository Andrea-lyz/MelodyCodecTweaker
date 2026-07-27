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
    public static final String ACTION_SET_OPTIONAL_CODECS =
            "xyz.melodylsp.codec.action.SET_OPTIONAL_CODECS";
    public static final String ACTION_CODEC_STATE = "xyz.melodylsp.codec.action.CODEC_STATE";
    public static final String ACTION_QUERY_NATIVE_PATCH =
            "xyz.melodylsp.codec.action.QUERY_NATIVE_PATCH";
    public static final String ACTION_NATIVE_PATCH_STATE =
            "xyz.melodylsp.codec.action.NATIVE_PATCH_STATE";
    public static final String ACTION_GAME_MODE_STATE =
            "xyz.melodylsp.codec.action.GAME_MODE_STATE";

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
    public static final String EXTRA_SELECTABLE_CODEC_TYPES = "selectable_codec_types";
    public static final String EXTRA_SELECTABLE_CODEC_SAMPLE_RATES =
            "selectable_codec_sample_rates";
    public static final String EXTRA_SELECTABLE_CODEC_BITS_PER_SAMPLE =
            "selectable_codec_bits_per_sample";
    public static final String EXTRA_SELECTABLE_CODEC_CHANNEL_MODES =
            "selectable_codec_channel_modes";
    public static final String EXTRA_SELECTABLE_CODEC_SPECIFIC_1_VALUES =
            "selectable_codec_specific_1_values";
    public static final String EXTRA_OPTIONAL_CODECS_SUPPORTED = "optional_codecs_supported";
    public static final String EXTRA_OPTIONAL_CODECS_ENABLED = "optional_codecs_enabled";
    public static final String EXTRA_OPTIONAL_CODECS_ENABLE = "optional_codecs_enable";
    public static final String EXTRA_READ_TIMESTAMP_MS = "read_timestamp_ms";
    public static final String EXTRA_NATIVE_PATCH_STATUS = "native_patch_status";
    public static final String EXTRA_NATIVE_PATCH_DETAIL = "native_patch_detail";
    public static final String EXTRA_NATIVE_PATCH_PATCHED = "native_patch_patched";
    public static final String EXTRA_NATIVE_PATCH_ORIGINAL = "native_patch_original";
    public static final String EXTRA_NATIVE_PATCH_SUCCESS = "native_patch_success";
    public static final String EXTRA_GAME_MODE_ACTIVE = "game_mode_active";
    public static final String EXTRA_GAME_MODE_TYPE = "game_mode_type";
    public static final String EXTRA_GAME_MODE_SOURCE = "game_mode_source";
    public static final String EXTRA_GAME_MODE_TTL_MS = "game_mode_ttl_ms";

    public static final String TOKEN = "mlcdc-codec-v1";

    private CodecIpc() {
    }
}
