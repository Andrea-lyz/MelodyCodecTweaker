package xyz.melodylsp.codec.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import xyz.melodylsp.codec.R;
import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.bt.BluetoothCodecReflect;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;
import xyz.melodylsp.codec.util.MLog;

/**
 * The single host-side state owner. Each Surface attaches its {@link CodecPreferences} bag, the
 * controller refreshes UI state from {@code BluetoothA2dp.getCodecStatus} and routes write
 * intents through {@link CodecBridgeClient}.
 */
public final class CodecController {

    private static final String ACTION_CODEC_CONFIG_CHANGED =
            "android.bluetooth.a2dp.profile.action.CODEC_CONFIG_CHANGED";
    private static final String ACTION_CONNECTION_STATE_CHANGED =
            "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED";

    private final Context context;
    private final BluetoothCodecReflect reflect;
    private final CodecBridgeClient bridge;
    private final PreferenceStore prefs;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ConnectionStateReplayer replayer;
    private final Map<LifecycleOwner, Subscription> subscriptions = new HashMap<>();
    private final AtomicReference<CodecSnapshot> lastSnapshot = new AtomicReference<>();

    public CodecController(
            Context context,
            BluetoothCodecReflect reflect,
            CodecBridgeClient bridge,
            PreferenceStore prefs) {
        this.context = context.getApplicationContext();
        this.reflect = reflect;
        this.bridge = bridge;
        this.prefs = prefs;
        this.replayer = new ConnectionStateReplayer(this.context, bridge, prefs);
        this.replayer.start();
        this.bridge.addSnapshotListener(this::onPushedSnapshot);
    }

    /** Bind a {@link CodecPreferences} bag to a lifecycle and refresh on resume. */
    public void attach(String mac, CodecPreferences pref, LifecycleOwner owner) {
        Subscription sub = new Subscription(mac, pref);
        subscriptions.put(owner, sub);

        wireListeners(sub);

        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override
            public void onStart(LifecycleOwner ownerInner) {
                sub.registerReceiver();
                mainHandler.post(() -> refreshSnapshot(sub));
            }

            @Override
            public void onStop(LifecycleOwner ownerInner) {
                sub.unregisterReceiver();
            }

            @Override
            public void onDestroy(LifecycleOwner ownerInner) {
                subscriptions.remove(owner);
            }
        });
    }

    private void wireListeners(Subscription sub) {
        // Quality changes -> setCodec with new specific1.
        sub.prefs.qualityOption.setOnPreferenceChangeListener((p, value) -> {
            if (!(value instanceof String)) return false;
            CodecSnapshot snapshot = lastSnapshot.get();
            if (snapshot == null) return false;
            long specific1;
            try {
                specific1 = Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return false;
            }
            CodecRequest req = CodecRequest.fromActive(snapshot)
                    .withSpecific1(specific1)
                    .build();
            applyWrite(sub, req);
            return true;
        });

        // Sample rate changes -> setCodec with new sampleRate.
        sub.prefs.sampleRateOption.setOnPreferenceChangeListener((p, value) -> {
            if (!(value instanceof String)) return false;
            CodecSnapshot snapshot = lastSnapshot.get();
            if (snapshot == null) return false;
            int rateBit = decodeStoredSampleRate((String) value, snapshot.selectableSampleRateMask);
            if (rateBit < 0) return false;
            CodecRequest req = CodecRequest.fromActive(snapshot)
                    .withSampleRate(rateBit)
                    .build();
            applyWrite(sub, req);
            return true;
        });

        // Remember toggle.
        sub.prefs.rememberToggle.setOnPreferenceChangeListener((p, value) -> {
            if (!(value instanceof Boolean)) return false;
            boolean enabled = (Boolean) value;
            prefs.setRemembered(sub.mac, enabled);
            if (enabled) {
                CodecSnapshot s = lastSnapshot.get();
                if (s != null && s.mac != null && s.mac.equals(sub.mac)) {
                    prefs.writeSnapshot(sub.mac, s.activeCodecSpecific1, s.activeSampleRate);
                }
            }
            return true;
        });
    }

    private void applyWrite(Subscription sub, CodecRequest request) {
        bridge.setCodec(request).whenComplete((result, ex) -> mainHandler.post(() -> {
            if (ex != null) {
                MLog.e("setCodec future failed", ex);
                Toast.makeText(context, R.string.toast_apply_failed, Toast.LENGTH_SHORT).show();
                refreshSnapshot(sub);
                return;
            }
            switch (result.outcome) {
                case CONFIRMED:
                    if (result.path == WriteResult.Path.SETTINGS_GLOBAL) {
                        Toast.makeText(context, R.string.banner_via_settings, Toast.LENGTH_LONG).show();
                    }
                    if (prefs.isRemembered(sub.mac)) {
                        prefs.writeSnapshot(sub.mac, request.codecSpecific1, request.sampleRate);
                    }
                    refreshSnapshot(sub);
                    break;
                case TIMEOUT_ROLLED_BACK:
                    Toast.makeText(context, R.string.toast_apply_failed, Toast.LENGTH_SHORT).show();
                    if (result.rollbackSnapshot != null) {
                        publish(result.rollbackSnapshot, sub);
                    } else {
                        refreshSnapshot(sub);
                    }
                    break;
                case FAILED:
                default:
                    Toast.makeText(context, R.string.toast_apply_failed, Toast.LENGTH_SHORT).show();
                    refreshSnapshot(sub);
                    break;
            }
        }));
    }

    private void refreshSnapshot(Subscription sub) {
        Thread worker = new Thread(() -> {
            CodecSnapshot snapshot;
            try {
                snapshot = reflect.readStatus(sub.mac);
            } catch (BluetoothCodecReflect.BluetoothCodecReflectException e) {
                MLog.w("refreshSnapshot reflect failed", e);
                snapshot = bridge.getStatus(sub.mac);
            } catch (Throwable t) {
                MLog.e("refreshSnapshot threw", t);
                snapshot = null;
            }
            CodecSnapshot finalSnapshot = snapshot;
            mainHandler.post(() -> publish(finalSnapshot, sub));
        }, "MelodyCodecLsp-refresh");
        worker.setDaemon(true);
        worker.start();
    }

    private void publish(CodecSnapshot snapshot, Subscription sub) {
        if (snapshot != null) {
            lastSnapshot.set(snapshot);
        }
        if (snapshot == null) {
            CodecSnapshot stale = lastSnapshot.get();
            if (stale != null) {
                renderSnapshot(stale, sub, /* fromCache= */ true);
            } else {
                renderUnknown(sub);
            }
            return;
        }
        renderSnapshot(snapshot, sub, /* fromCache= */ false);
    }

    /**
     * Called when {@link CodecBridgeClient} forwards a system-side push event. We refresh every
     * subscription whose MAC matches; subscriptions for other devices are left alone.
     */
    private void onPushedSnapshot(CodecSnapshot snapshot) {
        if (snapshot == null || snapshot.mac == null) return;
        mainHandler.post(() -> {
            lastSnapshot.set(snapshot);
            for (Subscription sub : subscriptions.values()) {
                if (snapshot.mac.equals(sub.mac)) {
                    renderSnapshot(snapshot, sub, /* fromCache= */ false);
                }
            }
        });
    }

    private void renderUnknown(Subscription sub) {
        sub.prefs.codecDisplay.setSummary(context.getString(R.string.state_codec_unknown));
        sub.prefs.qualityOption.setVisible(false);
        sub.prefs.sampleRateOption.setVisible(false);
        sub.prefs.rememberToggle.setChecked(prefs.isRemembered(sub.mac));
    }

    private void renderSnapshot(CodecSnapshot snapshot, Subscription sub, boolean fromCache) {
        String codecName = CodecLabelTable.codecLabel(context, snapshot.activeCodecType);
        if (fromCache) {
            String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT)
                    .format(new Date());
            sub.prefs.codecDisplay.setSummary(codecName + "  ·  "
                    + context.getString(R.string.freshness_label, stamp));
        } else {
            sub.prefs.codecDisplay.setSummary(codecName);
        }

        renderQuality(snapshot, sub);
        renderSampleRate(snapshot, sub);
        sub.prefs.rememberToggle.setChecked(prefs.isRemembered(sub.mac));
    }

    private void renderQuality(CodecSnapshot snapshot, Subscription sub) {
        long[] selectable = snapshot.selectableCodecSpecific1;
        ListPreference q = sub.prefs.qualityOption;
        if (selectable == null || selectable.length == 0) {
            q.setVisible(false);
            return;
        }
        // Hide Quality option for codecs that do not expose quality steps (e.g. SBC default).
        if (snapshot.activeCodecType != CodecLabelTable.CODEC_LDAC
                && snapshot.activeCodecType != CodecLabelTable.CODEC_LHDC) {
            q.setVisible(false);
            return;
        }
        CharSequence[] entries = new CharSequence[selectable.length];
        CharSequence[] values = new CharSequence[selectable.length];
        for (int i = 0; i < selectable.length; i++) {
            entries[i] = CodecLabelTable.qualityLabel(context, snapshot.activeCodecType, selectable[i]);
            values[i] = String.valueOf(selectable[i]);
        }
        q.setEntries(entries);
        q.setEntryValues(values);
        String currentValue = String.valueOf(snapshot.activeCodecSpecific1);
        if (!Arrays.asList(values).contains(currentValue)) {
            q.setSummary(context.getString(R.string.quality_unknown_value, currentValue));
        } else {
            q.setValue(currentValue);
            q.setSummary(CodecLabelTable.qualityLabel(
                    context, snapshot.activeCodecType, snapshot.activeCodecSpecific1));
        }
        q.setVisible(true);
    }

    private void renderSampleRate(CodecSnapshot snapshot, Subscription sub) {
        int[] rates = CodecSnapshot.decodeSampleRateBits(snapshot.selectableSampleRateMask);
        ListPreference r = sub.prefs.sampleRateOption;
        if (rates.length == 0) {
            r.setVisible(false);
            return;
        }
        List<CharSequence> entryList = new ArrayList<>(rates.length);
        List<CharSequence> valueList = new ArrayList<>(rates.length);
        for (int rate : rates) {
            entryList.add(CodecLabelTable.sampleRateLabel(rate));
            valueList.add(String.valueOf(rate));
        }
        r.setEntries(entryList.toArray(new CharSequence[0]));
        r.setEntryValues(valueList.toArray(new CharSequence[0]));
        // Map the active sample-rate bit to a Hz value if known so we select the right entry.
        int activeHz = sampleRateBitToHz(snapshot.activeSampleRate);
        if (activeHz > 0) {
            r.setValue(String.valueOf(activeHz));
            r.setSummary(CodecLabelTable.sampleRateLabel(activeHz));
        }
        r.setVisible(true);
    }

    private static int sampleRateBitToHz(int bit) {
        int[] decoded = CodecSnapshot.decodeSampleRateBits(bit);
        if (decoded.length != 1 || decoded[0] <= 0) return -1;
        return decoded[0];
    }

    /**
     * Inverse of {@link #sampleRateBitToHz(int)}. Walks the selectable-mask once to find the
     * single bit that decodes to the chosen Hz value, so we never write a bit the platform does
     * not support.
     */
    private static int decodeStoredSampleRate(String storedValue, int selectableMask) {
        int hz;
        try {
            hz = Integer.parseInt(storedValue);
        } catch (NumberFormatException e) {
            return -1;
        }
        int[] supported = CodecSnapshot.decodeSampleRateBits(selectableMask);
        for (int v : supported) {
            if (v == hz) {
                // Find the original bit that decoded to this Hz.
                for (int b = 0; b < 31; b++) {
                    int bit = 1 << b;
                    if ((selectableMask & bit) == 0) continue;
                    int[] decoded = CodecSnapshot.decodeSampleRateBits(bit);
                    if (decoded.length == 1 && decoded[0] == hz) return bit;
                }
            }
        }
        return -1;
    }

    /** Per-attach state — receiver, MAC, and Preference references. */
    private final class Subscription {
        final String mac;
        final CodecPreferences prefs;
        BroadcastReceiver receiver;

        Subscription(String mac, CodecPreferences prefs) {
            this.mac = mac;
            this.prefs = prefs;
        }

        void registerReceiver() {
            if (receiver != null) return;
            receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_CODEC_CONFIG_CHANGED.equals(action)
                            || ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                        refreshSnapshot(Subscription.this);
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_CODEC_CONFIG_CHANGED);
            filter.addAction(ACTION_CONNECTION_STATE_CHANGED);
            try {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } catch (Throwable t) {
                context.registerReceiver(receiver, filter);
            }
        }

        void unregisterReceiver() {
            if (receiver == null) return;
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiver = null;
        }
    }
}
