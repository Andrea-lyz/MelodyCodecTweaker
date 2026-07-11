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

    /** String, the device MAC the request / response concerns. */
    public static final String EXTRA_MAC = "mac";
    /** boolean, the requested enable state for {@link #ACTION_SET_LE_AUDIO}. */
    public static final String EXTRA_ENABLE = "enable";
    /** boolean, query-side hint that a fresh OEM/profile event warrants reconnect repair. */
    public static final String EXTRA_REPAIR_CONNECTION = "repair_connection";
    /** boolean, whether the device supports LE Audio (in {@link #ACTION_LE_AUDIO_STATE}). */
    public static final String EXTRA_SUPPORTED = "supported";
    /** boolean, whether LE Audio is currently enabled (in {@link #ACTION_LE_AUDIO_STATE}). */
    public static final String EXTRA_ENABLED = "enabled";
    /** boolean, whether the LE Audio profile is currently connected / active. */
    public static final String EXTRA_CONNECTED = "connected";
    /** boolean, whether the toggle request succeeded (in {@link #ACTION_LE_AUDIO_STATE}). */
    public static final String EXTRA_OK = "ok";
    /** String, the coarse anti-spoof token shared by both module instances. */
    public static final String EXTRA_TOKEN = "token";

    /** Compile-time shared secret; identical in both processes because it is the same APK. */
    public static final String TOKEN = "mlcdc-le-audio-v1";

    private LeAudioIpc() {
    }
}
