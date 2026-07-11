package xyz.melodylsp.codec.host;

import xyz.melodylsp.codec.bridge.CodecSnapshot;

/** Result of a {@code CodecBridgeClient.setCodec} attempt. */
public final class WriteResult {

    public enum Path {
        DIRECT_API,
        SYSTEM_BRIDGE,
        SYSTEM_BROADCAST,
        SETTINGS_GLOBAL,
        /** {@code su} → stage {@code cmd settings put global …}; live state still needs readback. */
        ROOT_SHELL
    }

    public enum Outcome {
        CONFIRMED,
        TIMEOUT_ROLLED_BACK,
        FAILED
    }

    public final Path path;
    public final Outcome outcome;
    /** Snapshot read when the write confirmed or after the timeout window, when available. */
    public final CodecSnapshot rollbackSnapshot;
    public final Throwable cause;

    private WriteResult(Path path, Outcome outcome, CodecSnapshot rollbackSnapshot, Throwable cause) {
        this.path = path;
        this.outcome = outcome;
        this.rollbackSnapshot = rollbackSnapshot;
        this.cause = cause;
    }

    public static WriteResult confirmed(Path path) {
        return new WriteResult(path, Outcome.CONFIRMED, null, null);
    }

    public static WriteResult confirmed(Path path, CodecSnapshot snapshot) {
        return new WriteResult(path, Outcome.CONFIRMED, snapshot, null);
    }

    public static WriteResult rolledBack(Path path, CodecSnapshot snapshot) {
        return new WriteResult(path, Outcome.TIMEOUT_ROLLED_BACK, snapshot, null);
    }

    public static WriteResult failed(Path path, Throwable cause) {
        return new WriteResult(path, Outcome.FAILED, null, cause);
    }
}
