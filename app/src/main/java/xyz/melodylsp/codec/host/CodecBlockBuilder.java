package xyz.melodylsp.codec.host;

import android.content.Context;

import xyz.melodylsp.codec.util.MLog;

/**
 * Builds the four-Preference block ({@code Codec_Display / Quality / SampleRate /
 * Remember_Toggle}) using only the host classpath. We never touch
 * {@code androidx.preference} types through compile-time symbols because the host APK is
 * R8-minified and the concrete class names ({@code PreferenceCategory},
 * {@code PreferenceFragmentCompat}, &c.) are gone — referencing them at compile time would
 * trigger {@link NoClassDefFoundError} the moment the JVM tries to verify our methods.
 *
 * <p>{@link PrefRef} hides every reflective invocation; the values returned from this builder
 * are plain {@link Object} references that the rest of the module passes around opaquely.</p>
 */
public final class CodecBlockBuilder {

    private static final String COUI_PREFERENCE_CATEGORY = "com.coui.appcompat.preference.COUIPreferenceCategory";
    private static final String COUI_PREFERENCE = "com.coui.appcompat.preference.COUIPreference";
    private static final String COUI_LIST_PREFERENCE = "com.coui.appcompat.preference.COUIListPreference";
    private static final String COUI_SWITCH_PREFERENCE = "com.coui.appcompat.preference.COUISwitchPreference";

    private static final String ANDX_PREFERENCE_CATEGORY = "androidx.preference.PreferenceCategory";
    private static final String ANDX_PREFERENCE = "androidx.preference.Preference";
    private static final String ANDX_LIST_PREFERENCE = "androidx.preference.ListPreference";
    private static final String ANDX_SWITCH_PREFERENCE_COMPAT = "androidx.preference.SwitchPreferenceCompat";

    private CodecBlockBuilder() {
    }

    /**
     * Insert the codec block into {@code screen} at the position {@code order}, returning a
     * reference bag the caller can hand to
     * {@link CodecController#attach(String, CodecPreferences, androidx.lifecycle.LifecycleOwner)}.
     *
     * <p>{@code screen} is the host-side {@code PreferenceScreen}; we do not type it because
     * the runtime class is minified.</p>
     */
    public static CodecPreferences buildAndInsert(Context context, Object screen, int order) {
        Object category = newOf(context,
                COUI_PREFERENCE_CATEGORY, ANDX_PREFERENCE_CATEGORY);
        if (category == null) {
            MLog.e("buildAndInsert: cannot resolve PreferenceCategory");
            return null;
        }
        PrefRef.setKey(category, "melody_codec_lsp_category");
        PrefRef.setTitle(category, Strings.CODEC_BLOCK_TITLE);
        PrefRef.setOrder(category, order);
        PrefRef.addPreference(screen, category);

        Object codecDisplay = newOf(context, COUI_PREFERENCE, ANDX_PREFERENCE);
        PrefRef.setKey(codecDisplay, "melody_codec_lsp_display");
        PrefRef.setTitle(codecDisplay, Strings.CODEC_DISPLAY_TITLE);
        PrefRef.setSummary(codecDisplay, Strings.STATE_CODEC_UNKNOWN);
        PrefRef.setSelectable(codecDisplay, false);
        PrefRef.setIconSpaceReserved(codecDisplay, false);
        PrefRef.setPersistent(codecDisplay, false);
        PrefRef.addPreference(category, codecDisplay);

        Object quality = newOf(context, COUI_LIST_PREFERENCE, ANDX_LIST_PREFERENCE);
        PrefRef.setKey(quality, "melody_codec_lsp_quality");
        PrefRef.setTitle(quality, Strings.QUALITY_OPTION_TITLE);
        PrefRef.setVisible(quality, false);
        PrefRef.setIconSpaceReserved(quality, false);
        PrefRef.setPersistent(quality, false);
        PrefRef.addPreference(category, quality);

        Object sampleRate = newOf(context, COUI_LIST_PREFERENCE, ANDX_LIST_PREFERENCE);
        PrefRef.setKey(sampleRate, "melody_codec_lsp_sample_rate");
        PrefRef.setTitle(sampleRate, Strings.SAMPLE_RATE_OPTION_TITLE);
        PrefRef.setVisible(sampleRate, false);
        PrefRef.setIconSpaceReserved(sampleRate, false);
        PrefRef.setPersistent(sampleRate, false);
        PrefRef.addPreference(category, sampleRate);

        Object remember = newOf(context, COUI_SWITCH_PREFERENCE, ANDX_SWITCH_PREFERENCE_COMPAT);
        PrefRef.setKey(remember, "melody_codec_lsp_remember");
        PrefRef.setTitle(remember, Strings.REMEMBER_TOGGLE_TITLE);
        PrefRef.setSummary(remember, Strings.REMEMBER_TOGGLE_SUMMARY);
        PrefRef.setIconSpaceReserved(remember, false);
        PrefRef.setPersistent(remember, false);
        PrefRef.addPreference(category, remember);

        MLog.event("codec_block.inserted", "order", order);
        return new CodecPreferences(category, codecDisplay, quality, sampleRate, remember);
    }

    private static Object newOf(Context context, String preferred, String fallback) {
        ClassLoader cl = context.getClassLoader();
        Class<?> cls = PrefRef.load(cl, preferred);
        if (cls != null) {
            Object instance = PrefRef.newInstance(cls, context);
            if (instance != null) return instance;
        }
        cls = PrefRef.load(cl, fallback);
        if (cls != null) {
            Object instance = PrefRef.newInstance(cls, context);
            if (instance != null) return instance;
            MLog.w("newOf: " + fallback + " present but cannot construct");
        } else {
            MLog.w("newOf: neither " + preferred + " nor " + fallback + " is on the host classpath");
        }
        return null;
    }
}
