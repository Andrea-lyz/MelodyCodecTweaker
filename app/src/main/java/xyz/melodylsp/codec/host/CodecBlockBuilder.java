package xyz.melodylsp.codec.host;

import android.content.Context;

import xyz.melodylsp.codec.util.MLog;

/**
 * Builds the codec block (Quality / SampleRate / Remember Toggle) using only host classpath
 * types. <strong>No ListPreference is used</strong>: the host APK is R8-minified and R8
 * stripped {@code ListPreference.setEntries / setEntryValues} entirely (they are unused in
 * the host's own code), so the moment the user taps a {@code ListPreference} the dialog
 * fragment crashes with {@code "ListPreference requires an entries array and an entryValues
 * array."} regardless of how we try to populate it. We dodge the entire dialog path by using
 * plain {@code Preference} rows whose click handler is a hand-rolled {@code AlertDialog}
 * (see {@link CodecController#showPickerDialog}). Same UX, none of the R8 fallout.
 *
 * <p>OneSpace gets the items flattened into the screen (no Category wrapper, no remember
 * toggle — that surface is for instant switching, persistence belongs in DetailMain).
 * DetailMain gets the full block wrapped in a Category card.</p>
 */
public final class CodecBlockBuilder {

    /** OPPO ships a customised category that pulls colour / spacing from the host theme. */
    private static final String MELODY_PREFERENCE_CATEGORY =
            "com.oplus.melody.common.widget.MelodyCOUIPreferenceCategory";
    private static final String COUI_PREFERENCE_CATEGORY =
            "com.coui.appcompat.preference.COUIPreferenceCategory";
    private static final String COUI_PREFERENCE =
            "com.coui.appcompat.preference.COUIPreference";
    private static final String COUI_SWITCH_PREFERENCE =
            "com.coui.appcompat.preference.COUISwitchPreference";

    private static final String ANDX_PREFERENCE_CATEGORY = "androidx.preference.PreferenceCategory";
    private static final String ANDX_PREFERENCE = "androidx.preference.Preference";
    private static final String ANDX_SWITCH_PREFERENCE_COMPAT =
            "androidx.preference.SwitchPreferenceCompat";

    private CodecBlockBuilder() {
    }

    /**
     * Insert the codec block into {@code container}.
     *
     * @param wrapInCategory   true → DetailMain card style (PreferenceCategory wrapper).
     * @param includeRemember  true → include the "remember this earphone" SwitchPreference.
     */
    public static CodecPreferences buildAndInsert(
            Context context, Object container, int order,
            boolean wrapInCategory, boolean includeRemember) {
        Object styleSource = container;
        Object categoryTemplate = findFirstOfType(styleSource, "PreferenceCategory");
        Object prefTemplate = findFirstOfType(styleSource, "Preference");
        Object switchTemplate = findFirstOfType(styleSource, "SwitchPreference");
        if (switchTemplate == null) switchTemplate = prefTemplate;

        Object category = null;
        Object header = null;
        Object insertionParent = container;
        int firstChildOrder = order;
        if (wrapInCategory) {
            category = newOf(context,
                    MELODY_PREFERENCE_CATEGORY, COUI_PREFERENCE_CATEGORY, ANDX_PREFERENCE_CATEGORY);
            if (category == null) {
                MLog.e("buildAndInsert: cannot resolve PreferenceCategory");
                return null;
            }
            cloneVisualStyleFrom(category, categoryTemplate);
            PrefRef.setKey(category, "melody_codec_lsp_category");
            PrefRef.setTitle(category, Strings.CODEC_BLOCK_TITLE);
            PrefRef.setOrder(category, order);
            PrefRef.addPreference(container, category);
            insertionParent = category;
            firstChildOrder = 0;
        } else {
            header = newOf(context, COUI_PREFERENCE, ANDX_PREFERENCE);
            if (header != null) {
                cloneVisualStyleFrom(header, prefTemplate);
                PrefRef.setKey(header, "melody_codec_lsp_header");
                PrefRef.setTitle(header, Strings.CODEC_BLOCK_TITLE);
                PrefRef.setSelectable(header, false);
                PrefRef.setIconSpaceReserved(header, false);
                PrefRef.setPersistent(header, false);
                PrefRef.setOrder(header, order);
                PrefRef.addPreference(container, header);
                firstChildOrder = order + 1;
            }
        }

        // Plain Preference rows for quality and sample rate: their click handlers will pop a
        // hand-rolled AlertDialog instead of going through ListPreference's R8-stripped path.
        Object quality = newOf(context, COUI_PREFERENCE, ANDX_PREFERENCE);
        if (quality != null) {
            cloneVisualStyleFrom(quality, prefTemplate);
            PrefRef.setKey(quality, "melody_codec_lsp_quality");
            PrefRef.setTitle(quality, Strings.QUALITY_OPTION_TITLE);
            PrefRef.setVisible(quality, false);
            PrefRef.setIconSpaceReserved(quality, false);
            PrefRef.setPersistent(quality, false);
            PrefRef.setOrder(quality, firstChildOrder);
            PrefRef.addPreference(insertionParent, quality);
        }

        Object sampleRate = newOf(context, COUI_PREFERENCE, ANDX_PREFERENCE);
        if (sampleRate != null) {
            cloneVisualStyleFrom(sampleRate, prefTemplate);
            PrefRef.setKey(sampleRate, "melody_codec_lsp_sample_rate");
            PrefRef.setTitle(sampleRate, Strings.SAMPLE_RATE_OPTION_TITLE);
            PrefRef.setVisible(sampleRate, false);
            PrefRef.setIconSpaceReserved(sampleRate, false);
            PrefRef.setPersistent(sampleRate, false);
            PrefRef.setOrder(sampleRate, firstChildOrder + 1);
            PrefRef.addPreference(insertionParent, sampleRate);
        }

        Object remember = null;
        if (includeRemember) {
            remember = newOf(context, COUI_SWITCH_PREFERENCE, ANDX_SWITCH_PREFERENCE_COMPAT);
            if (remember != null) {
                cloneVisualStyleFrom(remember, switchTemplate);
                PrefRef.setKey(remember, "melody_codec_lsp_remember");
                PrefRef.setTitle(remember, Strings.REMEMBER_TOGGLE_TITLE);
                PrefRef.setSummary(remember, Strings.REMEMBER_TOGGLE_SUMMARY);
                PrefRef.setIconSpaceReserved(remember, false);
                PrefRef.setPersistent(remember, false);
                PrefRef.setOrder(remember, firstChildOrder + 2);
                PrefRef.addPreference(insertionParent, remember);
            }
        }

        Object codecDisplay;
        if (category != null) codecDisplay = category;
        else if (header != null) codecDisplay = header;
        else if (quality != null) codecDisplay = quality;
        else if (sampleRate != null) codecDisplay = sampleRate;
        else codecDisplay = remember;

        MLog.event("codec_block.inserted", "order", order,
                "wrapped", wrapInCategory, "remember", includeRemember);
        return new CodecPreferences(context, category, codecDisplay, quality, sampleRate, remember);
    }

    /** Backwards-compatible overload: wrap in category, include remember toggle. */
    public static CodecPreferences buildAndInsert(Context context, Object container, int order) {
        return buildAndInsert(context, container, order,
                /* wrapInCategory= */ true, /* includeRemember= */ true);
    }

    /** Wrap-in-category overload (legacy). */
    public static CodecPreferences buildAndInsert(
            Context context, Object container, int order, boolean wrapInCategory) {
        return buildAndInsert(context, container, order, wrapInCategory,
                /* includeRemember= */ true);
    }

    private static void cloneVisualStyleFrom(Object to, Object from) {
        if (to == null || from == null) return;
        int layout = PrefRef.getLayoutResource(from);
        if (layout != 0) PrefRef.setLayoutResource(to, layout);
        int widget = PrefRef.getWidgetLayoutResource(from);
        if (widget != 0) PrefRef.setWidgetLayoutResource(to, widget);
    }

    private static Object findFirstOfType(Object container, String suffix) {
        if (container == null) return null;
        int count = PrefRef.getPreferenceCount(container);
        Object fallback = null;
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(container, i);
            if (pref == null) continue;
            if (isUnsuitableTemplate(pref)) continue;
            String name = pref.getClass().getName();
            if (matchesSuffix(name, suffix)) {
                if (PrefRef.getLayoutResource(pref) != 0) return pref;
                if (fallback == null) fallback = pref;
            }
        }
        for (int i = 0; i < count; i++) {
            Object pref = PrefRef.getPreference(container, i);
            if (pref == null) continue;
            int childCount = PrefRef.getPreferenceCount(pref);
            if (childCount > 0) {
                Object found = findFirstOfType(pref, suffix);
                if (found != null && PrefRef.getLayoutResource(found) != 0) return found;
                if (fallback == null) fallback = found;
            }
        }
        return fallback;
    }

    private static boolean isUnsuitableTemplate(Object pref) {
        String key = PrefRef.getKey(pref);
        return "footer_preference".equals(key)
                || "pref_device_info".equals(key);
    }

    private static boolean matchesSuffix(String className, String suffix) {
        if (className == null) return false;
        if (suffix.equals("Preference")) {
            // OneSpace has custom top-level rows such as OneSpaceHeaderPreference. Cloning
            // their layout makes our injected rows render as a blank white block. Prefer the
            // regular COUI rows used by "noise menu" / "more settings" instead.
            if (className.contains("COUIJumpPreference")
                    || className.contains("COUIMenuPreference")
                    || className.contains("COUIPreference")) {
                return !className.contains("Category")
                        && !className.contains("Switch")
                        && !className.contains("List")
                        && !className.contains("Group");
            }
            return "androidx.preference.Preference".equals(className);
        }
        if (className.endsWith("." + suffix)) return true;
        if (className.endsWith(suffix)) return true;
        if (suffix.equals("SwitchPreference") && className.contains("SwitchPreference")) return true;
        if (suffix.equals("PreferenceCategory") && className.contains("PreferenceCategory")) return true;
        return false;
    }

    private static Object newOf(Context context, String... classNames) {
        ClassLoader cl = context.getClassLoader();
        for (String name : classNames) {
            Class<?> cls = PrefRef.load(cl, name);
            if (cls == null) continue;
            Object instance = PrefRef.newInstanceWithAttrs(cls, context);
            if (instance != null) return instance;
            MLog.w("newOf: " + name + " present but cannot construct");
        }
        MLog.w("newOf: none of " + java.util.Arrays.toString(classNames) + " resolvable");
        return null;
    }
}
