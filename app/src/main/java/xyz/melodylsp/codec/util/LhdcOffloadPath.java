package xyz.melodylsp.codec.util;

import android.content.Context;
import android.media.AudioManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Decides whether LHDC V5 is encoded by the DSP (A2DP hardware offload) instead of the software
 * encoder.
 *
 * <p>Link governance reads the encoder bitrate and rewrites the target rate, which only exists on
 * the software path. On an offload build the encoder lives inside the controller/audio DSP, so
 * every governance feature has to stay off while the unlock patch, the quality/sample-rate
 * selection and the per-headset memory keep working.</p>
 *
 * <p>The primary signal is the audio HAL's offload codec list — the same list {@code
 * A2dpCodecConfig} hands to the native stack via {@code
 * AudioManager.getHwOffloadFormatsSupportedForA2dp()}. When an {@code A2dpService} instance is
 * available the stack's own {@code mA2dpOffloadEnabled} flag refines it. A failed probe is
 * reported as unknown instead of "offload", so a ROM that hides the API keeps the previous
 * behaviour instead of silently losing governance.</p>
 */
public final class LhdcOffloadPath {

    /** BluetoothCodecConfig.CODEC_TYPE_LHDC_V5 */
    private static final int LHDC_V5 = 19;

    private static final String[] HAL_METHODS = {
            "getHwOffloadFormatsSupportedForA2dp"
    };
    private static final String[] CODEC_TYPE_METHODS = {"getCodecType", "getCodec"};

    private static volatile Boolean halListsLhdc;
    private static volatile Boolean offloadEnabled;
    private static volatile String probeError = "";
    private static volatile String lastLoggedState = "";

    private LhdcOffloadPath() {
    }

    /** Probes the audio HAL only; used by the module app process before any Bluetooth hook runs. */
    public static synchronized void refresh(Context context) {
        refresh(context, null);
    }

    /**
     * Probes the audio HAL and (when available) the Bluetooth stack's offload flag.
     *
     * @param context any context; used for the audio HAL query
     * @param a2dpService the live {@code com.android.bluetooth.a2dp.A2dpService}, or {@code null}
     */
    public static synchronized void refresh(Context context, Object a2dpService) {
        if (context != null) probeHal(context);
        if (a2dpService != null) probeStackFlag(a2dpService);
        logIfChanged();
    }

    /** True when LHDC V5 is known to run on the hardware offload path. */
    public static boolean isOffloaded() {
        if (!Boolean.TRUE.equals(halListsLhdc)) return false;
        return !Boolean.FALSE.equals(offloadEnabled);
    }

    /** True once the HAL answered; false means the verdict is unknown and must not block anything. */
    public static boolean isKnown() {
        return halListsLhdc != null;
    }

    /** True when the HAL advertises LHDC V5 for offload (independent of the stack flag). */
    public static boolean isHalAdvertised() {
        return Boolean.TRUE.equals(halListsLhdc);
    }

    /** True when the Bluetooth stack's own offload switch is on; null when never resolved. */
    public static Boolean stackOffloadEnabled() {
        return offloadEnabled;
    }

    public static String probeError() {
        return probeError;
    }

    /** Compact one-line description for logs and the diagnostics payload. */
    public static String describe() {
        return "known=" + isKnown()
                + " halListsLhdc=" + halListsLhdc
                + " stackOffloadEnabled=" + offloadEnabled
                + " offloaded=" + isOffloaded()
                + (probeError.isEmpty() ? "" : " error=" + probeError);
    }

    /** Test seam: forget every cached probe result. Public only so other packages can reset. */
    public static synchronized void resetForTest() {
        halListsLhdc = null;
        offloadEnabled = null;
        probeError = "";
        lastLoggedState = "";
    }

    /** Test seam: inject a probe result without touching the audio HAL. */
    public static synchronized void setForTest(Boolean halAdvertised, Boolean stackEnabled) {
        halListsLhdc = halAdvertised;
        offloadEnabled = stackEnabled;
        probeError = "";
        lastLoggedState = "";
    }

    private static void probeHal(Context context) {
        try {
            AudioManager audioManager =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                probeError = "audio_manager_unavailable";
                return;
            }
            Object result = null;
            Throwable failure = null;
            for (String name : HAL_METHODS) {
                try {
                    Method method = AudioManager.class.getMethod(name);
                    result = method.invoke(audioManager);
                    failure = null;
                    break;
                } catch (Throwable t) {
                    failure = t;
                }
            }
            if (failure != null) throw failure;
            Boolean hasLhdc = containsLhdcV5(result);
            if (hasLhdc == null) {
                probeError = "unexpected_hal_result:"
                        + (result == null ? "null" : result.getClass().getSimpleName());
                return;
            }
            halListsLhdc = hasLhdc;
            probeError = "";
        } catch (Throwable t) {
            probeError = t.getClass().getSimpleName() + ":"
                    + String.valueOf(t.getMessage()).replace(' ', '_');
        }
    }

    private static void probeStackFlag(Object a2dpService) {
        try {
            Field field = findField(a2dpService.getClass(), "mA2dpOffloadEnabled");
            if (field != null) {
                field.setAccessible(true);
                offloadEnabled = field.getBoolean(a2dpService);
                return;
            }
            Method method = findMethod(a2dpService.getClass(), "isA2dpOffloadEnabled");
            if (method != null) {
                Object value = method.invoke(a2dpService);
                if (value instanceof Boolean) offloadEnabled = (Boolean) value;
            }
        } catch (Throwable t) {
            probeError = "stack_flag:" + t.getClass().getSimpleName();
        }
    }

    private static void logIfChanged() {
        String state = describe();
        if (state.equals(lastLoggedState)) return;
        lastLoggedState = state;
        MLog.event("lhdc.offload.path",
                "lhdcOffloaded", isOffloaded(),
                "halListsLhdc", halListsLhdc == null ? "unknown" : halListsLhdc,
                "stackOffloadEnabled", offloadEnabled == null ? "unknown" : offloadEnabled,
                "probeError", probeError.isEmpty() ? "none" : probeError);
    }

    private static Boolean containsLhdcV5(Object value) {
        if (!(value instanceof List)) return null;
        int parsed = 0;
        for (Object item : (List<?>) value) {
            if (item == null) continue;
            Integer codecType = codecTypeOf(item);
            if (codecType == null) continue;
            parsed++;
            if (codecType == LHDC_V5) return Boolean.TRUE;
        }
        // A non-empty list whose entries expose no codec type is not evidence of absence.
        if (parsed == 0 && !((List<?>) value).isEmpty()) return null;
        return Boolean.FALSE;
    }

    private static Integer codecTypeOf(Object item) {
        for (String name : CODEC_TYPE_METHODS) {
            try {
                Method method = item.getClass().getMethod(name);
                Object value = method.invoke(item);
                if (value instanceof Number) return ((Number) value).intValue();
            } catch (Throwable ignored) {
                // Try the next accessor.
            }
        }
        return null;
    }

    private static Field findField(Class<?> startClass, String name) {
        Class<?> cls = startClass;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> startClass, String name) {
        Class<?> cls = startClass;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
