package xyz.melodylsp.codec.host;

import android.content.Context;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import java.lang.reflect.Constructor;

import xyz.melodylsp.codec.R;
import xyz.melodylsp.codec.util.MLog;

/**
 * Builds the four-Preference block ({@code Codec_Display / Quality / SampleRate /
 * Remember_Toggle}) using only host-classpath types so the result is visually identical to the
 * surrounding rows (Requirement 11.4 / 11.5).
 *
 * <p>We try to use COUI-flavoured subclasses by reflection; if they cannot be resolved we fall
 * back to plain {@link Preference} / {@link ListPreference}. Either way the theming attributes
 * inherit from the host {@code MelodyAppTheme}.
 */
public final class CodecBlockBuilder {

    private static final String COUI_PREFERENCE_CATEGORY = "com.coui.appcompat.preference.COUIPreferenceCategory";
    private static final String COUI_PREFERENCE = "com.coui.appcompat.preference.COUIPreference";
    private static final String COUI_LIST_PREFERENCE = "com.coui.appcompat.preference.COUIListPreference";
    private static final String COUI_SWITCH_PREFERENCE = "com.coui.appcompat.preference.COUISwitchPreference";

    private CodecBlockBuilder() {
    }

    /**
     * Insert the codec block into {@code screen} at the position {@code order}, returning a
     * reference bag the caller can hand to {@link CodecController#attach(String, CodecPreferences,
     * androidx.lifecycle.LifecycleOwner)}.
     */
    public static CodecPreferences buildAndInsert(
            Context context,
            PreferenceScreen screen,
            int order) {
        PreferenceCategory category = newCategory(context);
        category.setKey("melody_codec_lsp_category");
        category.setTitle(context.getString(R.string.codec_block_title));
        category.setOrder(order);
        screen.addPreference(category);

        Preference codecDisplay = newPlainPreference(context);
        codecDisplay.setKey("melody_codec_lsp_display");
        codecDisplay.setTitle(context.getString(R.string.codec_display_title));
        codecDisplay.setSummary(context.getString(R.string.state_codec_unknown));
        codecDisplay.setSelectable(false);
        codecDisplay.setIconSpaceReserved(false);
        codecDisplay.setPersistent(false);
        category.addPreference(codecDisplay);

        ListPreference quality = newListPreference(context);
        quality.setKey("melody_codec_lsp_quality");
        quality.setTitle(context.getString(R.string.quality_option_title));
        quality.setVisible(false);
        quality.setIconSpaceReserved(false);
        quality.setPersistent(false);
        category.addPreference(quality);

        ListPreference sampleRate = newListPreference(context);
        sampleRate.setKey("melody_codec_lsp_sample_rate");
        sampleRate.setTitle(context.getString(R.string.sample_rate_option_title));
        sampleRate.setVisible(false);
        sampleRate.setIconSpaceReserved(false);
        sampleRate.setPersistent(false);
        category.addPreference(sampleRate);

        SwitchPreferenceCompat remember = newSwitchPreference(context);
        remember.setKey("melody_codec_lsp_remember");
        remember.setTitle(context.getString(R.string.remember_toggle_title));
        remember.setSummary(context.getString(R.string.remember_toggle_summary));
        remember.setIconSpaceReserved(false);
        remember.setPersistent(false);
        category.addPreference(remember);

        MLog.event("codec_block.inserted", "order", order);
        return new CodecPreferences(category, codecDisplay, quality, sampleRate, remember);
    }

    private static PreferenceCategory newCategory(Context context) {
        Object obj = tryNewInstance(COUI_PREFERENCE_CATEGORY, context);
        if (obj instanceof PreferenceCategory) return (PreferenceCategory) obj;
        return new PreferenceCategory(context);
    }

    private static Preference newPlainPreference(Context context) {
        Object obj = tryNewInstance(COUI_PREFERENCE, context);
        if (obj instanceof Preference) return (Preference) obj;
        return new Preference(context);
    }

    private static ListPreference newListPreference(Context context) {
        Object obj = tryNewInstance(COUI_LIST_PREFERENCE, context);
        if (obj instanceof ListPreference) return (ListPreference) obj;
        return new ListPreference(context);
    }

    private static SwitchPreferenceCompat newSwitchPreference(Context context) {
        Object obj = tryNewInstance(COUI_SWITCH_PREFERENCE, context);
        if (obj instanceof SwitchPreferenceCompat) return (SwitchPreferenceCompat) obj;
        return new SwitchPreferenceCompat(context);
    }

    private static Object tryNewInstance(String fqcn, Context context) {
        try {
            Class<?> cls = Class.forName(fqcn, true, context.getClassLoader());
            Constructor<?> ctor = cls.getConstructor(Context.class);
            return ctor.newInstance(context);
        } catch (Throwable t) {
            return null;
        }
    }
}
