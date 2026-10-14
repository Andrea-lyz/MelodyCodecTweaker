package xyz.melodylsp.codec.ui;

import android.app.Application;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.service.HookedTarget;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * App-side, read-only framework status. A bound service is not evidence that any package
 * has been hooked; running target states are reported exactly as supplied by the framework.
 */
public final class FrameworkStatusApplication extends Application {
    private static final Object LOCK = new Object();
    private static final List<XposedService> SERVICES = new ArrayList<>();
    private static final ExecutorService QUERIES = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "MelodyFrameworkStatus");
        thread.setDaemon(true);
        return thread;
    });

    // Only this immutable string crosses threads; callers never receive shared JSON objects.
    private static volatile String snapshotJson = emptySnapshot(false, false, "").toString();
    // All service selection and scheduling state below is guarded by LOCK.
    private static XposedService currentService;
    private static long serviceGeneration;
    private static boolean listenerRegistered;
    private static boolean queryScheduled;
    private static boolean queryRequested;
    private static boolean statusLogged;

    // Retain the listener strongly for the App process lifetime.
    private static final XposedServiceHelper.OnServiceListener LISTENER =
            new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService service) {
                    if (service == null) return;
                    synchronized (LOCK) {
                        removeServiceLocked(service);
                        SERVICES.add(service);
                        currentService = service;
                        serviceGeneration++;
                        snapshotJson = emptySnapshot(true, true, "").toString();
                        requestQueryLocked();
                    }
                }

                @Override
                public void onServiceDied(XposedService service) {
                    synchronized (LOCK) {
                        removeServiceLocked(service);
                        // A different framework's death must not clear the selected service.
                        if (service != currentService) return;
                        currentService = SERVICES.isEmpty()
                                ? null : SERVICES.get(SERVICES.size() - 1);
                        serviceGeneration++;
                        queryRequested = false;
                        boolean bound = currentService != null;
                        snapshotJson = emptySnapshot(bound, bound, "").toString();
                        if (bound) requestQueryLocked();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        synchronized (LOCK) {
            if (listenerRegistered) return;
            listenerRegistered = true;
        }
        try {
            XposedServiceHelper.registerListener(LISTENER);
        } catch (RuntimeException | LinkageError failure) {
            synchronized (LOCK) {
                if (currentService == null) {
                    snapshotJson = emptySnapshot(false, false,
                            diagnostic("register", failure)).toString();
                }
            }
        }
    }

    /** Returns an independent copy of the last snapshot, without making Binder calls. */
    public static JSONObject snapshot() {
        try {
            return new JSONObject(snapshotJson);
        } catch (JSONException failure) {
            return emptySnapshot(false, false, "snapshot:invalid_json");
        }
    }

    /** Requests a background read; concurrent requests are coalesced into one pending read. */
    public static void refresh() {
        synchronized (LOCK) {
            if (currentService == null) return;
            JSONObject loading = snapshot();
            put(loading, "loading", true);
            snapshotJson = loading.toString();
            requestQueryLocked();
        }
    }

    private static void removeServiceLocked(XposedService service) {
        for (int i = SERVICES.size() - 1; i >= 0; i--) {
            if (SERVICES.get(i) == service) SERVICES.remove(i);
        }
    }

    private static void requestQueryLocked() {
        queryRequested = true;
        if (queryScheduled) return;
        queryScheduled = true;
        QUERIES.execute(FrameworkStatusApplication::drainQueries);
    }

    private static void drainQueries() {
        while (true) {
            XposedService service;
            long generation;
            synchronized (LOCK) {
                if (!queryRequested || currentService == null) {
                    queryRequested = false;
                    queryScheduled = false;
                    return;
                }
                queryRequested = false;
                service = currentService;
                generation = serviceGeneration;
            }

            // Every framework getter, including API/metadata, runs on this single worker.
            JSONObject result = query(service);
            synchronized (LOCK) {
                // Discard results from a service that died or was replaced during the read.
                if (service != currentService || generation != serviceGeneration) continue;
                put(result, "loading", queryRequested);
                snapshotJson = result.toString();
                if (!statusLogged) {
                    statusLogged = true;
                    Log.i("MelodyCodecLsp", "evt=framework.status bound=true scope_count="
                            + result.optJSONArray("scopePackages").length()
                            + " target_count=" + result.optJSONArray("targets").length());
                }
            }
        }
    }

    private static JSONObject query(XposedService service) {
        JSONObject result = emptySnapshot(true, false, "");
        StringBuilder errors = new StringBuilder();
        int api = 0;
        try {
            api = service.getApiVersion();
            put(result, "api", api);
        } catch (RuntimeException | LinkageError failure) {
            appendError(errors, "api", failure);
        }
        try {
            put(result, "framework", nonNull(service.getFrameworkName()));
        } catch (RuntimeException | LinkageError failure) {
            appendError(errors, "framework", failure);
        }
        try {
            put(result, "frameworkVersion", nonNull(service.getFrameworkVersion()));
        } catch (RuntimeException | LinkageError failure) {
            appendError(errors, "version", failure);
        }
        try {
            List<String> packages = service.getScope();
            if (packages == null) throw new IllegalStateException("null scope");
            JSONArray scope = new JSONArray();
            for (String packageName : packages) {
                if (packageName != null) scope.put(packageName);
            }
            put(result, "scopePackages", scope);
        } catch (RuntimeException | LinkageError failure) {
            appendError(errors, "scope", failure);
        }
        if (api >= XposedService.API_102) {
            try {
                List<HookedTarget> runningTargets = service.getRunningTargets();
                if (runningTargets == null) throw new IllegalStateException("null targets");
                JSONArray targets = new JSONArray();
                for (HookedTarget target : runningTargets) {
                    JSONObject item = new JSONObject();
                    put(item, "process", nonNull(target.getProcessName()));
                    put(item, "pid", target.getPid());
                    put(item, "versionCode", target.getLoadedVersionCode());
                    HookedTarget.State state = target.getState();
                    put(item, "state", state == null ? "" : state.name());
                    targets.put(item);
                }
                put(result, "targets", targets);
                put(result, "targetsAvailable", true);
            } catch (RuntimeException | LinkageError failure) {
                appendError(errors, "targets", failure);
            }
        }
        put(result, "error", errors.toString());
        return result;
    }

    private static JSONObject emptySnapshot(boolean bound, boolean loading, String error) {
        JSONObject result = new JSONObject();
        put(result, "bound", bound);
        put(result, "framework", "");
        put(result, "frameworkVersion", "");
        put(result, "api", 0);
        put(result, "scopePackages", new JSONArray());
        put(result, "targets", new JSONArray());
        put(result, "targetsAvailable", false);
        put(result, "loading", loading);
        put(result, "error", error);
        return result;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static String diagnostic(String operation, Throwable failure) {
        String type = failure.getClass().getSimpleName();
        return operation + ":" + (type.isEmpty() ? "error" : type);
    }

    private static void appendError(StringBuilder errors, String operation, Throwable failure) {
        if (errors.length() > 0) errors.append(';');
        errors.append(diagnostic(operation, failure));
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException failure) {
            // Keys are constants and values are finite primitives, strings or JSON containers.
            throw new IllegalStateException("Invalid framework status JSON", failure);
        }
    }
}
