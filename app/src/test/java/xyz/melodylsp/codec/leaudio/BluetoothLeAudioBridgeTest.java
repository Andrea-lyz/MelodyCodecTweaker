package xyz.melodylsp.codec.leaudio;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import org.junit.Test;

public final class BluetoothLeAudioBridgeTest {

    @Test
    public void mediaRouteIsMatchedToTheRequestedBrAndLeAddresses() {
        String br = "AA:BB:CC:DD:EE:FF";
        String le = "11:22:33:44:55:66";
        assertEquals(LeAudioIpc.MEDIA_PROFILE_A2DP,
                BluetoothLeAudioBridge.mediaProfileForOutput(8, br.toLowerCase(),
                        Collections.singletonList(br), Collections.singletonList(le)));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_LE_AUDIO,
                BluetoothLeAudioBridge.mediaProfileForOutput(26, le,
                        Collections.singletonList(br), Collections.singletonList(le)));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_LE_AUDIO,
                BluetoothLeAudioBridge.mediaProfileForOutput(27, le,
                        Collections.singletonList(br), Collections.singletonList(le)));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_OTHER,
                BluetoothLeAudioBridge.mediaProfileForOutput(26, "00:00:00:00:00:00",
                        Collections.singletonList(br), Collections.singletonList(le)));
    }

    @Test
    public void missingRouteAddressAndNonBluetoothOutputCannotClaimThisHeadset() {
        assertEquals(LeAudioIpc.MEDIA_PROFILE_UNKNOWN,
                BluetoothLeAudioBridge.mediaProfileForOutput(26, "",
                        Collections.emptyList(), Collections.emptyList()));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_OTHER,
                BluetoothLeAudioBridge.mediaProfileForOutput(2, "",
                        Collections.emptyList(), Collections.emptyList()));
    }

    @Test
    public void fallbackRequiresOneProfileToBeExclusivelyConnected() {
        assertEquals(LeAudioIpc.MEDIA_PROFILE_A2DP,
                BluetoothLeAudioBridge.mediaProfileFromConnections(false, true));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_LE_AUDIO,
                BluetoothLeAudioBridge.mediaProfileFromConnections(true, false));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_UNKNOWN,
                BluetoothLeAudioBridge.mediaProfileFromConnections(true, true));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_UNKNOWN,
                BluetoothLeAudioBridge.mediaProfileFromConnections(true, null));
        assertEquals(LeAudioIpc.MEDIA_PROFILE_UNKNOWN,
                BluetoothLeAudioBridge.mediaProfileFromConnections(false, false));
    }

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

    @Test
    public void a2dpRestoreFromPreviousGameCannotRunAfterNewRequest() {
        BluetoothLeAudioBridge.A2dpRestoreGate gate =
                new BluetoothLeAudioBridge.A2dpRestoreGate();
        String mac = "AA:BB:CC:DD:EE:FF";
        long restore = gate.advance(mac);
        assertTrue(gate.mayRestore(mac, restore, false));

        long newGame = gate.advance(mac);

        assertFalse(gate.mayRestore(mac, restore, false));
        assertFalse(gate.mayRestore(mac, newGame, true));
    }

    @Test
    public void a2dpRestoreRequiresKnownDisabledLeAudioAndKeepsDevicesIndependent() {
        BluetoothLeAudioBridge.A2dpRestoreGate gate =
                new BluetoothLeAudioBridge.A2dpRestoreGate();
        String mac = "AA:BB:CC:DD:EE:FF";
        long restore = gate.advance(mac);
        gate.advance("11:22:33:44:55:66");

        assertFalse(gate.mayRestore(mac, restore, null));
        assertFalse(gate.mayRestore(mac, restore, true));
        assertTrue(gate.mayRestore(mac, restore, false));
    }

    @Test
    public void unreadablePolicyIsUnknownUnlessEnabledApiReturnsAState() {
        assertNull(BluetoothLeAudioBridge.enabledFromReadback(null, null));
        assertNull(BluetoothLeAudioBridge.enabledFromReadback(-1, null));
        assertEquals(Boolean.FALSE,
                BluetoothLeAudioBridge.enabledFromReadback(-1, false));
        assertEquals(Boolean.TRUE,
                BluetoothLeAudioBridge.enabledFromReadback(null, true));
        assertEquals(Boolean.FALSE,
                BluetoothLeAudioBridge.enabledFromReadback(0, true));
        assertEquals(Boolean.TRUE,
                BluetoothLeAudioBridge.enabledFromReadback(100, false));
    }

    @Test
    public void disabledPrimaryDoesNotHideAnEnabledOrUnknownPeer() {
        assertEquals(Boolean.TRUE,
                BluetoothLeAudioBridge.anyLeAudioEnabled(Arrays.asList(false, true)));
        assertEquals(Boolean.TRUE,
                BluetoothLeAudioBridge.anyLeAudioEnabled(Arrays.asList(null, true)));
        assertNull(BluetoothLeAudioBridge.anyLeAudioEnabled(Arrays.asList(false, null)));
        assertNull(BluetoothLeAudioBridge.anyLeAudioEnabled(Collections.emptyList()));
        assertEquals(Boolean.FALSE,
                BluetoothLeAudioBridge.anyLeAudioEnabled(Arrays.asList(false, false)));
    }

    @Test
    public void capabilityRequiresAnOfficialLeAudioUuidAndLeOrDualType() {
        UUID ascs = UUID.fromString("0000184e-0000-1000-8000-00805f9b34fb");
        UUID has = UUID.fromString("00001854-0000-1000-8000-00805f9b34fb");
        UUID bass = UUID.fromString("0000184f-0000-1000-8000-00805f9b34fb");

        assertTrue(BluetoothLeAudioBridge.hasLeAudioCapability(2, new UUID[]{ascs}));
        assertTrue(BluetoothLeAudioBridge.hasLeAudioCapability(3, new UUID[]{has}));
        assertFalse(BluetoothLeAudioBridge.hasLeAudioCapability(1, new UUID[]{ascs}));
        assertFalse(BluetoothLeAudioBridge.hasLeAudioCapability(3, new UUID[]{bass}));
        assertFalse(BluetoothLeAudioBridge.hasLeAudioCapability(3, null));
    }

    @Test
    public void coexistSupportRequiresAnExplicitTrueForAnyIdentity() {
        assertEquals(Boolean.TRUE, BluetoothLeAudioBridge.coexistFromPrefs(
                Map.of("40:72:18:C6:E5:50", true, "40:72:18:C6:E0:3A", false),
                Arrays.asList("40:72:18:C6:E0:3A", "40:72:18:C6:E5:50")));
        assertEquals(Boolean.FALSE, BluetoothLeAudioBridge.coexistFromPrefs(
                Map.of("40:72:18:C6:E5:50", false),
                Arrays.asList("40:72:18:C6:E5:50")));
        assertEquals(Boolean.FALSE, BluetoothLeAudioBridge.coexistFromPrefs(
                Map.of(), Arrays.asList("40:72:18:C6:E5:50")));
    }

    @Test
    public void coexistSupportStaysUnknownWhenItCannotBeResolved() {
        assertNull(BluetoothLeAudioBridge.coexistFromPrefs(
                null, Arrays.asList("40:72:18:C6:E5:50")));
        assertNull(BluetoothLeAudioBridge.coexistFromPrefs(
                Map.of(), Collections.<String>emptyList()));
        assertEquals(Boolean.FALSE, BluetoothLeAudioBridge.coexistFromPrefs(
                Map.of("40:72:18:C6:E5:50", "true"),
                Arrays.asList("40:72:18:C6:E5:50")));
    }
}
