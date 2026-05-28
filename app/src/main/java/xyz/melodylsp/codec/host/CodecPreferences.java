package xyz.melodylsp.codec.host;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

/** Bag of references to the four Preferences we own inside a single Codec_Block. */
public final class CodecPreferences {

    public final PreferenceCategory category;
    public final Preference codecDisplay;
    public final ListPreference qualityOption;
    public final ListPreference sampleRateOption;
    public final SwitchPreferenceCompat rememberToggle;

    public CodecPreferences(
            PreferenceCategory category,
            Preference codecDisplay,
            ListPreference qualityOption,
            ListPreference sampleRateOption,
            SwitchPreferenceCompat rememberToggle) {
        this.category = category;
        this.codecDisplay = codecDisplay;
        this.qualityOption = qualityOption;
        this.sampleRateOption = sampleRateOption;
        this.rememberToggle = rememberToggle;
    }
}
