package xyz.melodylsp.codec.bridge;

/** SBC control markers used by the OPlus classic game low-latency path. */
public final class GameModeCodecPolicy {
    private GameModeCodecPolicy() {
    }

    public static boolean isSbcLowLatencyEntry(int codecType, long specific2) {
        // A2dpLowLatencyCodec: 2/3 enter low latency; 5 requests normal mode again.
        return codecType == 0 && (specific2 == 2L || specific2 == 3L);
    }

    public static boolean shouldBlockSbcEntry(
            boolean lowLatencyEnabled, int configCount, int codecType, long specific2) {
        // Only the single explicit control request is known here. Preserve mixed codec lists.
        return !lowLatencyEnabled && configCount == 1
                && isSbcLowLatencyEntry(codecType, specific2);
    }
}
