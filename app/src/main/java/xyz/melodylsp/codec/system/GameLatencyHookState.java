package xyz.melodylsp.codec.system;

import java.util.HashSet;
import java.util.Set;

/** Coalesces extension-ready signals without losing a signal during an installation attempt. */
final class GameLatencyHookState {
    private Class<?> factoryImplementation;
    private ClassLoader factoryClassLoader;
    private boolean queued;
    private boolean pending;
    private boolean complete;
    private boolean retryScheduleExhausted;
    private boolean compatReported;
    private final Set<String> reportedIssues = new HashSet<>();

    synchronized boolean captureFactory(Class<?> factoryClass, Object instance) {
        if (complete || instance == null || !factoryClass.isInstance(instance)
                || instance.getClass() == factoryClass) {
            return false; // A plain factory is the vendor's no-extension fallback.
        }
        Class<?> implementation = instance.getClass();
        ClassLoader loader = implementation.getClassLoader();
        if (loader == null) loader = factoryClass.getClassLoader();
        if (factoryImplementation == implementation && factoryClassLoader == loader) return false;
        factoryImplementation = implementation;
        factoryClassLoader = loader;
        reportedIssues.clear();
        compatReported = false;
        return true;
    }

    synchronized ClassLoader factoryClassLoader() {
        return factoryClassLoader;
    }

    synchronized boolean requestAttempt() {
        if (complete) return false;
        if (queued) {
            pending = true;
            return false;
        }
        queued = true;
        return true;
    }

    /** True retains ownership of the worker for one coalesced follow-up attempt. */
    synchronized boolean finishAttempt(boolean installed) {
        complete |= installed;
        if (!complete && pending) {
            pending = false;
            return true;
        }
        queued = false;
        pending = false;
        return false;
    }

    synchronized void cancelQueuedAttempt() {
        queued = false;
        pending = false;
    }

    synchronized boolean reportIssueOnce(String target, String reason) {
        return reportedIssues.add(target + ':' + reason);
    }

    synchronized void exhaustRetrySchedule() {
        retryScheduleExhausted = true;
    }

    synchronized boolean reportCompatOnce() {
        if (complete || pending || !retryScheduleExhausted || compatReported) return false;
        compatReported = true;
        return true;
    }
}
