package xyz.melodylsp.codec.system;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.SystemClock;
import android.provider.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import xyz.melodylsp.codec.leaudio.BluetoothLeAudioBridge;
import xyz.melodylsp.codec.util.MLog;

/** Owns temporary game LE Audio sessions; explicit user LE Audio choices take priority. */
public final class GameLeaController implements BluetoothLeAudioBridge.GameLatencyListener {
    private static final String PREFS = "melody_codec_game_latency";
    private static final String KEY_LOW_LATENCY = "game_low_latency_enabled";
    private static final String KEY_AUTO_LEA = "game_auto_lea_enabled";
    private static final String KEY_OWNED_MAC = "game_owned_lea_mac";
    private static final String KEY_OFFICIAL_LOW_LATENCY_STATE = "oplus_games_low_latency_state";
    private static final String KEY_CURRENT_GAME_PACKAGE = "debug_gamemode_savegame";
    private static final long BRINGUP_TIMEOUT_MS = 15_000L;
    private static final long BACKGROUND_EXIT_DELAY_MS = 30_000L;
    private static final long SUPPRESS_LOG_INTERVAL_MS = 5_000L;

    private final Context context;
    private final BluetoothLeAudioBridge bridge;
    private final ExitGate exitGate = new ExitGate();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "MelodyCodecLsp-game-lea");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean lowLatencyEnabled = true;
    private volatile boolean autoLeaEnabled;
    private volatile boolean gameActive;
    private volatile boolean manualOverride;
    private volatile Object gameService;
    private volatile Session session;
    private volatile long lastSuppressLogMs;

    private static final class Session {
        final String mac;
        volatile String gamePackage;
        volatile boolean suppress = true;
        volatile boolean ready;
        volatile long exitTicket;
        volatile long exitStartedElapsedMs;
        volatile long mainProcessDiedElapsedMs;
        final ExitDeadline exitDeadline = new ExitDeadline();
        boolean started;
        boolean enabledLeaByUs;
        int restoreAttempts;

        Session(String mac, String gamePackage) {
            this.mac = mac;
            this.gamePackage = gamePackage;
        }
    }

    /** Tickets invalidate queued exits when the game returns or a newer user choice arrives. */
    static final class ExitGate {
        private long generation;
        private boolean foreground;

        synchronized long onGameMode(boolean foreground) {
            this.foreground = foreground;
            return ++generation;
        }

        synchronized long invalidate() {
            return ++generation;
        }

        synchronized boolean canExit(long ticket) {
            return !foreground && generation == ticket;
        }
    }

    static final class ExitDeadline {
        private long dueElapsedMs;
        private boolean confirmed;

        synchronized void reset() {
            dueElapsedMs = 0L;
            confirmed = false;
        }

        synchronized boolean update(long now, long delay, boolean official) {
            long next = now + delay;
            if (dueElapsedMs != 0L && !(official && !confirmed)
                    && next >= dueElapsedMs - 500L) return false;
            dueElapsedMs = next;
            confirmed |= official;
            return true;
        }

        synchronized boolean expired(long now) {
            return dueElapsedMs != 0L && now >= dueElapsedMs;
        }
    }

    public GameLeaController(Context context, BluetoothLeAudioBridge bridge) {
        this.context = context.getApplicationContext();
        this.bridge = bridge;
        loadPersistedState();
        observeOfficialPreference();
        worker.execute(this::recoverOwnedSession);
    }

    @Override
    public synchronized void onGameLatencyStateChanged(boolean lowLatency, boolean autoLea) {
        boolean changed = lowLatencyEnabled != lowLatency || autoLeaEnabled != autoLea;
        lowLatencyEnabled = lowLatency;
        autoLeaEnabled = autoLea;
        persistState();
        MLog.event("game.ll.state.recv", "lowLatency", lowLatency, "autoLea", autoLea);
        if (!changed) return; // Periodic host reports must not cancel an exit timer.
        long ticket = exitGate.invalidate();
        if (!lowLatency || !autoLea) {
            Session current = session;
            worker.execute(() -> {
                if (!lowLatencyEnabled) exitCurrentClassicLowLatency();
                if (!lowLatencyEnabled || !autoLeaEnabled) restore(current, "state_change");
            });
        } else if (gameActive) {
            manualOverride = false;
            ensureGameSession(0);
        } else {
            Session current = session;
            if (current != null) {
                current.exitTicket = ticket;
                if (current.exitStartedElapsedMs == 0L) {
                    current.exitStartedElapsedMs = SystemClock.elapsedRealtime();
                }
                current.exitDeadline.reset();
                worker.execute(() -> scheduleExit(current, ticket));
            }
        }
    }

    private void exitCurrentClassicLowLatency() {
        Object sm = invokeNoArg(gameService, "getActiveStateMachine");
        Object selectedMode = invokeNoArg(sm, "getLowLatencyCodecMode");
        int mode = selectedMode instanceof Number ? ((Number) selectedMode).intValue() : 0;
        if (mode > 0 && mode < 8) {
            invokeNoArg(sm, "exitLowLatency");
            MLog.event("game.ll.master.exit", "mode", mode);
        }
    }

    /** Called before applying an explicit user LE Audio request. */
    @Override
    public synchronized void onManualLeAudioChanged(String mac, boolean enable) {
        Session current = session;
        if (current == null) {
            String activeMac = resolveActiveMac(gameService);
            if (gameActive && activeMac != null && activeMac.equalsIgnoreCase(mac)) {
                manualOverride = true;
                exitGate.invalidate();
            }
            return;
        }
        if (!current.mac.equalsIgnoreCase(mac)) return;
        manualOverride = true;
        exitGate.invalidate();
        current.suppress = false;
        session = null;
        worker.execute(() -> clearOwnedSession(current.mac));
        MLog.event("game.ll.manual_override", "enable", enable);
    }

    /** Bootstrap a service whose current game state predates hook/controller installation. */
    public void onGameServiceReady(Object serviceInstance) {
        if (serviceInstance == null) return;
        gameService = serviceInstance;
        // A late-installed hook may find classic LL already active. A persisted OFF choice
        // must restore it as well as blocking the next selection.
        if (!lowLatencyEnabled) worker.execute(this::exitCurrentClassicLowLatency);
        Boolean current = readBooleanField(serviceInstance, "mGameMode");
        if (current != null) {
            onGameModeChanged(current, serviceInstance);
            // The hook posts this reconciliation behind the official handler message. It
            // can also shorten the deadline when the background game process actually dies.
            Session target = session;
            if (!current && target != null && target.exitTicket != 0L) {
                worker.execute(() -> scheduleExit(target, target.exitTicket, true));
            }
        }
    }

    /** Accept only an observed death of the tracked main PID, not another process of its UID. */
    public synchronized void onGameProcessDied(int pid, int uid, Object serviceInstance) {
        Session target = session;
        if (target == null) return;
        Object process = readField(readField(serviceInstance, "mUtil"), "mLastGamePackageProcess");
        Object gamePid = readField(process, "mPid");
        Object gameUid = readField(process, "mUid");
        Object pkg = readField(process, "mGamePackageName");
        if (!(gamePid instanceof Number) || ((Number) gamePid).intValue() != pid
                || !(gameUid instanceof Number) || ((Number) gameUid).intValue() != uid
                || target.gamePackage == null || !target.gamePackage.equals(pkg)) return;
        if (target.mainProcessDiedElapsedMs == 0L) {
            target.mainProcessDiedElapsedMs = SystemClock.elapsedRealtime();
        }
        if (!gameActive && target.exitTicket != 0L) {
            worker.execute(() -> scheduleExit(target, target.exitTicket));
        }
    }

    /** Scenario callbacks never block on profile proxy acquisition or reconnect work. */
    public synchronized void onGameModeChanged(boolean isGame, Object serviceInstance) {
        if (serviceInstance != null) gameService = serviceInstance;
        boolean changed = gameActive != isGame;
        if (!changed) {
            if (isGame) ensureGameSession(0);
            return;
        }
        gameActive = isGame;
        long ticket = exitGate.onGameMode(isGame);
        MLog.event("game.ll.mode", "game", isGame,
                "lowLatency", lowLatencyEnabled, "autoLea", autoLeaEnabled);
        if (isGame) {
            manualOverride = false;
            if (session != null) {
                session.exitDeadline.reset();
                session.exitStartedElapsedMs = 0L;
                session.mainProcessDiedElapsedMs = 0L;
            }
            ensureGameSession(0);
        } else {
            Session current = session;
            if (current != null) {
                current.exitTicket = ticket;
                current.exitStartedElapsedMs = SystemClock.elapsedRealtime();
                current.exitDeadline.reset();
                // Give the platform handler the first chance to refresh its game timer.
                worker.schedule(() -> scheduleExit(current, ticket), 250L, TimeUnit.MILLISECONDS);
            }
        }
    }

    private synchronized void ensureGameSession(int attempt) {
        if (!gameActive || !lowLatencyEnabled || !autoLeaEnabled || manualOverride) return;
        Session current = session;
        if (current != null) {
            String pkg = currentGamePackage();
            if (pkg != null) current.gamePackage = pkg;
            worker.execute(() -> startAutoLea(current));
            return;
        }
        String mac = resolveActiveMac(gameService);
        if (mac == null) {
            if (attempt < 3) {
                worker.schedule(() -> ensureGameSession(attempt + 1), 700L, TimeUnit.MILLISECONDS);
            } else {
                MLog.event("game.ll.auto.skip", "reason", "no_active_device");
            }
            return;
        }
        Session created = new Session(mac, currentGamePackage());
        session = created; // Block classic fallback before the official enter message runs.
        worker.execute(() -> startAutoLea(created));
    }

    private void startAutoLea(Session target) {
        if (session != target || !gameActive || !lowLatencyEnabled || !autoLeaEnabled) return;
        String official = readOfficialGameLowLatencyPref(target.gamePackage);
        if ("off".equals(official)) {
            restore(target, "official_off");
            MLog.event("game.ll.auto.skip", "reason", "official_off");
            return;
        }
        if (target.started) return;
        target.started = true;
        if (!bridge.isLeAudioSupportedForGame(target.mac)) {
            MLog.event("game.ll.auto.skip", "reason", "lea_not_supported");
            return; // Keep the existing classic codec for the rest of this game session.
        }
        Boolean enabledBefore = bridge.readLeAudioEnabledForGame(target.mac);
        if (enabledBefore == null) {
            MLog.event("game.ll.auto.skip", "reason", "enablement_unknown");
            return;
        }
        if (bridge.isLeAudioConnectedNow(target.mac)) {
            target.ready = true;
            MLog.event("game.ll.auto.skip", "reason", "lea_already_connected");
            return;
        }
        if (!enabledBefore) {
            // Journal before mutation: restarting bluetooth must not leave temporary LEA on.
            if (!saveOwnedSession(target.mac)) {
                MLog.event("game.ll.auto.skip", "reason", "journal_failed");
                return;
            }
            target.enabledLeaByUs = true;
        }
        if (session != target || !lowLatencyEnabled || !autoLeaEnabled) {
            restore(target, "start_cancelled");
            return;
        }
        boolean applied = bridge.setLeAudioEnabledForGame(target.mac, true,
                () -> session == target && gameActive && lowLatencyEnabled
                        && autoLeaEnabled && !manualOverride);
        MLog.event("game.ll.auto.start", "mac", redact(target.mac),
                "applied", applied, "official", official, "owned", target.enabledLeaByUs);
        if (!applied) {
            restore(target, "start_failed");
            return;
        }
        worker.schedule(() -> checkBringup(target, false), 2_500L, TimeUnit.MILLISECONDS);
        worker.schedule(() -> checkBringup(target, true), BRINGUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    private void checkBringup(Session target, boolean deadline) {
        if (session != target) return;
        if (bridge.isLeAudioConnectedNow(target.mac)) {
            target.ready = true;
            MLog.event("game.ll.auto.connected", "mac", redact(target.mac));
        } else if (deadline && !target.ready) {
            MLog.event("game.ll.auto.timeout", "mac", redact(target.mac));
            restore(target, "bringup_timeout");
        }
    }

    private void scheduleExit(Session target, long ticket) {
        scheduleExit(target, ticket, false);
    }

    private void scheduleExit(Session target, long ticket, boolean officialUpdated) {
        if (session != target || target.exitTicket != ticket || !exitGate.canExit(ticket)) return;
        Object util = readField(gameService, "mUtil");
        boolean died = target.mainProcessDiedElapsedMs != 0L;
        Object configured = util == null ? null : readField(util.getClass(), died
                ? "sLowLatencyExitDelay_GameDied" : "sLowLatencyExitDelay_GameBackgroud");
        long policy = configured instanceof Number ? ((Number) configured).longValue() : 0L;
        long started = died ? target.mainProcessDiedElapsedMs : target.exitStartedElapsedMs;
        long now = SystemClock.elapsedRealtime();
        // The vendor getter depends on mutable foreground/SCO/UID-death bookkeeping and can
        // report zero for a live game. Reuse its configured duration, never its internal timer.
        long delay = exitGraceRemaining(now, started, policy, died);
        if (!target.exitDeadline.update(now, delay, configured instanceof Number)) return;
        MLog.event("game.ll.exit.schedule", "delayMs", delay,
                "source", died ? "main_process_died" : "background_grace",
                "officialPolicy", configured instanceof Number);
        worker.schedule(() -> {
            if (session == target && exitGate.canExit(ticket)
                    && target.exitDeadline.expired(SystemClock.elapsedRealtime())) {
                restore(target, "game_exit");
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    static long exitGraceRemaining(long now, long started, long configuredMs, boolean mainDied) {
        long policy = configuredMs > 0L ? configuredMs : mainDied ? 10_000L : BACKGROUND_EXIT_DELAY_MS;
        policy = Math.max(2_000L, Math.min(policy, 60_000L));
        long elapsed = started > 0L && started <= now ? now - started : 0L;
        return Math.max(0L, policy - elapsed);
    }

    private void restore(Session target, String reason) {
        if (target == null || session != target) return;
        if (!shouldRestore(target, reason)) return;
        boolean holdClassic = gameActive && lowLatencyEnabled && autoLeaEnabled
                && ("start_failed".equals(reason) || "bringup_timeout".equals(reason));
        if (!target.enabledLeaByUs) {
            finishSession(target, holdClassic);
            MLog.event("game.ll.restore.skip", "reason", reason);
            return;
        }
        boolean ok = bridge.setLeAudioEnabledForGame(target.mac, false,
                () -> shouldRestore(target, reason));
        MLog.event("game.ll.restore", "reason", reason, "mac", redact(target.mac), "ok", ok);
        if (ok) {
            clearOwnedSession(target.mac);
            target.enabledLeaByUs = false;
            finishSession(target, holdClassic);
            if ("game_exit".equals(reason) && gameActive) ensureGameSession(0);
        } else if (shouldRestore(target, reason) && ++target.restoreAttempts < 3) {
            worker.schedule(() -> {
                if ("game_exit".equals(reason) && gameActive) return;
                if ("state_change".equals(reason) && lowLatencyEnabled && autoLeaEnabled) return;
                restore(target, reason);
            }, 2_000L, TimeUnit.MILLISECONDS);
        }
    }

    private boolean shouldRestore(Session target, String reason) {
        if (session != target || manualOverride) return false;
        if ("game_exit".equals(reason)) {
            return !gameActive && exitGate.canExit(target.exitTicket);
        }
        if ("state_change".equals(reason)) return !lowLatencyEnabled || !autoLeaEnabled;
        if ("official_off".equals(reason)) {
            return "off".equals(readOfficialGameLowLatencyPref(target.gamePackage));
        }
        if ("process_recovery".equals(reason)) {
            return !gameActive || !lowLatencyEnabled || !autoLeaEnabled;
        }
        return true;
    }

    private synchronized void finishSession(Session target, boolean holdClassic) {
        target.suppress = holdClassic;
        if (!holdClassic && session == target) session = null;
    }

    /** Blocks new LL entries; the user's normal LE Audio playback is not a LL selection. */
    public Object filterLowLatencySelection(Object stateMachine, Object selected) {
        if (selected == null) return null;
        int mode = codecMode(selected);
        if (mode <= 0) return selected;
        if (!lowLatencyEnabled) {
            logSuppress(stateMachine, "master_off", mode);
            return null;
        }
        Session current = session;
        if (mode >= 8) {
            return selected;
        }
        if (current != null && current.suppress) {
            Object device = invokeNoArg(stateMachine, "getDevice");
            if (device instanceof BluetoothDevice
                    && !current.mac.equalsIgnoreCase(((BluetoothDevice) device).getAddress())) {
                return selected;
            }
            // Do not reopen SBC fallback on connection success or an asynchronous timeout.
            logSuppress(stateMachine, "auto_lea_session", mode);
            return null;
        }
        return selected;
    }

    private void logSuppress(Object stateMachine, String reason, int mode) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastSuppressLogMs < SUPPRESS_LOG_INTERVAL_MS) return;
        lastSuppressLogMs = now;
        Object device = invokeNoArg(stateMachine, "getDevice");
        MLog.event("game.ll.suppress", "reason", reason, "mode", mode,
                "device", device instanceof BluetoothDevice
                        ? redact(((BluetoothDevice) device).getAddress()) : "?");
    }

    private void observeOfficialPreference() {
        try {
            context.getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(KEY_OFFICIAL_LOW_LATENCY_STATE), false,
                    new ContentObserver(null) {
                        @Override public void onChange(boolean selfChange) {
                            worker.execute(() -> {
                                Session current = session;
                                if (current != null && "off".equals(
                                        readOfficialGameLowLatencyPref(current.gamePackage))) {
                                    restore(current, "official_off");
                                } else if (gameActive && current == null) {
                                    ensureGameSession(0);
                                }
                            });
                        }
                    });
        } catch (Throwable t) {
            MLog.w("game.ll.official observer unavailable", t);
        }
    }

    private String currentGamePackage() {
        try {
            String pkg = Settings.Global.getString(context.getContentResolver(), KEY_CURRENT_GAME_PACKAGE);
            if (pkg == null || pkg.trim().isEmpty()) {
                Object remembered = invokeNoArg(readField(gameService, "mUtil"), "getGamemodeAppPackage");
                if (remembered instanceof String) pkg = (String) remembered;
            }
            return pkg == null || pkg.trim().isEmpty() ? null : pkg.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private String readOfficialGameLowLatencyPref(String gamePkg) {
        try {
            String value = Settings.Global.getString(context.getContentResolver(), KEY_OFFICIAL_LOW_LATENCY_STATE);
            Object cached = readField(gameService, "mGameAppUserPreferMap");
            return resolveOfficialGamePreference(gamePkg, value, cached);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    static String resolveOfficialGamePreference(String gamePkg, String value, Object cached) {
        if (gamePkg == null || gamePkg.isEmpty()) return "unknown";
        int separator = value == null ? -1 : value.indexOf(':');
        if (separator > 0 && gamePkg.equalsIgnoreCase(value.substring(0, separator).trim())) {
            String flag = value.substring(separator + 1).trim();
            if ("0".equals(flag)) return "off";
            if ("1".equals(flag)) return "on";
        }
        // The Settings channel is a single slot. The service retains preferences for
        // earlier games. The current slot wins over a map whose observer is still queued.
        if (cached instanceof Map) {
            Object flag = ((Map<?, ?>) cached).get(gamePkg);
            if (flag instanceof Boolean) return (Boolean) flag ? "on" : "off";
        }
        return "unknown";
    }

    private void recoverOwnedSession() {
        try {
            Session recovered = session;
            if (recovered == null || !recovered.enabledLeaByUs) return;
            synchronized (this) {
                if (session != recovered) return;
                if (gameService == null) {
                    gameActive = Settings.Global.getInt(context.getContentResolver(), "debug_gamemode_value", 0) != 0;
                    exitGate.onGameMode(gameActive);
                }
            }
            MLog.event("game.ll.recover", "game", gameActive);
            if (!gameActive || !lowLatencyEnabled || !autoLeaEnabled) restore(recovered, "process_recovery");
            else worker.schedule(() -> checkBringup(recovered, true), BRINGUP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            MLog.w("game.ll.recover failed", t);
        }
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private boolean saveOwnedSession(String mac) {
        try {
            return prefs().edit().putString(KEY_OWNED_MAC, mac).commit();
        } catch (Throwable t) {
            MLog.w("game.ll.journal failed", t);
            return false;
        }
    }

    private void clearOwnedSession(String mac) {
        try {
            if (mac.equalsIgnoreCase(prefs().getString(KEY_OWNED_MAC, ""))) {
                prefs().edit().remove(KEY_OWNED_MAC).commit();
            }
        } catch (Throwable t) {
            MLog.w("game.ll.journal clear failed", t);
        }
    }

    private void loadPersistedState() {
        try {
            lowLatencyEnabled = prefs().getBoolean(KEY_LOW_LATENCY, true);
            autoLeaEnabled = prefs().getBoolean(KEY_AUTO_LEA, false);
            String ownedMac = prefs().getString(KEY_OWNED_MAC, null);
            if (ownedMac != null && !ownedMac.isEmpty()) {
                // Claim the saved ownership before exposing this controller to hook callbacks.
                Session recovered = new Session(ownedMac, currentGamePackage());
                recovered.started = true;
                recovered.enabledLeaByUs = true;
                session = recovered;
            }
            MLog.event("game.ll.state.loaded", "lowLatency", lowLatencyEnabled, "autoLea", autoLeaEnabled);
        } catch (Throwable t) {
            MLog.w("game.ll.state load failed", t);
        }
    }

    private void persistState() {
        try {
            prefs().edit().putBoolean(KEY_LOW_LATENCY, lowLatencyEnabled).putBoolean(KEY_AUTO_LEA, autoLeaEnabled).apply();
        } catch (Throwable t) {
            MLog.w("game.ll.state persist failed", t);
        }
    }

    private static String resolveActiveMac(Object serviceInstance) {
        Object device = invokeNoArg(invokeNoArg(serviceInstance, "getActiveStateMachine"), "getDevice");
        return device instanceof BluetoothDevice ? ((BluetoothDevice) device).getAddress() : null;
    }

    private static int codecMode(Object codec) {
        Object value = invokeNoArg(codec, "getLowLatencyCodecMode");
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static Object invokeNoArg(Object target, String name) {
        if (target == null) return null;
        try {
            Class<?> cls = target.getClass();
            while (cls != null && cls != Object.class) {
                for (Method method : cls.getDeclaredMethods()) {
                    if (method.getName().equals(name) && method.getParameterCount() == 0) {
                        method.setAccessible(true);
                        return method.invoke(target);
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object readField(Object target, String name) {
        if (target == null) return null;
        Class<?> cls = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        while (cls != null && cls != Object.class) {
            try {
                Field field = cls.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target instanceof Class<?> ? null : target);
            } catch (NoSuchFieldException ignored) {
                cls = cls.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Boolean readBooleanField(Object target, String name) {
        Object value = readField(target, name);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static String redact(String mac) {
        return mac == null || mac.length() < 5 ? "??" : mac.substring(0, 2) + "**" + mac.substring(mac.length() - 2);
    }
}
