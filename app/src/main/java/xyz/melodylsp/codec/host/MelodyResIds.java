package xyz.melodylsp.codec.host;

import android.content.Context;
import android.content.res.Resources;

import xyz.melodylsp.codec.util.MLog;

/**
 * Best-effort resolver for resource ids and Preference keys defined inside the host
 * {@code com.oplus.melody} APK. Centralizing every string id here keeps version-specific
 * mappings in one place.
 *
 * <p>Resolution failures throw {@link MissingResIdException}; callers must catch and downgrade
 * (Requirement 10.2/10.3). Keys themselves are plain string literals copied from the
 * decompiled XML so they can be used directly with {@code findPreference(String)}.
 */
public final class MelodyResIds {

    private static final String HOST_PKG = "com.oplus.melody";

    /** {@code R.xml.melody_ui_high_audio_preference}. */
    public final int highAudioPreferenceXml;
    /** {@code R.xml.melody_app_onespace_list_preference}. */
    public final int oneSpacePreferenceXml;
    /** {@code R.xml.melody_app_onespace_list_preference_new}. Optional. */
    public final int oneSpacePreferenceXmlNew;
    /** Whether the host versionName matches the calibrated version (16.6.3). */
    public final boolean calibrated;

    public static final String KEY_HIRES_SWITCH_CATEGORY = "key_high_audio_hires_switch_category";
    public static final String KEY_CODEC_LIST_CATEGORY = "key_high_audio_codec_list_category";

    public static final String KEY_NOISE_MENU_CATEGORY = "pref_noise_menu_category";
    public static final String KEY_MORE_SETTING_CATEGORY = "pref_more_setting_category";

    private MelodyResIds(int hi, int os, int osNew, boolean calibrated) {
        this.highAudioPreferenceXml = hi;
        this.oneSpacePreferenceXml = os;
        this.oneSpacePreferenceXmlNew = osNew;
        this.calibrated = calibrated;
    }

    /**
     * Resolves the relevant ids using the host {@link Resources}. Throws on the first hard miss
     * (the HighAudio xml or any of the OneSpace xmls).
     */
    public static MelodyResIds resolve(Context hostContext, String hostVersion) {
        Resources r = hostContext.getResources();
        int hi = r.getIdentifier("melody_ui_high_audio_preference", "xml", HOST_PKG);
        int os = r.getIdentifier("melody_app_onespace_list_preference", "xml", HOST_PKG);
        int osNew = r.getIdentifier("melody_app_onespace_list_preference_new", "xml", HOST_PKG);
        if (hi == 0) {
            throw new MissingResIdException("melody_ui_high_audio_preference");
        }
        if (os == 0 && osNew == 0) {
            throw new MissingResIdException("melody_app_onespace_list_preference[_new]");
        }
        boolean calibrated = "16.6.3".equals(hostVersion);
        MLog.event("resids.resolved",
                "hi", hi, "os", os, "osNew", osNew, "calibrated", calibrated);
        return new MelodyResIds(hi, os, osNew, calibrated);
    }

    /** Thrown when a required host resource id cannot be resolved. */
    public static final class MissingResIdException extends RuntimeException {
        public MissingResIdException(String resourceName) {
            super("missing host resource: " + resourceName);
        }
    }
}
