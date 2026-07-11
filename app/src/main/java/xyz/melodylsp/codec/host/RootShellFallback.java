package xyz.melodylsp.codec.host;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.util.MLog;

/**
 * Last-resort write path that runs as the {@code root} user via {@code su}.
 *
 * <p>This kicks in after the host-side reflection ({@link
 * xyz.melodylsp.codec.bt.BluetoothCodecReflect#setCodec}) and the system-bridge AIDL call
 * ({@link CodecBridgeClient}) have both refused the write — typically because
 * {@code com.oplus.melody:fg} is missing {@code BLUETOOTH_PRIVILEGED} and SELinux blocks the
 * AIDL hop. The user explicitly authorises {@code xyz.melodylsp.codec} in their root manager,
 * so we can just shell out.</p>
 *
 * <p>Strategy: stage the developer-options keys via {@code cmd settings put global …}
 * (which the root shell can do unconditionally, no {@code WRITE_SECURE_SETTINGS} needed).
 * This fallback deliberately does not toggle the Bluetooth adapter: doing so would disconnect
 * every watch, car kit, keyboard and headset, and a failed re-enable could leave Bluetooth off.
 * The staged values take effect on the next natural A2DP negotiation.</p>
 *
 * <p>Each invocation lazily probes for {@code su}; absence is cached so subsequent attempts
 * skip straight to "no usable root" rather than spawning processes that cannot resolve the
 * binary.</p>
 */
public final class RootShellFallback {

    private static final long PROCESS_TIMEOUT_MS = 5_000L;

    private final AtomicBoolean rootProbed = new AtomicBoolean(false);
    private volatile boolean rootAvailable;

    /** Explicit outcome: a successful shell write is staged, not confirmed on the live link. */
    public enum ApplyResult {
        NOT_APPLIED(false),
        SETTINGS_STAGED(true);

        private final boolean settingsWritten;

        ApplyResult(boolean settingsWritten) {
            this.settingsWritten = settingsWritten;
        }

        public boolean settingsWritten() {
            return settingsWritten;
        }
    }

    /**
     * Compatibility wrapper for callers that only understand a boolean. A true result means
     * settings were staged successfully; it does not mean the active codec was renegotiated or
     * confirmed. New callers should use {@link #stageSettings(CodecRequest)}.
     */
    public boolean apply(CodecRequest req) {
        return stageSettings(req).settingsWritten();
    }

    /**
     * Best-effort settings staging via {@code su}. The result distinguishes a successful global
     * settings write from a confirmed live A2DP change.
     */
    public ApplyResult stageSettings(CodecRequest req) {
        return stageSettings(req, () -> true);
    }

    /**
     * Stage settings only while the originating UI write is still current. The second guard
     * check is deliberately after the root probe because a root-manager prompt may block long
     * enough for the user to start a newer write while this request is waiting.
     */
    public ApplyResult stageSettings(CodecRequest req, BooleanSupplier shouldContinue) {
        if (!canContinue(shouldContinue)) {
            return ApplyResult.NOT_APPLIED;
        }
        if (!ensureRoot()) {
            return ApplyResult.NOT_APPLIED;
        }
        if (!canContinue(shouldContinue)) {
            MLog.event("root.shell.settings_skip", "reason", "stale_after_root_probe");
            return ApplyResult.NOT_APPLIED;
        }
        List<String> commands = buildCommands(req);
        if (commands.isEmpty()) {
            MLog.w("RootShellFallback: nothing to write for request " + req);
            return ApplyResult.NOT_APPLIED;
        }
        boolean ok = runAsRoot(commands);
        if (ok) {
            MLog.event("root.shell.settings_staged",
                    "codec", req.codecType,
                    "specific1", req.codecSpecific1,
                    "rate", req.sampleRate,
                    "liveState", "unconfirmed_until_natural_renegotiation");
        }
        return ok ? ApplyResult.SETTINGS_STAGED : ApplyResult.NOT_APPLIED;
    }

    private static boolean canContinue(BooleanSupplier shouldContinue) {
        if (shouldContinue == null) return true;
        try {
            return shouldContinue.getAsBoolean();
        } catch (Throwable t) {
            MLog.w("root write continuation guard failed", t);
            return false;
        }
    }

    static List<String> buildCommands(CodecRequest req) {
        java.util.ArrayList<String> cmds = new java.util.ArrayList<>(2);

        // LDAC playback quality maps {1000,1001,1002} → {0,1,2}.
        if (req.codecType == CodecLabelTable.CODEC_LDAC) {
            int idx = mapLdacQualityToIndex(req.codecSpecific1);
            if (idx >= 0) {
                cmds.add("cmd settings put global "
                        + SettingsGlobalFallback.KEY_LDAC_QUALITY + " " + idx);
            }
        }
        // LHDC variants do not have a global quality key in AOSP; codec selection is owned by
        // the OPPO vendor stack. A sample-rate value is only staged for a later negotiation.

        int rateIdx = mapSampleRateToIndex(req.sampleRate);
        if (rateIdx >= 0) {
            cmds.add("cmd settings put global "
                    + SettingsGlobalFallback.KEY_SAMPLE_RATE + " " + rateIdx);
        }

        return cmds;
    }

    private boolean ensureRoot() {
        if (rootProbed.compareAndSet(false, true)) {
            rootAvailable = probeRoot();
            MLog.event("root.probe", "available", rootAvailable);
        }
        return rootAvailable;
    }

    /** Run {@code id -u} via su; root is "available" iff that returns 0. */
    private static boolean probeRoot() {
        return runAsRoot(java.util.Collections.singletonList(
                "if [ \"$(id -u)\" != \"0\" ]; then exit 126; fi"));
    }

    /** Pipe each command (newline-terminated) into {@code su}, then {@code exit}. */
    private static boolean runAsRoot(List<String> commands) {
        Process process = null;
        try {
            process = Runtime.getRuntime().exec("su");
        } catch (IOException e) {
            MLog.w("su not present or unable to spawn", e);
            return false;
        }
        try (DataOutputStream out = new DataOutputStream(process.getOutputStream())) {
            // A failed settings write must not be hidden by a later successful command.
            out.writeBytes("set -e\n");
            for (String c : commands) {
                out.writeBytes(c + "\n");
            }
            out.writeBytes("exit\n");
            out.flush();
        } catch (IOException e) {
            MLog.w("writing to su stdin failed", e);
            destroyQuietly(process);
            return false;
        }
        try {
            boolean done = process.waitFor(PROCESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!done) {
                MLog.w("su timed out after " + PROCESS_TIMEOUT_MS + "ms");
                destroyQuietly(process);
                return false;
            }
            int exit = process.exitValue();
            String stderr = readAll(process.getErrorStream());
            if (exit != 0) {
                MLog.w("su exited " + exit + "; stderr=" + stderr);
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyQuietly(process);
            return false;
        }
    }

    private static String readAll(java.io.InputStream stream) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }

    private static void destroyQuietly(Process process) {
        try {
            process.destroy();
        } catch (Throwable ignored) {
        }
    }

    private static int mapLdacQualityToIndex(long codecSpecific1) {
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_HIGH) return 0;
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_MID) return 1;
        if (codecSpecific1 == CodecLabelTable.LDAC_QUALITY_LOW) return 2;
        return -1;
    }

    private static int mapSampleRateToIndex(int sampleRateBit) {
        switch (sampleRateBit) {
            case 1 << 0: return 1;
            case 1 << 1: return 2;
            case 1 << 2: return 3;
            case 1 << 3: return 4;
            default: return -1;
        }
    }
}
