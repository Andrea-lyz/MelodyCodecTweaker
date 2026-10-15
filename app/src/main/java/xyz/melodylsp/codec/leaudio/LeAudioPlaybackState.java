package xyz.melodylsp.codec.leaudio;

/** Separates permission to use LE Audio from the transport selected for media. */
public enum LeAudioPlaybackState {
    CLASSIC,
    CONNECTING,
    LE_ACTIVE,
    ROUTE_UNKNOWN;

    public static LeAudioPlaybackState resolve(
            boolean enabled, boolean connected, boolean coexist, int mediaProfile) {
        if (!enabled) return CLASSIC;
        if (!coexist) return connected ? LE_ACTIVE : CONNECTING;
        // A coexistence device may leave LE allowed while playing normally over A2DP.
        if (!connected || mediaProfile == LeAudioIpc.MEDIA_PROFILE_A2DP) return CLASSIC;
        if (mediaProfile == LeAudioIpc.MEDIA_PROFILE_LE_AUDIO) return LE_ACTIVE;
        // Both profiles can be connected. Connection alone does not identify the media route.
        return ROUTE_UNKNOWN;
    }
}
