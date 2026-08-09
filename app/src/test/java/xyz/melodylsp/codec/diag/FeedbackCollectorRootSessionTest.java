package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import org.junit.Test;

/** Unit tests for the root-backed session gate that protects feedback packaging. */
public final class FeedbackCollectorRootSessionTest {

    @Test
    public void rejectsMissingOrUnrootedSessions() {
        FakePrefs empty = new FakePrefs(null, "", "");
        assertFalse(FeedbackCollector.isValidRootBackedSession(empty));

        FakePrefs noStatus = new FakePrefs("20260807-120000", "", "20260807-120000");
        assertFalse(FeedbackCollector.isValidRootBackedSession(noStatus));

        FakePrefs unavailable = new FakePrefs("20260807-120000", "unavailable",
                "20260807-120000");
        assertFalse(FeedbackCollector.isValidRootBackedSession(unavailable));
    }

    @Test
    public void acceptsRootBackedSessionStates() {
        assertTrue(FeedbackCollector.isValidRootBackedSession(
                new FakePrefs("20260807-120000", "started", "20260807-120000")));
        assertTrue(FeedbackCollector.isValidRootBackedSession(
                new FakePrefs("20260807-120000", "collected", "20260807-120000")));
        assertTrue(FeedbackCollector.isValidRootBackedSession(
                new FakePrefs("20260807-120000", "stopped", "20260807-120000")));
    }

    @Test
    public void rejectsStaleCaptureFromEarlierSession() {
        // PR #10 review: capture state left over from a previous session must not mark the
        // current session as root-backed (fail-closed feedback packaging).
        assertFalse(FeedbackCollector.isValidRootBackedSession(
                new FakePrefs("20260809-010000", "collected", "20260807-120000")));
        assertFalse(FeedbackCollector.isValidRootBackedSession(
                new FakePrefs("20260809-010000", "started", "")));
    }

    private static final class FakePrefs implements SharedPreferences {
        private final String sessionId;
        private final String captureStatus;
        private final String captureSession;

        FakePrefs(String sessionId, String captureStatus, String captureSession) {
            this.sessionId = sessionId;
            this.captureStatus = captureStatus;
            this.captureSession = captureSession;
        }

        @Override
        public String getString(String key, String defValue) {
            if (DiagnosticEvents.KEY_SESSION_ID.equals(key)) return sessionId;
            if (RootBluetoothLogCapture.KEY_CAPTURE_STATUS.equals(key)) return captureStatus;
            if (RootBluetoothLogCapture.KEY_CAPTURE_SESSION.equals(key)) return captureSession;
            return defValue;
        }

        @Override
        public java.util.Map<String, ?> getAll() {
            return new java.util.HashMap<>();
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            return defValue;
        }

        @Override
        public int getInt(String key, int defValue) {
            return defValue;
        }

        @Override
        public long getLong(String key, long defValue) {
            return defValue;
        }

        @Override
        public java.util.Set<String> getStringSet(String key, java.util.Set<String> defValue) {
            return defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            return defValue;
        }

        @Override
        public boolean contains(String key) {
            return false;
        }

        @Override
        public Editor edit() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) {
        }
    }
}
