package xyz.melodylsp.codec.leaudio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BluetoothLeAudioBridgeTest {

    @Test
    public void reconnectGateCoalescesConcurrentRepairSeries() {
        BluetoothLeAudioBridge.ReconnectGate gate =
                new BluetoothLeAudioBridge.ReconnectGate();

        long generation = gate.tryStart("AA:BB:CC:DD:EE:FF", 1_000L, false);

        assertTrue(generation > 0L);
        assertTrue(gate.isCurrent("AA:BB:CC:DD:EE:FF", generation));
        assertTrue(gate.tryStart("AA:BB:CC:DD:EE:FF", 1_001L, true) < 0L);
    }

    @Test
    public void reconnectGateHonorsCooldownButExplicitEnableCanBypassIt() {
        BluetoothLeAudioBridge.ReconnectGate gate =
                new BluetoothLeAudioBridge.ReconnectGate();
        String mac = "AA:BB:CC:DD:EE:FF";
        long generation = gate.tryStart(mac, 1_000L, false);
        gate.finish(mac, generation, 2_000L, 12_000L);

        assertTrue(gate.tryStart(mac, 13_999L, false) < 0L);
        assertTrue(gate.tryStart(mac, 13_999L, true) > 0L);
    }

    @Test
    public void reconnectGateCancelInvalidatesDelayedAttempts() {
        BluetoothLeAudioBridge.ReconnectGate gate =
                new BluetoothLeAudioBridge.ReconnectGate();
        String mac = "AA:BB:CC:DD:EE:FF";
        long generation = gate.tryStart(mac, 1_000L, false);

        gate.cancel(mac);

        assertFalse(gate.isCurrent(mac, generation));
        assertTrue(gate.tryStart(mac, 1_001L, false) > generation);
    }
}
