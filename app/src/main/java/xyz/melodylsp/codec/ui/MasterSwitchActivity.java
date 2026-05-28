package xyz.melodylsp.codec.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import xyz.melodylsp.codec.R;

/**
 * Tiny standalone Activity for toggling {@code module_prefs.xml#enabled}. The libxposed
 * framework mirrors this SharedPreferences file into the in-host process via
 * {@code XposedModule.getRemotePreferences("module_prefs")}, so flipping the switch here is
 * picked up the next time the host process starts.
 */
public final class MasterSwitchActivity extends Activity {

    private static final String PREFS_NAME = "module_prefs";
    private static final String KEY_ENABLED = "enabled";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Use MODE_WORLD_READABLE so older LSPosed implementations that read the file directly
        // (rather than via the libxposed remote prefs pipe) can still observe changes.
        @SuppressWarnings("WorldReadableFiles")
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_WORLD_READABLE);
        boolean enabled = prefs.getBoolean(KEY_ENABLED, true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.master_switch_title);
        title.setTextSize(20);
        title.setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Large);
        root.addView(title, lp());

        TextView summary = new TextView(this);
        summary.setText(R.string.master_switch_summary);
        summary.setTextAppearance(android.R.style.TextAppearance_DeviceDefault);
        ViewGroup.MarginLayoutParams lpSummary = new ViewGroup.MarginLayoutParams(lp());
        lpSummary.topMargin = padding / 2;
        summary.setLayoutParams(lpSummary);
        root.addView(summary);

        Switch switchView = new Switch(this);
        switchView.setText(R.string.master_switch_label);
        switchView.setChecked(enabled);
        switchView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ViewGroup.MarginLayoutParams lpSwitch = new ViewGroup.MarginLayoutParams(lp());
        lpSwitch.topMargin = padding;
        switchView.setLayoutParams(lpSwitch);
        root.addView(switchView);

        TextView status = new TextView(this);
        status.setText(enabled ? R.string.master_switch_status_on : R.string.master_switch_status_off);
        ViewGroup.MarginLayoutParams lpStatus = new ViewGroup.MarginLayoutParams(lp());
        lpStatus.topMargin = padding / 2;
        status.setLayoutParams(lpStatus);
        root.addView(status);

        switchView.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(KEY_ENABLED, isChecked).apply();
            status.setText(isChecked ? R.string.master_switch_status_on : R.string.master_switch_status_off);
        });

        setContentView(root);
    }

    private static ViewGroup.LayoutParams lp() {
        return new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
