package xyz.melodylsp.codec.diag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RootBluetoothLogCaptureTest {

    @Test
    public void sessionIdRejectsShellMetacharacters() {
        assertTrue(RootBluetoothLogCapture.isValidSessionId("20260805-225439"));
        assertFalse(RootBluetoothLogCapture.isValidSessionId("20260805-225439;id"));
        assertFalse(RootBluetoothLogCapture.isValidSessionId("../../data"));
        assertFalse(RootBluetoothLogCapture.isValidSessionId(""));
        assertFalse(RootBluetoothLogCapture.isValidSessionId(null));
    }

    @Test
    public void startCommandIsBoundedFilteredAndRotated() {
        String command = RootBluetoothLogCapture.buildStartCommand("20260805-225439");

        assertTrue(command.contains("toybox timeout 1860"));
        assertTrue(command.contains("-f \"$LOG\" -r 1024 -n 2"));
        assertTrue(command.contains("bluetooth-a2dp:V"));
        assertTrue(command.contains("BluetoothQualityReportNativeInterface:V"));
        assertTrue(command.contains("bt-20260805-225439.log"));
        assertTrue(command.contains("capture_already_running"));
        assertFalse(command.contains("logcat -d"));
        assertFalse(command.contains("logcat -c"));
        assertFalse(command.contains("logcat -G"));
    }

    @Test
    public void stopCommandValidatesExactPidBeforeKilling() {
        String command = RootBluetoothLogCapture.buildStopCommand("20260805-225439");

        assertTrue(command.contains("/proc/$P/cmdline"));
        assertTrue(command.contains("*logcat*\"$LOG\"*"));
        assertTrue(command.contains("kill -TERM"));
        assertTrue(command.contains("kill -KILL"));
        assertTrue(command.contains("bt-20260805-225439.pid"));
        assertFalse(command.contains("pkill"));
        assertFalse(command.contains("killall"));
    }

    @Test
    public void readCommandMergesRotationsOldestFirst() {
        String command = RootBluetoothLogCapture.buildReadCommand("20260805-225439");
        int oldest = command.indexOf("\"$LOG.2\"");
        int middle = command.indexOf("\"$LOG.1\"");
        int newest = command.indexOf("\"$LOG\"");

        assertTrue(oldest >= 0);
        assertTrue(middle > oldest);
        assertTrue(newest > middle);
    }

    @Test
    public void cleanupTargetsOnlyValidatedSessionFiles() {
        String command = RootBluetoothLogCapture.buildCleanupCommand("20260805-225439");

        assertTrue(command.contains("bt-20260805-225439.log"));
        assertTrue(command.contains("bt-20260805-225439.pid"));
        assertFalse(command.contains("*"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void commandBuilderRejectsInvalidSession() {
        RootBluetoothLogCapture.buildStartCommand("20260805-225439;rm");
    }

    @Test
    public void persistentAndSnapshotLogsAreMergedWithoutDuplicates() {
        String merged = FeedbackCollector.mergeUniqueLogLines(
                "early-a\nearly-b\n",
                "early-b\nlate-c\n");

        assertEquals("early-a\nearly-b\nlate-c\n", merged);
    }
}
