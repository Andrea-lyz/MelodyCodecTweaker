package xyz.melodylsp.codec.leaudio;

/**
 * Shared constants for the LE Audio one-tap IPC channel (TODO B2 — Phase 2).
 *
 * <p>The same module APK is loaded into both {@code com.oplus.melody} (UI side) and
 * {@code com.android.bluetooth} / {@code com.oplus.wirelesssettings} (privileged toggle side),
 * so both ends agree on these
 * literals at compile time. The melody side broadcasts {@link #ACTION_SET_LE_AUDIO} /
 * {@link #ACTION_QUERY_LE_AUDIO}; the privileged side performs the actual
 * LE Audio profile write and echoes
 * {@link #ACTION_LE_AUDIO_STATE} back.</p>
 *
 * <p>Broadcasts are always targeted with {@code Intent#setPackage} (so they never leave the
 * intended app) and carry {@link #EXTRA_TOKEN} = {@link #TOKEN}. The token is a coarse guard
 * against unrelated apps poking the receiver; it is not a cryptographic boundary. The action
 * strings are private to this module.</p>
 */
public final class LeAudioIpc {

    public static final String MELODY_PKG = "com.oplus.melody";
    public static final String WIRELESS_SETTINGS_PKG = "com.oplus.wirelesssettings";
    public static final String BLUETOOTH_PKG = "com.android.bluetooth";

    /** melody → privileged bridge: request an enable/disable of LE Audio for a device. */
    public static final String ACTION_SET_LE_AUDIO = "xyz.melodylsp.codec.action.SET_LE_AUDIO";
    /** melody → privileged bridge: request the current support + enabled state for a device. */
    public static final String ACTION_QUERY_LE_AUDIO = "xyz.melodylsp.codec.action.QUERY_LE_AUDIO";
    /** privileged bridge → melody: authoritative support + enabled state for a device. */
    public static final String ACTION_LE_AUDIO_STATE = "xyz.melodylsp.codec.action.LE_AUDIO_STATE";

    /**
     * melody → privileged bridge: module game low-latency state. Sent when Melody's 游戏功能
     * controller starts, rows are attached, and on every toggle. The host verifies the mirror
     * through ACTION_QUERY_GAME_LATENCY_STATE and retries until the requested values arrive.
     */
    public static final String ACTION_SET_GAME_LATENCY_STATE =
            "xyz.melodylsp.codec.action.SET_GAME_LATENCY_STATE";
    /** melody -> bluetooth: read back the applied game configuration. */
    public static final String ACTION_QUERY_GAME_LATENCY_STATE =
            "xyz.melodylsp.codec.action.QUERY_GAME_LATENCY_STATE";
    /** bluetooth -> melody: configuration readback, correlated with the host request. */
    public static final String ACTION_GAME_LATENCY_STATE =
            "xyz.melodylsp.codec.action.GAME_LATENCY_STATE";
    /** bluetooth -> melody: the receiver is ready; resend the authoritative host preferences. */
    public static final String ACTION_REQUEST_GAME_LATENCY_STATE =
            "xyz.melodylsp.codec.action.REQUEST_GAME_LATENCY_STATE";
    /** Bluetooth-side mirror also used by GameLeaController. */
    public static final String GAME_LATENCY_PREFS = "melody_codec_game_latency";

    /** String, the device MAC the request / response concerns. */
    public static final String EXTRA_MAC = "mac";
    /** boolean, the requested enable state for {@link #ACTION_SET_LE_AUDIO}. */
    public static final String EXTRA_ENABLE = "enable";
    /** boolean, 游戏低延迟 master switch (default true; off keeps LHDC during games). */
    public static final String EXTRA_GAME_LOW_LATENCY_ENABLED = "game_low_latency_enabled";
    /** boolean, 游戏自动切换 LE Audio switch (default false). */
    public static final String EXTRA_GAME_AUTO_LEA_ENABLED = "game_auto_lea_enabled";
    public static final String EXTRA_GAME_STATE_REQUEST_ID = "game_state_request_id";
    public static final String EXTRA_GAME_STATE_READY = "game_state_ready";
    /** boolean, query-side hint that a fresh OEM/profile event warrants reconnect repair. */
    public static final String EXTRA_REPAIR_CONNECTION = "repair_connection";
    /** boolean, whether the device supports LE Audio (in {@link #ACTION_LE_AUDIO_STATE}). */
    public static final String EXTRA_SUPPORTED = "supported";
    /** boolean, whether LE Audio is currently enabled (in {@link #ACTION_LE_AUDIO_STATE}). */
    public static final String EXTRA_ENABLED = "enabled";
    /** boolean, whether the LE Audio profile is connected (not necessarily selected for media). */
    public static final String EXTRA_CONNECTED = "connected";
    /** boolean, X4-class "LE Audio + classic coexistence" report used by the official chain. */
    public static final String EXTRA_LEA_COEXIST = "lea_coexist";
    /** int, media transport for the requested device, reported by the bluetooth process. */
    public static final String EXTRA_MEDIA_PROFILE = "media_profile";
    public static final int MEDIA_PROFILE_UNKNOWN = 0;
    public static final int MEDIA_PROFILE_OTHER = -1;
    public static final int MEDIA_PROFILE_A2DP = 2;
    public static final int MEDIA_PROFILE_LE_AUDIO = 22;
    /** boolean, whether the toggle request succeeded (in {@link #ACTION_LE_AUDIO_STATE}). */
    public static final String EXTRA_OK = "ok";
    /** String, the coarse anti-spoof token shared by both module instances. */
    public static final String EXTRA_TOKEN = "token";

    /** Compile-time shared secret; identical in both processes because it is the same APK. */
    public static final String TOKEN = "mlcdc-le-audio-v1";

    private LeAudioIpc() {
    }
}
