package xyz.melodylsp.codec.system;

import xyz.melodylsp.codec.BuildConfig;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import xyz.melodylsp.codec.bridge.LhdcQualityPolicy;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.BuildConfig;

/**
 * Experimental in-process replacement for the KSU / Magisk LHDC V5 native overlay.
 *
 * <p>ColorOS 16 changed {@code libbluetooth_jni.so} to ignore fixed LHDC V5 target bitrate
 * requests. The KSU module patches the on-disk library through a systemless mount, which works
 * but leaves a visible mount drift. This helper tries the same 4-byte patch against the already
 * mapped library inside {@code com.android.bluetooth}: scan the mapped bytes, temporarily make the
 * target page writable, write the branch instruction, verify, then restore page protection.</p>
 */
final class NativeLhdcMemoryPatch {

    private static final String LIB_NAME = "libbluetooth_jni.so";
    private static final PatternSpec[] PATTERN_SPECS = {
            new PatternSpec(
                    "branch_plus_69",
                    hex("1f0900f1a2080054e83d80529b008052"),
                    hex("1f0900f145000014e83d80529b008052"),
                    4,
                    hex("45000014")),
            new PatternSpec(
                    "branch_plus_23_op15",
                    hex("1f0900f1e2020054283d805299008052"),
                    hex("1f0900f117000014283d805299008052"),
                    4,
                    hex("17000014")),
            new PatternSpec(
                    "branch_plus_73_plc110",
                    hex("1f0900f122090054680f80529a008052"),
                    hex("1f0900f149000014680f80529a008052"),
                    4,
                    hex("49000014")),
            new PatternSpec(
                    "branch_plus_68_pjz110_1609401",
                    hex("1f0900f182080054a83e80529a008052"),
                    hex("1f0900f144000014a83e80529a008052"),
                    4,
                    hex("44000014")),
            new PatternSpec(
                    "branch_plus_27_rmx6688",
                    hex("1f0900f182030054a80e8052b8cdfff0"),
                    hex("1f0900f11c000014a80e8052b8cdfff0"),
                    4,
                    hex("1c000014")),
    };
    /**
     * ColorOS 16 inlines A2DP codec equality but omits LHDC V5 on every known version line.
     * Each entry is the unsupported-codec logger default block (reached with CodecId
     * 0x4c35053aff) for one build; the replacement reproduces the older OPlus LHDC V5
     * equality mask. It accepts only a valid current LHDC V5 CIE whose
     * sample-rate/channel/feature fields match the target, while deliberately ignoring the
     * quality/bitrate bits. Every other codec or material CIE change falls through to the
     * original restart path.
     *
     * <p>Group A (CIE pointer x21, CIE on stack x29-#0x70): OP15/Ace6T 16.0.7.201,
     * PJZ110 16.0.8.301 (+ PLK110), PJZ110 16.0.9.401 (+ .402). Group B (x28, x29-#0x60):
     * PLC110 16.0.8.300, RMX6688. The reject/accept tails (+0x90/+0x98) are structurally
     * identical across all five builds, so only the register/stack-offset encodings differ.
     *
     * <p>These are intentionally exact whole-block signatures for the evidence builds. An OTA
     * that recompiles the function is unsupported instead of receiving a guessed patch.</p>
     */
    private static final CodeBlockSpec[] LHDC_V5_QUALITY_SWITCH_SPECS = {
            new CodeBlockSpec(
                    "lhdcv5_quality_equals_pjz110_1609401_1609402",
                    hex("68ac805289b2ffd029613191a88316b8c8b1ffd008353e91a92102a9e8018052"
                            + "a90302d11f2003d50aa60210a93900f9aaa107a929008052aa0301d141b0ff90"
                            + "21b4139183b5ffd0638c2a91a26302d1a5c302d1c000805264038052a92900a9"
                            + "a80900f99d2b17940a000014"),
                    hex("e95f87d2a9a0a6f28909c0f21f0109eb01040054aa0359385f350071a1030054"
                            + "aa2359385ffd037141030054aa3359b84ba780525f010b6bc1020054aa735978"
                            + "ab8689525f010b6b41020054aa9359b8ab9240b84a010b4aabe680520b02b872"
                            + "5f010b6a610100540c000014"),
                    0x14000024),
            new CodeBlockSpec(
                    "lhdcv5_quality_equals_op15",
                    hex("68ac8052a9b2ffd029a12491a88316b8e8b1ffd008093491a92102a9e8018052"
                            + "a90302d11f2003d50aa60210a93900f9aaa107a929008052aa0301d161b0ff90"
                            + "21b01191a3b5ffd063a00e91a26302d1a5c302d1c000805264038052a92900a9"
                            + "a80900f9592717940a000014"),
                    hex("e95f87d2a9a0a6f28909c0f21f0109eb01040054aa0359385f350071a1030054"
                            + "aa2359385ffd037141030054aa3359b84ba780525f010b6bc1020054aa735978"
                            + "ab8689525f010b6b41020054aa9359b8ab9240b84a010b4aabe680520b02b872"
                            + "5f010b6a610100540c000014"),
                    0x14000024),
            new CodeBlockSpec(
                    "lhdcv5_quality_equals_pjz110_1608301",
                    hex("68ac805289b2ffd029f93091a88316b8c8b1ffd008193e91a92102a9e8018052"
                            + "a90302d11f2003d50aa60210a93900f9aaa107a929008052aa0301d141b0ff90"
                            + "2144139183b5ffd063942a91a26302d1a5c302d1c000805264038052a92900a9"
                            + "a80900f9092917940a000014"),
                    hex("e95f87d2a9a0a6f28909c0f21f0109eb01040054aa0359385f350071a1030054"
                            + "aa2359385ffd037141030054aa3359b84ba780525f010b6bc1020054aa735978"
                            + "ab8689525f010b6b41020054aa9359b8ab9240b84a010b4aabe680520b02b872"
                            + "5f010b6a610100540c000014"),
                    0x14000024),
            new CodeBlockSpec(
                    "lhdcv5_quality_equals_plc110_1608300",
                    hex("68ac805249b5fff029fd0d918ab4fff04add1b912b008052a88317b8a8c301d1"
                            + "01b3ff9021c02991a92902a91f2003d58a9c0210e901805243b8fff063a41891"
                            + "a22302d1a58302d1a82906a9a80301d1c000805264038052a93900f9ab2100a9"
                            + "a90900f933e519940a000014"),
                    hex("e95f87d2a9a0a6f28909c0f21f0109eb01040054aa035a385f350071a1030054"
                            + "aa235a385ffd037141030054aa335ab84ba780525f010b6bc1020054aa735a78"
                            + "ab8689525f010b6b41020054aa935ab88b9340b84a010b4aabe680520b02b872"
                            + "5f010b6a610100540c000014"),
                    0x14000024),
            new CodeBlockSpec(
                    "lhdcv5_quality_equals_rmx6688",
                    hex("68ac805289b5fff029150091cab4fff04aa510912b008052a88317b8a8c301d1"
                            + "41b3ff90212c2891a92902a91f2003d58a9c0210e901805283b8ffd063ec3b91"
                            + "a22302d1a58302d1a82906a9a80301d1c000805264038052a93900f9ab2100a9"
                            + "a90900f9dfe219940a000014"),
                    hex("e95f87d2a9a0a6f28909c0f21f0109eb01040054aa035a385f350071a1030054"
                            + "aa235a385ffd037141030054aa335ab84ba780525f010b6bc1020054aa735a78"
                            + "ab8689525f010b6b41020054aa935ab88b9340b84a010b4aabe680520b02b872"
                            + "5f010b6a610100540c000014"),
                    0x14000024),
    };
    private static final int MAX_RANGE_BYTES = 64 * 1024 * 1024;
    private static final int NATIVE_PATCH_OK = 0;
    private static final int NATIVE_PATCH_ALREADY_APPLIED = 1;
    private static volatile Method cachedPeekByteArray;
    private static volatile String nativeLibraryPath;
    private static volatile String nativeLoadError;
    private static volatile boolean nativeLoadAttempted;
    private static volatile boolean nativeLoaded;
    private static volatile PatchResult lastResult;
    private static volatile PatchResult lastQualitySwitchResult;
    /**
     * Specs inside one group share identical patched bytes, so a later rescan cannot attribute
     * an already-applied block by signature alone. Remember the spec this process applied so
     * diagnostics report the actual build line instead of the first table entry. In-memory
     * patches do not survive process death, so process-local state is always accurate here.
     */
    private static volatile String appliedQualitySwitchSpecName;
    private static volatile boolean governorInstalled;
    private static volatile int governorPolicy = LhdcQualityPolicy.ADAPTIVE;
    /**
     * Java-side replay cache for the native probe ceiling. Updated before any install attempt so
     * an early 900 kbps capability broadcast survives a late or failed governor install.
     */
    private static volatile int desiredGovernorProbeCeilingKbps = 1000;

    private NativeLhdcMemoryPatch() {
    }

    /** Visible for tests. */
    static PatternSpec[] patternsForTest() {
        return PATTERN_SPECS;
    }

    static void configureModuleContext(Context hostContext) {
        if (hostContext == null || nativeLibraryPath != null) return;
        try {
            Context moduleContext = hostContext.createPackageContext(
                    BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
            ApplicationInfo info = moduleContext.getApplicationInfo();
            if (info != null && info.nativeLibraryDir != null) {
                nativeLibraryPath = info.nativeLibraryDir + "/libmelody_lhdc_patch.so";
                nativeLoadAttempted = false;
                nativeLoadError = null;
            }
        } catch (Throwable t) {
            nativeLoadError = "context:" + describeThrowable(t);
        }
    }

    static synchronized PatchResult apply() {
        // Toast-matrix 设备验证门：debug 构建且显式开启时模拟补丁未适配（release 恒 false，
        // 且此处 DEBUG 二次门控保证 release 永不生效）。
        if (BuildConfig.DEBUG && BuildConfig.TOAST_TEST_BLOCK_BITRATE) {
            PatchResult blocked = PatchResult.unsupported(0, 0);
            lastResult = blocked;
            return blocked;
        }
        PatchResult result;
        try {
            result = applyUnchecked();
        } catch (Throwable t) {
            result = PatchResult.failed("exception:" + t.getClass().getSimpleName()
                    + ":" + t.getMessage());
        }
        lastResult = result;
        return result;
    }

    static PatchResult lastResult() {
        return lastResult;
    }

    static synchronized PatchResult applyQualitySwitchGuard() {
        if (BuildConfig.DEBUG && BuildConfig.TOAST_TEST_BLOCK_FAST_SWITCH) {
            PatchResult blocked = PatchResult.unsupported(0, 0);
            lastQualitySwitchResult = blocked;
            return blocked;
        }
        PatchResult result;
        try {
            result = applyQualitySwitchGuardUnchecked();
        } catch (Throwable t) {
            result = PatchResult.failed("exception:" + t.getClass().getSimpleName()
                    + ":" + t.getMessage());
        }
        lastQualitySwitchResult = result;
        return result;
    }

    static PatchResult lastQualitySwitchResult() {
        return lastQualitySwitchResult;
    }

    /** Installs the fixed-bitrate encoder capture. Safe to call repeatedly. */
    static synchronized boolean installGovernor() {
        if (governorInstalled) return true;
        if (!ensureNativeLoaded()) {
            MLog.event("lhdc.governor.install",
                    "ok", false,
                    "result", "native_helper_unavailable",
                    "detail", nativeLoadError);
            return false;
        }
        int result;
        try {
            result = nativeInstallGovernor();
        } catch (Throwable t) {
            MLog.w("lhdc governor native install failed", t);
            return false;
        }
        governorInstalled = result > 0;
        MLog.event("lhdc.governor.install", "ok", governorInstalled, "result", result);
        if (governorInstalled) replayGovernorProbeCeiling();
        return governorInstalled;
    }

    static synchronized boolean setGovernorPolicy(int policy) {
        if (!ensureNativeLoaded()) return false;
        try {
            nativeSetGovernorPolicy(policy);
            governorPolicy = LhdcQualityPolicy.normalize(policy);
            if (!governorInstalled) installGovernor();
            return true;
        } catch (Throwable t) {
            MLog.w("lhdc governor policy update failed", t);
            return false;
        }
    }

    static String governorModeForDiagnostics() {
        return BuildConfig.LHDC_GOVERNOR_MODE;
    }

    /** Normalizes any input to the supported probe-ceiling ladder; 0/negative means "unknown" -> 1000. */
    static int normalizeProbeCeilingKbps(int ceilingKbps) {
        if (ceilingKbps <= 0) return 1000;
        if (ceilingKbps <= 400) return 400;
        if (ceilingKbps <= 500) return 500;
        if (ceilingKbps <= 900) return 900;
        return 1000;
    }

    /**
     * Phase N-1 requestId transaction: every Target_Cap write is stamped with a monotonically
     * increasing id. Native events carry the id of the request that caused them, so Java can
     * drop stale results from superseded transactions.
     */
    private static final AtomicInteger GOVERNOR_REQUEST_COUNTER = new AtomicInteger();
    private static volatile int lastIssuedGovernorRequestId;

    /** requestId 0 means the event is not bound to a Java request (native init path). */
    static boolean isGovernorEventCurrent(GovernorEvent event) {
        if (event == null || event.requestId == 0) return true;
        return event.requestId == lastIssuedGovernorRequestId;
    }

    /** Updates the desired ceiling cache and returns the previously cached value. */
    static synchronized int cacheDesiredGovernorProbeCeilingKbps(int ceilingKbps) {
        int previous = desiredGovernorProbeCeilingKbps;
        desiredGovernorProbeCeilingKbps = normalizeProbeCeilingKbps(ceilingKbps);
        return previous;
    }

    static int currentDesiredGovernorProbeCeilingKbps() {
        return desiredGovernorProbeCeilingKbps;
    }

    /** Last rung actually applied to native (0 = unknown). Guards against same-value re-writes
     *  that would open a phantom transaction with no native event to confirm it. */
    private static volatile int lastAppliedGovernorTargetKbps;

    /**
     * Applies a Target_Cap write and returns the requestId of the new transaction, or 0 when
     * nothing was issued (governor unavailable, or the same rung is already applied). Callers
     * only register a switch transaction when the return value is &gt; 0.
     */
    static synchronized int setGovernorProbeCeilingKbps(int ceilingKbps) {
        return setGovernorProbeCeilingKbpsInternal(ceilingKbps, true);
    }

    /**
     * Bypass writes (peer-ceiling sync, session cleanup, policy broadcast) must not advance the
     * transaction id: an in-flight controller transaction keeps its id so its events stay
     * confirmable, and no phantom transaction is registered.
     */
    static synchronized int setGovernorProbeCeilingKbpsQuiet(int ceilingKbps) {
        return setGovernorProbeCeilingKbpsInternal(ceilingKbps, false);
    }

    private static int setGovernorProbeCeilingKbpsInternal(
            int ceilingKbps, boolean transaction) {
        int previous = cacheDesiredGovernorProbeCeilingKbps(ceilingKbps);
        int normalized = desiredGovernorProbeCeilingKbps;
        if (!governorInstalled && !installGovernor()) {
            // Value stays cached and is replayed by installGovernor() once it succeeds.
            MLog.event("lhdc.governor.ceiling_cached",
                    "ceilingKbps", normalized,
                    "previousKbps", previous,
                    "reason", "governor_not_installed");
            return 0;
        }
        if (!governorInstalled) return 0;
        if (normalized == lastAppliedGovernorTargetKbps) {
            // Same rung already applied: native set_rate would be a no-op and emit no
            // TRANSITION_APPLIED, so a transaction opened here could never be confirmed.
            MLog.event("lhdc.governor.ceiling_unchanged",
                    "ceilingKbps", normalized,
                    "previousKbps", previous);
            return 0;
        }
        try {
            int requestId = transaction ? GOVERNOR_REQUEST_COUNTER.incrementAndGet() : 0;
            if (transaction) lastIssuedGovernorRequestId = requestId;
            nativeSetGovernorTargetKbps(normalized, requestId);
            lastAppliedGovernorTargetKbps = normalized;
            MLog.event("lhdc.governor.ceiling_applied",
                    "ceilingKbps", normalized,
                    "previousKbps", previous,
                    "requestId", requestId);
            return requestId;
        } catch (Throwable t) {
            MLog.w("lhdc governor probe ceiling update failed", t);
            return 0;
        }
    }

    private static void replayGovernorProbeCeiling() {
        int ceilingKbps = desiredGovernorProbeCeilingKbps;
        try {
            int requestId = setGovernorProbeCeilingKbpsInternal(ceilingKbps, true);
            if (requestId > 0) {
                MLog.event("lhdc.governor.ceiling_replayed_after_install",
                        "ceilingKbps", ceilingKbps,
                        "requestId", requestId);
            }
        } catch (Throwable t) {
            MLog.w("lhdc governor probe ceiling replay failed", t);
        }
    }

    static void reportRemoteChoppy(int level) {
        if (level <= 0) return;
        if (!governorInstalled && !installGovernor()) return;
        try {
            nativeReportChoppy(level);
        } catch (Throwable t) {
            MLog.w("lhdc governor choppy report failed", t);
        }
    }

    static boolean shouldSampleQueue() {
        return governorInstalled && governorPolicy == LhdcQualityPolicy.QUALITY;
    }

    static int currentGovernorBitrateKbps() {
        if (!governorInstalled
                || !nativeLoaded
                || (governorPolicy != LhdcQualityPolicy.QUALITY
                && governorPolicy != LhdcQualityPolicy.ADAPTIVE)) return 0;
        try {
            return nativeGetGovernorBitrateKbps();
        } catch (Throwable t) {
            MLog.w("lhdc governor bitrate read failed", t);
            return 0;
        }
    }

    static int currentGovernorRequestedBitrateKbps() {
        if (!governorInstalled || !nativeLoaded) return 0;
        try {
            return nativeGetGovernorRequestedBitrateKbps();
        } catch (Throwable t) {
            MLog.w("lhdc governor requested bitrate read failed", t);
            return 0;
        }
    }

    static String currentGovernorVerification() {
        if (!governorInstalled || !nativeLoaded) return "none";
        try {
            return verificationTextForNativeState(nativeGetGovernorVerificationState());
        } catch (Throwable t) {
            MLog.w("lhdc governor verification state read failed", t);
            return "unknown";
        }
    }

    static String verificationTextForNativeState(int state) {
        switch (state) {
            case 0: return "none";
            case 1: return "pending";
            case 2: return "getter_confirmed";
            case 3: return "getter_unavailable";
            case 4: return "getter_read_failed";
            case 5: return "getter_mismatch";
            case 6: return "setter_only";
            default: return "unknown";
        }
    }

    static boolean isGovernorStreaming() {
        if (!governorInstalled || !nativeLoaded) return false;
        try {
            return nativeIsGovernorStreaming();
        } catch (Throwable t) {
            MLog.w("lhdc governor streaming state read failed", t);
            return false;
        }
    }

    static long currentGovernorSessionEpoch() {
        if (!governorInstalled || !nativeLoaded) return 0L;
        try {
            return nativeGetGovernorSessionEpoch();
        } catch (Throwable t) {
            MLog.w("lhdc governor session epoch read failed", t);
            return 0L;
        }
    }

    static void reportQueueLength(int length) {
        if (length < 0 || !shouldSampleQueue()) return;
        try {
            nativeReportQueueLength(length);
        } catch (Throwable t) {
            MLog.w("lhdc governor queue sample failed", t);
        }
    }

    private static final int DYN_OBSERVE_MAX_PROBES = 5;
    private static int dynObserveProbeCount;
    private static long dynObserveLastEpoch = -1L;
    private static long dynObserveLastProbeMs;

    /**
     * Observe-only dynamic adapter probe, retained from the DYN-A/B experiments for future
     * ROM-family research. Debug builds only ({@code BuildConfig.LHDC_DYN_OBSERVE}) and
     * intended to run while a diagnostic recording session is active: it scans the writable
     * owner of {@code lhdcv5BT_adjust_bitrate} inside libbluetooth_jni and reports the slot
     * structure relationship to the encode/free slots. Never writes a pointer; bounded to a
     * few probes per Bluetooth process with per-epoch dedup.
     */
    static synchronized void probeAdjustOwnerIfEnabled() {
        if (!BuildConfig.LHDC_DYN_OBSERVE) return;
        if (!governorInstalled || !nativeLoaded) return;
        long epoch = currentGovernorSessionEpoch();
        long now = System.currentTimeMillis();
        if (epoch == dynObserveLastEpoch && now - dynObserveLastProbeMs < 30_000L) return;
        if (dynObserveProbeCount >= DYN_OBSERVE_MAX_PROBES) return;
        dynObserveProbeCount++;
        dynObserveLastEpoch = epoch;
        dynObserveLastProbeMs = now;
        int result;
        try {
            result = nativeProbeAdjustOwnerObserve();
        } catch (Throwable t) {
            MLog.w("lhdc dyn observe probe failed", t);
            return;
        }
        long bits;
        long slot;
        long encodeSlot;
        try {
            bits = nativeGetAdjustObserveBits();
            slot = nativeGetAdjustObserveSlotAddress();
            encodeSlot = nativeGetEncodeObserveSlotAddress();
        } catch (Throwable t) {
            MLog.w("lhdc dyn observe probe state read failed", t);
            return;
        }
        int adjustCandidates = (int) (bits & 0xffffL);
        int encodeCandidates = (int) ((bits >>> 16) & 0xffffL);
        int freeCandidates = (int) ((bits >>> 32) & 0xffffL);
        int ownerSegment = (int) ((bits >>> 48) & 0xffL);
        boolean sameRangeAsEncode = ((bits >>> 56) & 1L) != 0L;
        MLog.event("lhdc.dyn.observe",
                "mode", "observe_only",
                "status", describeObserveProbeResult(result),
                "result", result,
                "adjustCandidates", adjustCandidates,
                "encodeCandidates", encodeCandidates,
                "freeCandidates", freeCandidates,
                "ownerSlot", "0x" + Long.toHexString(slot),
                "encodeSlot", "0x" + Long.toHexString(encodeSlot),
                "ownerSegment", ownerSegment,
                "sameRangeAsEncode", sameRangeAsEncode,
                "streamEpoch", epoch,
                "probeCount", dynObserveProbeCount,
                "pid", android.os.Process.myPid());
    }

    static String describeObserveProbeResult(int result) {
        switch (result) {
            case 1: return "unique_owner";
            case -2: return "not_enabled_or_arch";
            case -3: return "encoder_not_loaded";
            case -4: return "adjust_symbol_missing";
            case -5: return "zero_owners";
            case -6: return "ambiguous_multiple_owners";
            default: return "unknown";
        }
    }

    static GovernorEvent consumeGovernorEvent() {
        if (!governorInstalled || !nativeLoaded) return null;
        try {
            long packed = nativeConsumeGovernorEvent();
            if (packed == 0L) return null;
            int event = (int) (packed & 0xffL);
            int fromKbps = bitrateForNativeRate((int) ((packed >>> 8) & 0xffL));
            int toKbps = bitrateForNativeRate((int) ((packed >>> 16) & 0xffL));
            long detailMs = packed >>> 24;
            int reasonId = nativeConsumeGovernorEventReasonId();
            int requestId = nativeConsumeGovernorEventRequestId();
            if (event == LhdcLinkHealthController.EVENT_PEER_CEILING_DETECTED) {
                if (toKbps == 0) return null;
                return new GovernorEvent(event, 0, toKbps, detailMs, reasonId, requestId);
            }
            if (event == LhdcLinkHealthController.EVENT_TRANSITION_APPLIED) {
                if (toKbps == 0) return null;
                return new GovernorEvent(event, fromKbps, toKbps, detailMs, reasonId, requestId);
            }
            if (fromKbps == 0 || toKbps == 0) return null;
            return new GovernorEvent(event, fromKbps, toKbps, detailMs, reasonId, requestId);
        } catch (Throwable t) {
            MLog.w("lhdc governor event read failed", t);
            return null;
        }
    }

    private static int bitrateForNativeRate(int rate) {
        if (rate == 5) return 400;
        if (rate == 6) return 500;
        if (rate == 7) return 900;
        if (rate == 8) return 1000;
        return 0;
    }

    static final class GovernorEvent {
        final int type;
        final int fromKbps;
        final int toKbps;
        final long detailMs;
        final int reasonId;
        final int requestId;

        GovernorEvent(int type, int fromKbps, int toKbps, long detailMs, int reasonId,
                int requestId) {
            this.type = type;
            this.fromKbps = fromKbps;
            this.toKbps = toKbps;
            this.detailMs = detailMs;
            this.reasonId = reasonId;
            this.requestId = requestId;
        }
    }

    private static PatchResult applyUnchecked() throws Exception {
        List<MapRange> ranges = readLibraryMaps();
        if (ranges.isEmpty()) {
            return PatchResult.pending("library_not_mapped");
        }

        Match original = null;
        int originalCount = 0;
        int patchedCount = 0;
        String patchedSpec = "";

        for (MapRange range : ranges) {
            byte[] bytes = readRange(range);
            if (bytes == null) continue;
            for (PatternSpec spec : PATTERN_SPECS) {
                int rangeOriginalCount = countMatches(bytes, spec.original);
                int rangePatchedCount = countMatches(bytes, spec.patched);
                originalCount += rangeOriginalCount;
                patchedCount += rangePatchedCount;
                if (rangePatchedCount > 0 && patchedSpec.isEmpty()) {
                    patchedSpec = spec.name;
                }
                if (original == null) {
                    int index = indexOf(bytes, spec.original);
                    if (index >= 0) {
                        original = new Match(range.start + index, range, spec);
                    }
                }
            }
        }

        if (patchedCount == 1 && originalCount == 0) {
            return PatchResult.alreadyPatched(patchedCount, originalCount, patchedSpec);
        }
        if (originalCount != 1 || original == null) {
            SemanticScan semantic = scanSemanticGuard(ranges);
            if (semantic.patchedCount == 1 && semantic.originalCount == 0) {
                return PatchResult.alreadyPatched(
                        semantic.patchedCount,
                        semantic.originalCount,
                        "semantic_guard_v1");
            }
            if (semantic.originalCount != 1 || semantic.original == null
                    || semantic.patchedCount != 0) {
                return PatchResult.unsupported(
                        patchedCount + semantic.patchedCount,
                        originalCount + semantic.originalCount);
            }
            original = semantic.original;
            originalCount = semantic.originalCount;
            patchedCount = semantic.patchedCount;
        }

        long patchAddress = original.address + original.spec.patchDelta;
        MapRange patchRange = findRange(ranges, patchAddress);
        if (patchRange == null) {
            return PatchResult.failed("patch_address_outside_mapping");
        }
        if (!patchRange.executable) {
            return PatchResult.failed("patch_mapping_not_executable");
        }
        if ((patchAddress & 3L) != 0L
                || original.spec.patchBytes.length != Integer.BYTES
                || original.spec.patchDelta < 0
                || original.spec.patchDelta + Integer.BYTES > original.spec.original.length) {
            return PatchResult.failed("patch_instruction_not_aligned_arm64");
        }
        if (!ensureNativeLoaded()) {
            return PatchResult.failed("native_helper_unavailable:" + nativeLoadError);
        }

        int expectedInstruction = readIntLe(original.spec.original, original.spec.patchDelta);
        int replacementInstruction = readIntLe(original.spec.patchBytes, 0);
        int nativeResult;
        try {
            nativeResult = nativePatchInstruction(
                    patchAddress,
                    expectedInstruction,
                    replacementInstruction,
                    patchRange.protectionFlags());
        } catch (Throwable t) {
            return PatchResult.failed("native_patch_call_failed:" + describeThrowable(t));
        }
        if (nativeResult != NATIVE_PATCH_OK
                && nativeResult != NATIVE_PATCH_ALREADY_APPLIED) {
            return PatchResult.failed(describeNativePatchResult(nativeResult));
        }

        byte[] verify = readMemory(original.address, original.spec.patched.length);
        if (!equalsBytes(verify, original.spec.patched)) {
            return PatchResult.failed("verify_failed");
        }
        if (nativeResult == NATIVE_PATCH_ALREADY_APPLIED) {
            return PatchResult.alreadyPatched(
                    Math.max(1, patchedCount), 0, original.spec.name);
        }
        return PatchResult.patched(patchAddress, patchedCount, originalCount, original.spec.name);
    }

    private static PatchResult applyQualitySwitchGuardUnchecked() throws Exception {
        List<MapRange> ranges = readLibraryMaps();
        if (ranges.isEmpty()) {
            return PatchResult.pending("library_not_mapped");
        }
        for (CodeBlockSpec spec : LHDC_V5_QUALITY_SWITCH_SPECS) {
            PatchResult result = applyQualitySwitchSpecUnchecked(ranges, spec);
            if (result != null) return result;
        }
        return PatchResult.unsupported(0, 0);
    }

    /**
     * Applies one version-line spec when its whole-block signature uniquely matches the mapped
     * library; returns null when this build is not the target (another spec may match).
     */
    private static PatchResult applyQualitySwitchSpecUnchecked(
            List<MapRange> ranges, CodeBlockSpec spec) throws Exception {
        long originalAddress = 0L;
        int originalCount = 0;
        int patchedCount = 0;
        for (MapRange range : ranges) {
            if (!range.executable) continue;
            byte[] bytes = readRange(range);
            if (bytes == null) continue;
            int rangeOriginalCount = countMatches(bytes, spec.original);
            originalCount += rangeOriginalCount;
            patchedCount += countMatches(bytes, spec.patched);
            if (originalAddress == 0L && rangeOriginalCount > 0) {
                int index = indexOf(bytes, spec.original);
                if (index >= 0) originalAddress = range.start + index;
            }
        }

        if (patchedCount == 1 && originalCount == 0) {
            String applied = appliedQualitySwitchSpecName;
            return PatchResult.alreadyPatched(patchedCount, originalCount,
                    applied != null ? applied : spec.name);
        }
        if (originalCount != 1 || patchedCount != 0 || originalAddress == 0L) {
            return null;
        }

        MapRange patchRange = findRange(ranges, originalAddress);
        if (patchRange == null
                || originalAddress + spec.original.length > patchRange.end) {
            return PatchResult.failed("patch_block_outside_mapping");
        }
        if (!patchRange.executable) {
            return PatchResult.failed("patch_mapping_not_executable");
        }
        if ((originalAddress & 3L) != 0L
                || spec.original.length == 0
                || (spec.original.length & 3) != 0
                || spec.original.length != spec.patched.length) {
            return PatchResult.failed("patch_block_not_aligned_arm64");
        }
        if (!ensureNativeLoaded()) {
            return PatchResult.failed("native_helper_unavailable:" + nativeLoadError);
        }

        int nativeResult;
        try {
            nativeResult = nativePatchCodeBlock(
                    originalAddress,
                    spec.original,
                    spec.patched,
                    spec.safeGateInstruction,
                    patchRange.protectionFlags());
        } catch (Throwable t) {
            return PatchResult.failed("native_patch_call_failed:" + describeThrowable(t));
        }
        if (nativeResult != NATIVE_PATCH_OK
                && nativeResult != NATIVE_PATCH_ALREADY_APPLIED) {
            return PatchResult.failed(describeNativePatchResult(nativeResult));
        }

        byte[] verify = readMemory(originalAddress, spec.patched.length);
        if (!equalsBytes(verify, spec.patched)) {
            return PatchResult.failed("verify_failed");
        }
        if (nativeResult == NATIVE_PATCH_ALREADY_APPLIED) {
            if (appliedQualitySwitchSpecName == null) {
                appliedQualitySwitchSpecName = spec.name;
            }
            return PatchResult.alreadyPatched(Math.max(1, patchedCount), 0,
                    appliedQualitySwitchSpecName);
        }
        appliedQualitySwitchSpecName = spec.name;
        return PatchResult.patched(originalAddress, patchedCount, originalCount, spec.name);
    }

    /**
     * Finds the LHDC fixed-bitrate guard by ARM64 instruction semantics instead of compiler-
     * generated bytes. OPlus rebuilds this function on nearly every OTA, which changes register
     * allocation, source-line constants and branch distances even when the logic is unchanged.
     *
     * <p>The stable control-flow shape is: CBNZ and B.NE share a forward target, the latter is
     * guarded by {@code cmp wN, #0x13}, followed by {@code sub xN, xM, #7},
     * {@code cmp xN, #2}, and {@code b.hs same_target}. The forced path then selects quality mode
     * 4. OPlus sometimes interleaves string-pointer setup ({@code adrp}/{@code add}) between the
     * guard branch and the quality-mode load, so the mode-4 store is searched within the first
     * eight instructions after the branch. We only accept a unique match across executable
     * mappings.</p>
     */
    private static SemanticScan scanSemanticGuard(List<MapRange> ranges) {
        SemanticScan out = new SemanticScan();
        for (MapRange range : ranges) {
            if (!range.executable) continue;
            byte[] bytes = readRange(range);
            if (bytes == null) continue;
            for (int offset = 8; offset <= bytes.length - 20; offset += 4) {
                int branch = readIntLe(bytes, offset);
                boolean originalBranch = isConditionalBranch(branch, 2);
                boolean patchedBranch = isUnconditionalBranch(branch);
                if (!originalBranch && !patchedBranch) continue;

                int cmp = readIntLe(bytes, offset - 4);
                int sub = readIntLe(bytes, offset - 8);
                if (!isCmpXImmediate(cmp, 2) || !isSubXImmediate(sub, 7)) continue;
                if (registerN(cmp) != registerD(sub)) continue;

                long address = range.start + offset;
                long target = branchTarget(address, branch, originalBranch);
                if (target <= address || target - address > 0x400L) continue;
                if (!hasCmp19AndBranchTo(bytes, range.start, offset, target)) continue;
                if (!hasCbnzTo(bytes, range.start, offset, target)) continue;
                if (!hasMovWImmediate(bytes, offset + 4, 8, 4)) continue;

                if (originalBranch) {
                    int replacement = encodeUnconditionalBranch(address, target);
                    if (replacement == 0) continue;
                    PatternSpec spec = new PatternSpec(
                            "semantic_guard_v1",
                            intLe(branch),
                            intLe(replacement),
                            0,
                            intLe(replacement));
                    out.originalCount++;
                    if (out.original == null) {
                        out.original = new Match(address, range, spec);
                    }
                } else {
                    out.patchedCount++;
                }
            }
        }
        return out;
    }

    private static boolean hasCmp19AndBranchTo(
            byte[] bytes,
            long rangeStart,
            int branchOffset,
            long target) {
        int first = Math.max(4, branchOffset - 10 * 4);
        for (int offset = branchOffset - 4; offset >= first; offset -= 4) {
            int branch = readIntLe(bytes, offset);
            if (!isConditionalBranch(branch, 1)) continue;
            if (branchTarget(rangeStart + offset, branch, true) != target) continue;
            if (isCmpWImmediate(readIntLe(bytes, offset - 4), 0x13)) return true;
        }
        return false;
    }

    private static boolean hasCbnzTo(
            byte[] bytes,
            long rangeStart,
            int branchOffset,
            long target) {
        int first = Math.max(0, branchOffset - 14 * 4);
        for (int offset = branchOffset - 4; offset >= first; offset -= 4) {
            int instruction = readIntLe(bytes, offset);
            if (!isCbnz(instruction)) continue;
            if (branchTarget19(rangeStart + offset, instruction) == target) return true;
        }
        return false;
    }

    private static boolean hasMovWImmediate(
            byte[] bytes,
            int start,
            int instructionCount,
            int immediate) {
        int end = Math.min(bytes.length - 4, start + instructionCount * 4);
        for (int offset = start; offset <= end; offset += 4) {
            int instruction = readIntLe(bytes, offset);
            if ((instruction & 0xffe00000) == 0x52800000
                    && ((instruction >>> 5) & 0xffff) == immediate) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCmpXImmediate(int instruction, int immediate) {
        return (instruction & 0xffc0001f) == 0xf100001f
                && ((instruction >>> 10) & 0xfff) == immediate;
    }

    private static boolean isCmpWImmediate(int instruction, int immediate) {
        return (instruction & 0xffc0001f) == 0x7100001f
                && ((instruction >>> 10) & 0xfff) == immediate;
    }

    private static boolean isSubXImmediate(int instruction, int immediate) {
        return (instruction & 0xffc00000) == 0xd1000000
                && ((instruction >>> 10) & 0xfff) == immediate;
    }

    private static boolean isConditionalBranch(int instruction, int condition) {
        return (instruction & 0xff000010) == 0x54000000
                && (instruction & 0xf) == condition;
    }

    private static boolean isUnconditionalBranch(int instruction) {
        return (instruction & 0xfc000000) == 0x14000000;
    }

    private static boolean isCbnz(int instruction) {
        return (instruction & 0x7f000000) == 0x35000000;
    }

    private static int registerN(int instruction) {
        return (instruction >>> 5) & 0x1f;
    }

    private static int registerD(int instruction) {
        return instruction & 0x1f;
    }

    private static long branchTarget(long address, int instruction, boolean conditional) {
        if (conditional) return branchTarget19(address, instruction);
        int immediate = instruction & 0x03ffffff;
        if ((immediate & 0x02000000) != 0) immediate |= 0xfc000000;
        return address + ((long) immediate * 4L);
    }

    private static long branchTarget19(long address, int instruction) {
        int immediate = (instruction >>> 5) & 0x7ffff;
        if ((immediate & 0x40000) != 0) immediate |= 0xfff80000;
        return address + ((long) immediate * 4L);
    }

    private static int encodeUnconditionalBranch(long address, long target) {
        long delta = target - address;
        if ((delta & 3L) != 0) return 0;
        long immediate = delta / 4L;
        if (immediate < -(1L << 25) || immediate >= (1L << 25)) return 0;
        return 0x14000000 | ((int) immediate & 0x03ffffff);
    }

    private static int readIntLe(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16)
                | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static byte[] intLe(int value) {
        return new byte[]{
                (byte) value,
                (byte) (value >>> 8),
                (byte) (value >>> 16),
                (byte) (value >>> 24)};
    }

    private static List<MapRange> readLibraryMaps() throws IOException {
        List<MapRange> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/maps"))) {
            String line;
            while ((line = br.readLine()) != null) {
                MapRange range = MapRange.parse(line);
                if (range == null) continue;
                if (!range.readable) continue;
                if (range.size() <= 0 || range.size() > MAX_RANGE_BYTES) continue;
                if (range.path == null || !range.path.endsWith(LIB_NAME)) continue;
                out.add(range);
            }
        }
        return out;
    }

    private static byte[] readRange(MapRange range) {
        try {
            return readMemory(range.start, (int) range.size());
        } catch (Throwable t) {
            MLog.w("lhdc memory patch read range failed: " + range.describe() + " "
                    + t.getClass().getSimpleName() + ":" + t.getMessage());
            return null;
        }
    }

    private static byte[] readMemory(long address, int length) throws Exception {
        byte[] out = new byte[length];
        Method peek = cachedPeekByteArray;
        if (peek == null) {
            peek = Class.forName("libcore.io.Memory").getDeclaredMethod(
                    "peekByteArray", long.class, byte[].class, int.class, int.class);
            peek.setAccessible(true);
            cachedPeekByteArray = peek;
        }
        try {
            peek.invoke(null, address, out, 0, length);
            return out;
        } catch (Throwable t) {
            throw new Exception("peekByteArray failed: " + describeThrowable(unwrapReflection(t)),
                    unwrapReflection(t));
        }
    }

    private static int countMatches(byte[] haystack, byte[] needle) {
        int count = 0;
        int from = 0;
        while (from <= haystack.length - needle.length) {
            int index = indexOf(haystack, needle, from);
            if (index < 0) break;
            count++;
            from = index + needle.length;
        }
        return count;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        return indexOf(haystack, needle, 0);
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) return from;
        for (int i = Math.max(0, from); i <= haystack.length - needle.length; i++) {
            boolean ok = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    ok = false;
                    break;
                }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static boolean equalsBytes(byte[] actual, byte[] expected) {
        if (actual == null || actual.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (actual[i] != expected[i]) return false;
        }
        return true;
    }

    private static MapRange findRange(List<MapRange> ranges, long address) {
        for (MapRange range : ranges) {
            if (address >= range.start && address < range.end) return range;
        }
        return null;
    }

    private static synchronized boolean ensureNativeLoaded() {
        if (nativeLoaded) return true;
        if (nativeLoadAttempted) return false;
        nativeLoadAttempted = true;
        String path = nativeLibraryPath;
        Throwable pathError = null;
        try {
            if (path != null && !path.isEmpty()) {
                System.load(path);
                nativeLoaded = true;
                nativeLoadError = null;
                MLog.event("lhdc.memory_patch.native_loaded", "path", path);
                return true;
            }
        } catch (Throwable t) {
            pathError = t;
        }

        try {
            System.loadLibrary("melody_lhdc_patch");
            nativeLoaded = true;
            nativeLoadError = null;
            MLog.event("lhdc.memory_patch.native_loaded",
                    "path", path != null && !path.isEmpty() ? path + "|loadLibrary" : "loadLibrary");
            return true;
        } catch (Throwable t) {
            nativeLoaded = false;
            nativeLoadError = pathError == null
                    ? describeThrowable(t)
                    : "path=" + describeThrowable(pathError) + " loadLibrary=" + describeThrowable(t);
            return false;
        }
    }

    private static native int nativePatchInstruction(
            long address,
            int expectedInstruction,
            int replacementInstruction,
            int originalProtection);

    private static native int nativePatchCodeBlock(
            long address,
            byte[] expected,
            byte[] replacement,
            int safeGateInstruction,
            int originalProtection);

    private static native int nativeInstallGovernor();

    private static native void nativeSetGovernorPolicy(int policy);

    private static native void nativeSetGovernorTargetKbps(int targetKbps, int requestId);

    private static native long nativeConsumeGovernorEvent();
    private static native int nativeConsumeGovernorEventReasonId();
    private static native int nativeConsumeGovernorEventRequestId();

    private static native int nativeGetGovernorBitrateKbps();

    private static native int nativeGetGovernorRequestedBitrateKbps();

    private static native int nativeGetGovernorVerificationState();

    private static native boolean nativeIsGovernorStreaming();

    private static native long nativeGetGovernorSessionEpoch();

    private static native int nativeProbeAdjustOwnerObserve();

    private static native long nativeGetAdjustObserveBits();

    private static native long nativeGetAdjustObserveSlotAddress();

    private static native long nativeGetEncodeObserveSlotAddress();

    private static native void nativeReportQueueLength(int length);

    private static native void nativeReportChoppy(int level);

    static String describeNativePatchResult(int result) {
        if (result == NATIVE_PATCH_OK) return "native_patch_ok";
        if (result == NATIVE_PATCH_ALREADY_APPLIED) return "native_patch_already_applied";
        if (result == -1001) return "native_patch_invalid_argument";
        if (result == -1002) return "native_patch_unsupported_architecture";
        if (result == -1003) return "native_patch_instruction_changed";
        if (result <= -2001 && result >= -2999) {
            return "native_patch_make_writable_failed:errno=" + (-2000 - result);
        }
        if (result <= -3001 && result >= -3999) {
            return "native_patch_restore_failed_before_write:errno=" + (-3000 - result);
        }
        if (result == -4001) return "native_patch_verify_failed_rolled_back";
        if (result <= -5001 && result >= -5999) {
            return "native_patch_restore_failed_rolled_back:errno=" + (-5000 - result);
        }
        if (result <= -6001 && result >= -6999) {
            return "native_patch_restore_failed_permissions_dirty:errno=" + (-6000 - result);
        }
        if (result <= -7000 && result >= -7999) {
            return "native_patch_rollback_verify_failed:errno=" + Math.max(0, -7000 - result);
        }
        return "native_patch_unknown_result:" + result;
    }

    private static Throwable unwrapReflection(Throwable t) {
        if (t instanceof InvocationTargetException
                && ((InvocationTargetException) t).getTargetException() != null) {
            return ((InvocationTargetException) t).getTargetException();
        }
        return t;
    }

    private static String describeThrowable(Throwable t) {
        if (t == null) return "none";
        return t.getClass().getSimpleName() + ":" + t.getMessage();
    }

    private static byte[] hex(String value) {
        int len = value.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) Integer.parseInt(value.substring(i, i + 2), 16);
        }
        return out;
    }

    static final class PatchResult {
        final String status;
        final String reason;
        final long address;
        final int patchedCount;
        final int originalCount;
        final boolean terminal;
        final boolean success;

        private PatchResult(
                String status,
                String reason,
                long address,
                int patchedCount,
                int originalCount,
                boolean terminal,
                boolean success) {
            this.status = status;
            this.reason = reason;
            this.address = address;
            this.patchedCount = patchedCount;
            this.originalCount = originalCount;
            this.terminal = terminal;
            this.success = success;
        }

        static PatchResult patched(
                long address,
                int patchedCount,
                int originalCount,
                String pattern) {
            return new PatchResult("patched", patternReason(pattern), address,
                    patchedCount, originalCount, true, true);
        }

        static PatchResult alreadyPatched(
                int patchedCount,
                int originalCount,
                String pattern) {
            return new PatchResult("already_patched", patternReason(pattern), 0L,
                    patchedCount, originalCount, true, true);
        }

        static PatchResult unsupported(int patchedCount, int originalCount) {
            return new PatchResult("unsupported", "", 0L, patchedCount, originalCount,
                    true, false);
        }

        static PatchResult pending(String reason) {
            return new PatchResult("pending", reason, 0L, 0, 0,
                    false, false);
        }

        static PatchResult failed(String reason) {
            return new PatchResult("failed", reason, 0L, 0, 0,
                    true, false);
        }

        String addressHex() {
            return address == 0L ? "0x0" : "0x" + Long.toHexString(address);
        }

        private static String patternReason(String pattern) {
            return pattern == null || pattern.isEmpty() ? "" : "pattern=" + pattern;
        }
    }

    private static final class Match {
        final long address;
        final MapRange range;
        final PatternSpec spec;

        Match(long address, MapRange range, PatternSpec spec) {
            this.address = address;
            this.range = range;
            this.spec = spec;
        }
    }

    private static final class SemanticScan {
        Match original;
        int originalCount;
        int patchedCount;
    }

    static final class PatternSpec {
        final String name;
        final byte[] original;
        final byte[] patched;
        final int patchDelta;
        final byte[] patchBytes;

        PatternSpec(
                String name,
                byte[] original,
                byte[] patched,
                int patchDelta,
                byte[] patchBytes) {
            this.name = name;
            this.original = original;
            this.patched = patched;
            this.patchDelta = patchDelta;
            this.patchBytes = patchBytes;
        }
    }

    static final class CodeBlockSpec {
        final String name;
        final byte[] original;
        final byte[] patched;
        final int safeGateInstruction;

        CodeBlockSpec(
                String name,
                byte[] original,
                byte[] patched,
                int safeGateInstruction) {
            this.name = name;
            this.original = original;
            this.patched = patched;
            this.safeGateInstruction = safeGateInstruction;
        }
    }

    /** Visible for tests. */
    static CodeBlockSpec qualitySwitchSpecForTest() {
        return LHDC_V5_QUALITY_SWITCH_SPECS[0];
    }

    static CodeBlockSpec[] qualitySwitchSpecsForTest() {
        return LHDC_V5_QUALITY_SWITCH_SPECS;
    }

    static CodeBlockSpec qualitySwitchSpecForTestName(String name) {
        for (CodeBlockSpec spec : LHDC_V5_QUALITY_SWITCH_SPECS) {
            if (spec.name.equals(name)) return spec;
        }
        throw new IllegalArgumentException("unknown quality-switch spec " + name);
    }

    private static final class MapRange {
        final long start;
        final long end;
        final boolean readable;
        final boolean writable;
        final boolean executable;
        final String perms;
        final String path;

        private MapRange(
                long start,
                long end,
                boolean readable,
                boolean writable,
                boolean executable,
                String perms,
                String path) {
            this.start = start;
            this.end = end;
            this.readable = readable;
            this.writable = writable;
            this.executable = executable;
            this.perms = perms;
            this.path = path;
        }

        static MapRange parse(String line) {
            if (line == null) return null;
            String[] parts = line.trim().split("\\s+", 6);
            if (parts.length < 5) return null;
            String[] bounds = parts[0].split("-", 2);
            if (bounds.length != 2) return null;
            try {
                long start = Long.parseUnsignedLong(bounds[0], 16);
                long end = Long.parseUnsignedLong(bounds[1], 16);
                String perms = parts[1];
                String path = parts.length >= 6 ? parts[5] : "";
                return new MapRange(start, end,
                        perms.length() > 0 && perms.charAt(0) == 'r',
                        perms.length() > 1 && perms.charAt(1) == 'w',
                        perms.length() > 2 && perms.charAt(2) == 'x',
                        perms,
                        path);
            } catch (Throwable ignored) {
                return null;
            }
        }

        long size() {
            return end - start;
        }

        int protectionFlags() {
            int flags = 0;
            if (readable) flags |= OsConstants.PROT_READ;
            if (writable) flags |= OsConstants.PROT_WRITE;
            if (executable) flags |= OsConstants.PROT_EXEC;
            return flags;
        }

        String describe() {
            return String.format(Locale.ROOT, "0x%x-0x%x/%s/%s", start, end, perms, path);
        }
    }
}
