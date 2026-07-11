package xyz.melodylsp.codec.leaudio;

import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Method;
import java.util.Enumeration;

import dalvik.system.DexFile;

import xyz.melodylsp.codec.MelodyCodecLspEntry;
import xyz.melodylsp.codec.util.MLog;
import xyz.melodylsp.codec.util.TrustedBroadcasts;

/**
 * Privileged LE Audio toggle bridge installed inside {@code com.oplus.wirelesssettings}
 * (TODO B2 — Phase 2, redesigned v2).
 *
 * <p>{@code com.oplus.wirelesssettings} runs as {@code android.uid.system}, so this process
 * can call the privileged settingslib API ({@code LeAudioProfile.setEnabled}). The confirmation
 * dialog is now shown on the melody side (which has a live Activity context with COUI theme);
 * this bridge only executes the toggle and echoes the authoritative state back.</p>
 *
 * <p>Flow:
 * <ol>
 *   <li>melody shows a confirmation dialog in its own Activity;</li>
 *   <li>user confirms → melody sends {@link LeAudioIpc#ACTION_SET_LE_AUDIO};</li>
 *   <li>this bridge receives it, calls {@code LeAudioProfile.setEnabled(device, enable)};</li>
 *   <li>after a delay (BT stack needs time), reads back the actual state and replies
 *       {@link LeAudioIpc#ACTION_LE_AUDIO_STATE} to melody.</li>
 * </ol>
 *
 * <p>Security: every sender must hold OPlus' signature-only component-safe permission. Android
 * 14+ additionally requires a framework-attributed sender identity belonging to
 * {@code com.oplus.melody}; the compile-time token is only a protocol marker.</p>
 */
public final class WirelessSettingsHookInstaller {

    private static final String CLASS_LOCAL_BT_MANAGER =
            "com.android.settingslib.bluetooth.LocalBluetoothManager";

    /** First readback delay after setEnabled — BT stack needs time to settle. */
    private static final long REPLY_DELAY_MS = 2000L;
    /** Second readback for cases where the first was too early. */
    private static final long REPLY_RETRY_MS = 3500L;

    private final MelodyCodecLspEntry module;
    private final ClassLoader classLoader;
    private final String processName;
    private final String sourceDir;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean receiverRegistered;
    private volatile Context appContext;

    public WirelessSettingsHookInstaller(
            MelodyCodecLspEntry module,
            ClassLoader classLoader,
            String processName,
            String sourceDir) {
        this.module = module;
        this.classLoader = classLoader;
        this.processName = processName;
        this.sourceDir = sourceDir;
    }

    public void install() {
        // Only the main wirelesssettings process owns the bluetooth profile manager; the
        // :seed / :qrscan / :tzupdate helper processes do not, so skip them.
        if (processName != null && processName.contains(":")) {
            MLog.event("le.ws.skip_subprocess", "process", processName);
            return;
        }
        try {
            Method onCreate = Application.class.getMethod("onCreate");
            module.hook(onCreate).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object app = chain.getThisObject();
                    if (app instanceof Application) {
                        ensureReceiver(((Application) app).getApplicationContext());
                    }
                } catch (Throwable t) {
                    MLog.e("LE Audio receiver registration failed", t);
                }
                return result;
            });
            MLog.event("le.ws.hook.installed", "process", processName);
        } catch (Throwable t) {
            MLog.e("WirelessSettingsHookInstaller.install failed", t);
        }
    }

    private synchronized void ensureReceiver(Context context) {
        if (receiverRegistered || context == null) return;
        MLog.setDiagnosticContext(context, "wirelesssettings");
        MLog.event("scope.wirelesssettings.context.ready");
        this.appContext = context;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleRequest(ctx, intent, TrustedBroadcasts.captureSender(this));
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(LeAudioIpc.ACTION_SET_LE_AUDIO);
        filter.addAction(LeAudioIpc.ACTION_QUERY_LE_AUDIO);
        if (!TrustedBroadcasts.registerExportedReceiver(
                context,
                receiver,
                filter,
                TrustedBroadcasts.PERMISSION_OPLUS_COMPONENT_SAFE,
                mainHandler)) {
            MLog.w("le.ws.receiver registration rejected: signature permission unavailable");
            return;
        }
        receiverRegistered = true;
        MLog.event("le.ws.receiver.registered");
    }

    private void handleRequest(
            Context ctx,
            Intent intent,
            TrustedBroadcasts.SenderIdentity sender) {
        try {
            if (intent == null) return;
            if (!LeAudioIpc.TOKEN.equals(intent.getStringExtra(LeAudioIpc.EXTRA_TOKEN))) {
                MLog.w("LE Audio request rejected: protocol mismatch");
                return;
            }
            String action = intent.getAction();
            if (!isAuthorizedRequest(ctx, action, sender)) return;
            String mac = intent.getStringExtra(LeAudioIpc.EXTRA_MAC);
            if (mac == null || mac.isEmpty()) {
                MLog.w("LE Audio request missing mac");
                return;
            }
            if (LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)) {
                boolean enable = intent.getBooleanExtra(LeAudioIpc.EXTRA_ENABLE, false);
                // User already confirmed on the melody side; apply directly.
                mainHandler.post(() -> applyAndReply(ctx, mac, enable));
            } else if (LeAudioIpc.ACTION_QUERY_LE_AUDIO.equals(action)) {
                replyState(ctx, mac, true);
            }
        } catch (Throwable t) {
            MLog.e("handleRequest failed", t);
        }
    }

    private static boolean isAuthorizedRequest(
            Context ctx,
            String action,
            TrustedBroadcasts.SenderIdentity sender) {
        if (!TrustedBroadcasts.supportsSenderIdentity()) {
            // Android 12/13 rely on the OPlus signature permission required at registration time.
            return LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)
                    || LeAudioIpc.ACTION_QUERY_LE_AUDIO.equals(action);
        }
        boolean trusted = TrustedBroadcasts.isTrustedSender(
                ctx, sender, LeAudioIpc.MELODY_PKG);
        if (trusted) return true;

        if (LeAudioIpc.ACTION_SET_LE_AUDIO.equals(action)) {
            MLog.w("LE Audio wireless-settings write rejected: sender identity unavailable or untrusted");
            return false;
        }
        MLog.w("LE Audio wireless-settings query rejected: untrusted sender");
        return false;
    }

    /**
     * Apply the toggle and reply with the authoritative state after a delay. The BT stack
     * needs 1-3 seconds to complete the connection-policy change, so we do two readbacks.
     */
    private void applyAndReply(Context ctx, String mac, boolean enable) {
        boolean ok = applyLeAudio(ctx, mac, enable);
        MLog.event("le.ws.apply", "enable", enable, "ok", ok);
        // First readback after REPLY_DELAY_MS
        mainHandler.postDelayed(() -> replyState(ctx, mac, ok), REPLY_DELAY_MS);
        // Second readback in case the first was too early
        mainHandler.postDelayed(() -> replyState(ctx, mac, ok), REPLY_RETRY_MS);
    }

    /**
     * Reflectively invoke {@code LeAudioProfile.setEnabled(device, enable)} through the
     * settingslib singletons. Returns true when the call succeeded.
     */
    private boolean applyLeAudio(Context ctx, String mac, boolean enable) {
        Object profile = resolveLeAudioProfile(ctx);
        if (profile == null) {
            MLog.w("applyLeAudio: LeAudioProfile unavailable");
            return false;
        }
        BluetoothDevice device = resolveDevice(mac);
        if (device == null) {
            MLog.w("applyLeAudio: device unresolved");
            return false;
        }
        try {
            Method setEnabled = findMethod(profile.getClass(), "setEnabled",
                    BluetoothDevice.class, boolean.class);
            if (setEnabled == null) {
                MLog.w("applyLeAudio: setEnabled(BluetoothDevice,boolean) not found");
                return false;
            }
            setEnabled.setAccessible(true);
            Object r = setEnabled.invoke(profile, device, enable);
            boolean ok = !(r instanceof Boolean) || (Boolean) r;
            MLog.event("le.ws.setEnabled", "enable", enable, "ok", ok);
            return ok;
        } catch (Throwable t) {
            MLog.e("applyLeAudio reflective setEnabled failed", t);
            return false;
        }
    }

    /** Read whether LE Audio is currently enabled for {@code mac}. */
    private boolean isLeAudioEnabled(Context ctx, String mac) {
        Object profile = resolveLeAudioProfile(ctx);
        BluetoothDevice device = resolveDevice(mac);
        if (profile == null || device == null) return false;
        try {
            Method isEnabled = findMethod(profile.getClass(), "isEnabled", BluetoothDevice.class);
            if (isEnabled == null) return false;
            isEnabled.setAccessible(true);
            Object r = isEnabled.invoke(profile, device);
            return r instanceof Boolean && (Boolean) r;
        } catch (Throwable t) {
            MLog.w("isLeAudioEnabled failed", t);
            return false;
        }
    }

    private void replyState(Context ctx, String mac, boolean ok) {
        Object profile = resolveLeAudioProfile(ctx);
        boolean supported = profile != null;
        boolean enabled = supported && isLeAudioEnabled(ctx, mac);
        Intent reply = new Intent(LeAudioIpc.ACTION_LE_AUDIO_STATE);
        reply.setPackage(LeAudioIpc.MELODY_PKG);
        reply.putExtra(LeAudioIpc.EXTRA_TOKEN, LeAudioIpc.TOKEN);
        reply.putExtra(LeAudioIpc.EXTRA_MAC, mac);
        reply.putExtra(LeAudioIpc.EXTRA_SUPPORTED, supported);
        reply.putExtra(LeAudioIpc.EXTRA_ENABLED, enabled);
        reply.putExtra(LeAudioIpc.EXTRA_OK, ok);
        try {
            if (!TrustedBroadcasts.send(ctx, reply)) {
                MLog.w("le.ws.reply identity send failed");
            }
            MLog.event("le.ws.reply", "supported", supported, "enabled", enabled, "ok", ok);
        } catch (Throwable t) {
            MLog.w("replyState sendBroadcast failed", t);
        }
    }

    /**
     * Resolve {@code LocalBluetoothManager.getInstance(ctx,null).getProfileManager()
     * .getLeAudioProfile()} entirely by reflection.
     */
    private Object resolveLeAudioProfile(Context ctx) {
        try {
            Class<?> mgrCls = resolveLocalBluetoothManagerClass();
            if (mgrCls == null) return null;
            Method getInstance = null;
            for (Method m : mgrCls.getMethods()) {
                if (!"getInstance".equals(m.getName())) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 2 && p[0] == Context.class) {
                    getInstance = m;
                    break;
                }
            }
            if (getInstance == null) return null;
            Object mgr = getInstance.invoke(null, ctx, null);
            if (mgr == null) return null;
            Object profileManager = invokeNoArg(mgr, "getProfileManager");
            if (profileManager == null) return null;
            return invokeNoArg(profileManager, "getLeAudioProfile");
        } catch (Throwable t) {
            MLog.w("resolveLeAudioProfile failed", t);
            return null;
        }
    }

    private Class<?> resolveLocalBluetoothManagerClass() {
        try {
            return Class.forName(CLASS_LOCAL_BT_MANAGER, false, classLoader);
        } catch (Throwable ignored) {
        }
        DexFile dex = null;
        try {
            if (sourceDir == null || sourceDir.isEmpty()) return null;
            dex = new DexFile(sourceDir);
            Enumeration<String> entries = dex.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement();
                if (!name.startsWith("com.android.settingslib.bluetooth.")) continue;
                if (!name.contains("BluetoothManager")
                        && !name.contains("LocalBluetooth")) continue;
                try {
                    Class<?> cls = Class.forName(name, false, classLoader);
                    if (looksLikeLocalBluetoothManager(cls)) {
                        MLog.event("le.ws.local_manager.resolved",
                                "mode", "scan", "class", name);
                        return cls;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            MLog.w("resolveLocalBluetoothManager dex scan failed", t);
        } finally {
            if (dex != null) {
                try {
                    dex.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static boolean looksLikeLocalBluetoothManager(Class<?> cls) {
        if (cls == null) return false;
        boolean hasGetInstance = false;
        boolean hasProfileManager = false;
        for (Method m : cls.getMethods()) {
            if ("getInstance".equals(m.getName())) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length >= 1 && p[0] == Context.class) {
                    hasGetInstance = true;
                }
            } else if ("getProfileManager".equals(m.getName()) && m.getParameterCount() == 0) {
                hasProfileManager = true;
            }
        }
        return hasGetInstance && hasProfileManager;
    }

    private static BluetoothDevice resolveDevice(String mac) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            return adapter != null ? adapter.getRemoteDevice(mac) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Method findMethod(Class<?> startCls, String name, Class<?>... params) {
        Class<?> cls = startCls;
        while (cls != null && cls != Object.class) {
            try {
                return cls.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }
}
