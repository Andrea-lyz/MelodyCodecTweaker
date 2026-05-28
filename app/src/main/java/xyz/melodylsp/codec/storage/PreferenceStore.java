package xyz.melodylsp.codec.storage;

import android.content.Context;
import android.content.SharedPreferences;

import xyz.melodylsp.codec.util.MLog;

/**
 * Per-host SharedPreferences for the per-MAC {@code Remember_Toggle} and value snapshot.
 *
 * <p>The module-wide master switch (default {@code true}, Requirement 12.2) is stored
 * separately in {@code module_prefs.xml} and exposed through libxposed
 * {@code XposedModule.getRemotePreferences("module_prefs")} so the module's own settings
 * Activity ({@code MasterSwitchActivity}) and the in-host hook callbacks see the same
 * value.</p>
 *
 * <p>Setting {@code Remember_Toggle=false} immediately removes the snapshot keys so the next
 * reconnect goes through the Android system default (Requirement 12.5 / 12.7).</p>
 */
public final class PreferenceStore {

    public static final String MELODY_PREFS = "melody_lsp_codec_prefs";

    public static final String KEY_ENABLED = "enabled";

    private static final String KEY_REMEMBER_SUFFIX = "_remember";
    private static final String KEY_SPECIFIC1_SUFFIX = "_specific1";
    private static final String KEY_SAMPLERATE_SUFFIX = "_samplerate";

    private final SharedPreferences melodyPrefs;

    public PreferenceStore(Context hostContext) {
        this.melodyPrefs = hostContext.getSharedPreferences(MELODY_PREFS, Context.MODE_PRIVATE);
    }

    public boolean isRemembered(String mac) {
        if (mac == null) return false;
        return melodyPrefs.getBoolean(mac + KEY_REMEMBER_SUFFIX, false);
    }

    public void setRemembered(String mac, boolean remembered) {
        if (mac == null) return;
        SharedPreferences.Editor editor = melodyPrefs.edit();
        editor.putBoolean(mac + KEY_REMEMBER_SUFFIX, remembered);
        if (!remembered) {
            // Snapshot keys must vanish atomically with the toggle (Property 9).
            editor.remove(mac + KEY_SPECIFIC1_SUFFIX);
            editor.remove(mac + KEY_SAMPLERATE_SUFFIX);
        }
        editor.apply();
        MLog.event("remember.set", "mac", redact(mac), "remembered", remembered);
    }

    public RememberedValue readSnapshot(String mac) {
        if (mac == null || !isRemembered(mac)) return null;
        if (!melodyPrefs.contains(mac + KEY_SPECIFIC1_SUFFIX)
                || !melodyPrefs.contains(mac + KEY_SAMPLERATE_SUFFIX)) {
            return null;
        }
        long specific1 = melodyPrefs.getLong(mac + KEY_SPECIFIC1_SUFFIX, -1L);
        int sampleRate = melodyPrefs.getInt(mac + KEY_SAMPLERATE_SUFFIX, -1);
        return new RememberedValue(specific1, sampleRate);
    }

    public void writeSnapshot(String mac, long codecSpecific1, int sampleRate) {
        if (mac == null) return;
        if (!isRemembered(mac)) {
            MLog.w("writeSnapshot ignored, remember=false mac=" + redact(mac));
            return;
        }
        melodyPrefs.edit()
                .putLong(mac + KEY_SPECIFIC1_SUFFIX, codecSpecific1)
                .putInt(mac + KEY_SAMPLERATE_SUFFIX, sampleRate)
                .apply();
        MLog.event("remember.write",
                "mac", redact(mac), "specific1", codecSpecific1, "rate", sampleRate);
    }

    private static String redact(String mac) {
        if (mac == null || mac.length() < 5) return "??";
        return mac.substring(0, 2) + "**" + mac.substring(mac.length() - 2);
    }

    /** Snapshot returned to ConnectionStateReplayer / UI on a remember=true MAC. */
    public static final class RememberedValue {
        public final long codecSpecific1;
        public final int sampleRate;

        public RememberedValue(long codecSpecific1, int sampleRate) {
            this.codecSpecific1 = codecSpecific1;
            this.sampleRate = sampleRate;
        }
    }
}
