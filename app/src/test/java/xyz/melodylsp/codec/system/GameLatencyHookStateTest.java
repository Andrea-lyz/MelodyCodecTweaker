package xyz.melodylsp.codec.system;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class GameLatencyHookStateTest {
    public static class Factory {
    }

    public static final class Extension extends Factory {
    }

    public static final class LaterExtension extends Factory {
    }

    public interface FactoryApi {
    }

    @Test
    public void repeatedFactoryReadsDoNotRestartInstallationOrIssueReporting() {
        GameLatencyHookState state = new GameLatencyHookState();
        Extension factory = new Extension();
        assertTrue(state.captureFactory(Factory.class, factory));
        assertTrue(state.reportIssueOnce("service", "class_missing"));
        for (int i = 0; i < 100; i++) {
            assertFalse(state.captureFactory(Factory.class, factory));
            assertFalse(state.captureFactory(Factory.class, new Extension()));
            assertFalse(state.reportIssueOnce("service", "class_missing"));
        }
    }

    @Test
    public void plainFallbackAndInvalidFactoryResultsDoNotClaimDexReady() {
        GameLatencyHookState state = new GameLatencyHookState();
        assertFalse(state.captureFactory(Factory.class, new Factory()));
        assertFalse(state.captureFactory(Factory.class, null));
        assertFalse(state.captureFactory(Factory.class, new Object()));
    }

    @Test
    public void capturesTheReturnedImplementationLoader() {
        GameLatencyHookState state = new GameLatencyHookState();
        ClassLoader extensionLoader = new ClassLoader(FactoryApi.class.getClassLoader()) { };
        Object factory = Proxy.newProxyInstance(extensionLoader, new Class<?>[]{FactoryApi.class},
                (proxy, method, args) -> null);
        assertTrue(state.captureFactory(FactoryApi.class, factory));
        assertSame(extensionLoader, state.factoryClassLoader());
    }

    @Test
    public void extensionReadyDuringAnAttemptGetsOneFollowUp() {
        GameLatencyHookState state = new GameLatencyHookState();
        assertTrue(state.requestAttempt());
        assertTrue(state.captureFactory(Factory.class, new Extension()));
        assertFalse(state.requestAttempt());
        assertTrue(state.finishAttempt(false));
        assertFalse(state.finishAttempt(false));
        assertTrue(state.requestAttempt());
        assertFalse(state.finishAttempt(false));
    }

    @Test
    public void concurrentWakeupsShareOneWorkerAndOnePendingPass() throws Exception {
        GameLatencyHookState state = new GameLatencyHookState();
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 80; i++) {
                futures.add(workers.submit(() -> {
                    start.await();
                    if (state.requestAttempt()) accepted.incrementAndGet();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(5, TimeUnit.SECONDS);
            assertEquals(1, accepted.get());
            assertTrue(state.finishAttempt(false));
            assertFalse(state.finishAttempt(false));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    public void successfulInstallationDropsPendingRetries() {
        GameLatencyHookState state = new GameLatencyHookState();
        assertTrue(state.requestAttempt());
        assertFalse(state.requestAttempt());
        assertFalse(state.finishAttempt(true));
        assertFalse(state.requestAttempt());
        assertFalse(state.captureFactory(Factory.class, new LaterExtension()));
        state.exhaustRetrySchedule();
        assertFalse(state.reportCompatOnce());
    }

    @Test
    public void lateExtensionCanRecoverAfterTheStartupBudgetExpires() {
        GameLatencyHookState state = new GameLatencyHookState();
        assertTrue(state.captureFactory(Factory.class, new Extension()));
        state.exhaustRetrySchedule();
        assertTrue(state.reportCompatOnce());
        assertFalse(state.reportCompatOnce());
        assertTrue(state.reportIssueOnce("service", "class_missing"));
        assertTrue(state.captureFactory(Factory.class, new LaterExtension()));
        assertTrue(state.reportIssueOnce("service", "class_missing"));
        assertTrue(state.requestAttempt());
        assertFalse(state.finishAttempt(true));
        assertFalse(state.reportCompatOnce());
    }

    @Test
    public void rejectedQueueSubmissionDoesNotWedgeTheGate() {
        GameLatencyHookState state = new GameLatencyHookState();
        assertTrue(state.requestAttempt());
        state.cancelQueuedAttempt();
        assertTrue(state.requestAttempt());
        assertFalse(state.finishAttempt(false));
    }
}
