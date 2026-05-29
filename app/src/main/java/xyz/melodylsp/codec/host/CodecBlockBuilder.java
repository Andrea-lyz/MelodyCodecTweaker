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
 * plain {@code Preference} rows whose click handler pops a hand-rolled {@code PopupWindow}
 * (see {@link CodecController}). Same UX, none of the R8 fallout.
 *
 * <p>OneSpace skips the remember toggle — that surface is for instant switching, persistence
 * belongs in DetailMain. Both surfaces can use a Category wrapper when the host list needs
 * COUI to own the card background.</p>
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
        return buildAndInsert(context, container, order, wrapInCategory, includeRemember,
                /* includeLeAudio= */ false);
    }

    /**
     * Insert the codec block into {@code container}.
     *
     * @param wrapInCategory   true → DetailMain card style (PreferenceCategory wrapper).
     * @param includeRemember  true → include the "remember this earphone" SwitchPreference.
     * @param includeLeAudio   true → include the LE Audio toggle (DetailMain only, TODO B1).
     */
    public static CodecPreferences buildAndInsert(
            Context context, Object container, int order,
            boolean wrapInCategory, boolean includeRemember, boolean includeLeAudio) {
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
            // Suppress the Category's own title — it would render as the host's grey
            // "section header" floating above the white card, the way "通用设置" does. We want
            // the codec name to live INSIDE the white card as a regular row instead. Empty
            // title shrinks the header strip to its minimum; the actual "蓝牙音质 · LHDC" goes
            // on the first child row below.
            PrefRef.setTitle(category, "");
            PrefRef.setOrder(category, order);
            PrefRef.addPreference(container, category);
            insertionParent = category;
            firstChildOrder = 0;
        }

        // First child row — header. Lives inside the white card whether or not we wrapped it
        // in a Category, so the visual is "蓝牙音质 · LHDC" stacked above 播放质量 / 采样率 in
        // a single connected card. Non-selectable; no click ripple.
        header = newOf(context, COUI_PREFERENCE, ANDX_PREFERENCE);
        if (header != null) {
            cloneVisualStyleFrom(header, prefTemplate);
            PrefRef.setKey(header, "melody_codec_lsp_header");
            PrefRef.setTitle(header, Strings.CODEC_BLOCK_TITLE);
            PrefRef.setSelectable(header, false);
            PrefRef.setIconSpaceReserved(header, false);
            PrefRef.setPersistent(header, false);
            PrefRef.setWidgetLayoutResource(header, 0);
            PrefRef.setOrder(header, firstChildOrder);
            PrefRef.addPreference(insertionParent, header);
            firstChildOrder++;
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
            PrefRef.setWidgetLayoutResource(quality, 0);
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
            PrefRef.setWidgetLayoutResource(sampleRate, 0);
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

        Object leAudio = null;
        if (includeLeAudio) {
            leAudio = newOf(context, COUI_SWITCH_PREFERENCE, ANDX_SWITCH_PREFERENCE_COMPAT);
            if (leAudio != null) {
                cloneVisualStyleFrom(leAudio, switchTemplate);
                PrefRef.setKey(leAudio, "melody_codec_lsp_le_audio");
                PrefRef.setTitle(leAudio, Strings.LE_AUDIO_TITLE);
                PrefRef.setSummary(leAudio, Strings.LE_AUDIO_SUMMARY_UNKNOWN);
                PrefRef.setIconSpaceReserved(leAudio, false);
                PrefRef.setPersistent(leAudio, false);
                // Hidden until the wirelesssettings bridge confirms the device supports LE
                // Audio (TODO B1 device-support probe); CodecController flips it visible.
                PrefRef.setVisible(leAudio, false);
                PrefRef.setOrder(leAudio, firstChildOrder + 3);
                PrefRef.addPreference(insertionParent, leAudio);
            }
        }

        Object codecDisplay;
        // codecDisplay is the row whose title CodecController updates to show
        // "蓝牙音质 · LHDC" / freshness stamps. Always prefer the header row (lives inside the
        // card); fall back to whichever child still rendered if header construction failed.
        if (header != null) codecDisplay = header;
        else if (category != null) codecDisplay = category;
        else if (quality != null) codecDisplay = quality;
        else if (sampleRate != null) codecDisplay = sampleRate;
        else codecDisplay = remember;

        MLog.event("codec_block.inserted", "order", order,
                "wrapped", wrapInCategory, "remember", includeRemember, "leAudio", includeLeAudio);
        return new CodecPreferences(
                context, category, codecDisplay, quality, sampleRate, remember, leAudio);
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
                || "pref_device_info".equals(key)
                || "pref_noise_menu".equals(key);
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
