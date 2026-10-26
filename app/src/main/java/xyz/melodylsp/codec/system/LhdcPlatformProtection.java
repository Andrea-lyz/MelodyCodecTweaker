package xyz.melodylsp.codec.system;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;

/**
 * Conservative codec-configuration fallback for an unobserved software LHDC V5 encoder.
 * Unlike the software governor this can restart the audio input. It performs at most one
 * 1000 -> 900 transition per user selection; a codec-status acknowledgement is configuration
 * evidence, never a measurement of the hardware encoder's actual bitrate.
 */
final class LhdcPlatformProtection {
    static final long START_GUARD_MS = 25_000L;
    static final long ENCODER_GRACE_MS = 8_000L;
    static final long CHOPPY_WINDOW_MS = 30_000L;
    static final long CONFIRM_TIMEOUT_MS = 15_000L;
    private static final long CHOPPY_DEDUP_MS = 1_000L;
    private static final int LHDC_V5 = 19;
    private static final int FIXED_1000 = 8;
    private static final int FIXED_900 = 7;

    static final class Decision {
        final long id;
        final CodecSnapshot before;
        final CodecRequest request;
        final boolean restoring;

        Decision(long id, CodecSnapshot before, long specific1, boolean restoring) {
            this.id = id;
            this.before = before;
            this.restoring = restoring;
            this.request = new CodecRequest(before.mac, before.activeCodecType,
                    specific1, before.activeCodecSpecific2, before.activeCodecSpecific3,
                    before.activeCodecSpecific4, before.activeSampleRate,
                    before.activeBitsPerSample, before.activeChannelMode);
        }
    }

    private static final class State {
        CodecSnapshot observed;
        CodecSnapshot original;
        long expectedSpecific1;
        long playingSinceMs = -1L;
        long missingEncoderSinceMs = -1L;
        final ArrayDeque<Long> choppy = new ArrayDeque<>();
        Decision pending;
        long issuedAtMs;
        boolean armed;
        boolean acceptsReports;
        boolean confirmed;
        boolean attempted;
        boolean restoreAttempted;
        String phase = "waiting";
    }

    private final Map<String, State> states = new HashMap<>();
    private long nextId;
    private String activeMac;

    synchronized void onUserWrite(String mac) {
        states.remove(key(mac));
    }

    synchronized void onChoppy(String mac, long nowMs) {
        if (!key(mac).equals(activeMac)) return;
        State state = states.get(key(mac));
        if (state == null || !state.acceptsReports || state.attempted) return;
        prune(state, nowMs);
        if (!state.choppy.isEmpty()
                && nowMs - state.choppy.peekLast() < CHOPPY_DEDUP_MS) return;
        state.choppy.addLast(nowMs);
        while (state.choppy.size() > 2) state.choppy.removeFirst();
    }

    synchronized void onInactive(String mac) {
        State state = states.get(key(mac));
        if (state == null) return;
        state.armed = false;
        state.acceptsReports = false;
        state.playingSinceMs = -1L;
        state.missingEncoderSinceMs = -1L;
        state.choppy.clear();
    }

    /** Called only for the currently active A2DP device, with a freshly queried codec status. */
    synchronized Decision observe(CodecSnapshot live, boolean qualitySelected,
            boolean governorEnabled, boolean softwareStreaming, Boolean playing,
            boolean canWrite, long nowMs) {
        if (live == null || live.mac == null) return null;
        String mac = key(live.mac);
        if (!mac.equals(activeMac)) {
            onInactive(activeMac);
            activeMac = mac;
        }
        State state = states.computeIfAbsent(mac, ignored -> new State());
        if (state.original == null && state.observed != null
                && !sameConfig(state.observed, live)) {
            onInactive(mac);
        }
        state.observed = live;
        if (state.original != null && !sameFormat(state.original, live)) {
            // A sample-rate/codec/feature change belongs to another configuration. Never undo it.
            states.remove(mac);
            return null;
        }
        boolean atOriginal = state.original != null
                && live.activeCodecSpecific1 == state.original.activeCodecSpecific1;
        boolean atProtected = owns(state, live);
        if (state.original != null && !atOriginal && !atProtected) {
            states.remove(mac);
            return null;
        }
        if (state.pending != null) {
            if (state.pending.restoring && atOriginal) {
                states.remove(mac);
                return null;
            }
            if (!state.pending.restoring && atProtected) {
                state.pending = null;
                state.confirmed = true;
                state.phase = "protected_900";
            } else if (nowMs - state.issuedAtMs >= CONFIRM_TIMEOUT_MS) {
                state.phase = state.pending.restoring ? "restore_timeout" : "write_timeout";
                state.pending = null;
                // Retain ownership evidence for a late acknowledgement, including switch-off.
            } else {
                return null;
            }
        }
        if (atProtected) {
            state.confirmed = true;
            if (state.phase.equals("write_timeout")) state.phase = "protected_900";
            state.armed = false;
            if (qualitySelected && (!governorEnabled || softwareStreaming)
                    && canWrite && !state.restoreAttempted) {
                state.restoreAttempted = true;
                return issue(state, live, state.original.activeCodecSpecific1, true, nowMs);
            }
            return null;
        }
        if (state.confirmed) state.phase = "reverted";
        state.confirmed = false;
        state.acceptsReports = qualitySelected && governorEnabled && !softwareStreaming && canWrite
                && live.activeCodecType == LHDC_V5
                && singleBit(live.activeSampleRate) && singleBit(live.activeBitsPerSample)
                && singleBit(live.activeChannelMode)
                && (live.activeCodecSpecific1 & 0xffL) == FIXED_1000;
        state.armed = state.acceptsReports && Boolean.TRUE.equals(playing);
        if (!state.armed) {
            state.playingSinceMs = -1L;
            state.missingEncoderSinceMs = -1L;
            if (!state.acceptsReports) state.choppy.clear();
            prune(state, nowMs);
            return null;
        }
        if (state.playingSinceMs < 0L) state.playingSinceMs = nowMs;
        if (state.missingEncoderSinceMs < 0L) state.missingEncoderSinceMs = nowMs;
        prune(state, nowMs);
        if (state.attempted || state.choppy.size() < 2
                || nowMs - state.playingSinceMs < START_GUARD_MS
                || nowMs - state.missingEncoderSinceMs < ENCODER_GRACE_MS) return null;
        state.original = live;
        state.expectedSpecific1 = (live.activeCodecSpecific1 & ~0xffL) | FIXED_900;
        state.attempted = true;
        state.armed = false;
        return issue(state, live, state.expectedSpecific1, false, nowMs);
    }

    synchronized void onSubmission(String mac, long id, boolean accepted) {
        State state = states.get(key(mac));
        if (state == null || state.pending == null || state.pending.id != id || accepted) return;
        state.phase = state.pending.restoring ? "restore_rejected" : "write_rejected";
        if (!state.pending.restoring) state.original = null;
        state.pending = null;
        // Do not retry a rejected write on every poll, or invent an acknowledgement.
    }

    synchronized CodecSnapshot annotate(CodecSnapshot snapshot) {
        if (snapshot == null) return null;
        State state = states.get(key(snapshot.mac));
        return owns(state, snapshot)
                ? snapshot.withGovernorSelection(state.original.activeCodecSpecific1) : snapshot;
    }

    synchronized String phase(String mac) {
        State state = states.get(key(mac));
        return state == null ? "idle" : state.phase;
    }

    synchronized boolean hasOverride(String mac) {
        State state = states.get(key(mac));
        return state != null && state.original != null;
    }

    synchronized boolean available(String mac) {
        State state = states.get(key(mac));
        return state != null && (state.acceptsReports || state.original != null);
    }

    /** True while the next choppy reports would actually be able to arm the fallback. */
    synchronized boolean acceptsReports(String mac) {
        State state = states.get(key(mac));
        return state != null && state.acceptsReports;
    }

    synchronized boolean confirmed(String mac) {
        State state = states.get(key(mac));
        return state != null && state.confirmed;
    }

    synchronized boolean isPending(String mac, long id) {
        State state = states.get(key(mac));
        return state != null && state.pending != null && state.pending.id == id;
    }

    private Decision issue(State state, CodecSnapshot before, long specific1,
            boolean restoring, long nowMs) {
        Decision decision = new Decision(++nextId, before, specific1, restoring);
        state.pending = decision;
        state.issuedAtMs = nowMs;
        state.phase = restoring ? "restoring_1000" : "pending_900";
        return decision;
    }

    private static boolean owns(State state, CodecSnapshot live) {
        return state != null && state.original != null && live != null
                && sameFormat(state.original, live)
                && live.activeCodecSpecific1 == state.expectedSpecific1;
    }

    static boolean sameConfig(CodecSnapshot a, CodecSnapshot b) {
        return sameFormat(a, b) && a.activeCodecSpecific1 == b.activeCodecSpecific1;
    }

    private static boolean sameFormat(CodecSnapshot a, CodecSnapshot b) {
        return a != null && b != null && key(a.mac).equals(key(b.mac))
                && a.activeCodecType == b.activeCodecType
                && a.activeSampleRate == b.activeSampleRate
                && a.activeBitsPerSample == b.activeBitsPerSample
                && a.activeChannelMode == b.activeChannelMode
                && a.activeCodecSpecific2 == b.activeCodecSpecific2
                && a.activeCodecSpecific3 == b.activeCodecSpecific3
                && a.activeCodecSpecific4 == b.activeCodecSpecific4;
    }

    private static void prune(State state, long nowMs) {
        while (!state.choppy.isEmpty()
                && (nowMs < state.choppy.peekFirst()
                || nowMs - state.choppy.peekFirst() > CHOPPY_WINDOW_MS)) {
            state.choppy.removeFirst();
        }
    }

    private static String key(String mac) {
        return mac == null ? "" : mac.toUpperCase(Locale.ROOT);
    }

    private static boolean singleBit(int value) {
        return value > 0 && (value & (value - 1)) == 0;
    }
}
