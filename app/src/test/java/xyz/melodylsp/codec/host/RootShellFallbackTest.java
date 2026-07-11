package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import xyz.melodylsp.codec.bridge.CodecRequest;
import xyz.melodylsp.codec.label.CodecLabelTable;

public final class RootShellFallbackTest {

    @Test
    public void ldacRequestOnlyStagesDeveloperOptionSettings() {
        CodecRequest request = request(
                CodecLabelTable.CODEC_LDAC,
                CodecLabelTable.LDAC_QUALITY_HIGH,
                1 << 3);

        List<String> commands = RootShellFallback.buildCommands(request);

        assertEquals(2, commands.size());
        assertEquals("cmd settings put global "
                        + SettingsGlobalFallback.KEY_LDAC_QUALITY + " 0",
                commands.get(0));
        assertEquals("cmd settings put global "
                        + SettingsGlobalFallback.KEY_SAMPLE_RATE + " 4",
                commands.get(1));
        assertFalse(containsAdapterMutation(commands));
    }

    @Test
    public void sampleRateOnlyRequestNeverRestartsBluetooth() {
        List<String> commands = RootShellFallback.buildCommands(request(
                CodecLabelTable.CODEC_AAC,
                0L,
                1 << 1));

        assertEquals(1, commands.size());
        assertTrue(commands.get(0).startsWith("cmd settings put global "));
        assertFalse(containsAdapterMutation(commands));
    }

    @Test
    public void unsupportedValuesProduceNoRootCommands() {
        List<String> commands = RootShellFallback.buildCommands(request(
                CodecLabelTable.CODEC_AAC,
                1234L,
                1 << 7));

        assertTrue(commands.isEmpty());
    }

    private static CodecRequest request(int codecType, long specific1, int sampleRate) {
        return new CodecRequest(
                "AA:BB:CC:DD:EE:FF",
                codecType,
                specific1,
                0L,
                0L,
                0L,
                sampleRate,
                0,
                0);
    }

    private static boolean containsAdapterMutation(List<String> commands) {
        for (String command : commands) {
            if (command.contains("bluetooth_manager")
                    || command.matches(".*\\b(enable|disable)\\b.*")) {
                return true;
            }
        }
        return false;
    }
}
