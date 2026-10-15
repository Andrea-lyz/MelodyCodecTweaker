package xyz.melodylsp.codec.leaudio;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class LeAudioPlaybackStateTest {

    @Test
    public void exclusiveDevicesKeepTheirExistingBringupSequence() {
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                false, true, false, LeAudioIpc.MEDIA_PROFILE_LE_AUDIO));
        assertEquals(LeAudioPlaybackState.CONNECTING, LeAudioPlaybackState.resolve(
                true, false, false, LeAudioIpc.MEDIA_PROFILE_UNKNOWN));
        assertEquals(LeAudioPlaybackState.LE_ACTIVE, LeAudioPlaybackState.resolve(
                true, true, false, LeAudioIpc.MEDIA_PROFILE_UNKNOWN));
    }

    @Test
    public void coexistPermissionWithoutLeConnectionLeavesClassicControlsAvailable() {
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                true, false, true, LeAudioIpc.MEDIA_PROFILE_UNKNOWN));
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                true, false, true, LeAudioIpc.MEDIA_PROFILE_A2DP));
    }

    @Test
    public void simultaneousProfileConnectionsFollowTheSelectedMediaTransport() {
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                true, true, true, LeAudioIpc.MEDIA_PROFILE_A2DP));
        assertEquals(LeAudioPlaybackState.LE_ACTIVE, LeAudioPlaybackState.resolve(
                true, true, true, LeAudioIpc.MEDIA_PROFILE_LE_AUDIO));
    }

    @Test
    public void unconfirmedOrOtherDeviceRouteCannotClaimLc3() {
        assertEquals(LeAudioPlaybackState.ROUTE_UNKNOWN, LeAudioPlaybackState.resolve(
                true, true, true, LeAudioIpc.MEDIA_PROFILE_UNKNOWN));
        assertEquals(LeAudioPlaybackState.ROUTE_UNKNOWN, LeAudioPlaybackState.resolve(
                true, true, true, LeAudioIpc.MEDIA_PROFILE_OTHER));
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                true, false, true, LeAudioIpc.MEDIA_PROFILE_LE_AUDIO));
    }

    @Test
    public void gameExitCanReturnToClassicWithoutTurningOffLePermission() {
        assertEquals(LeAudioPlaybackState.LE_ACTIVE, LeAudioPlaybackState.resolve(
                true, true, true, LeAudioIpc.MEDIA_PROFILE_LE_AUDIO));
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                true, true, true, LeAudioIpc.MEDIA_PROFILE_A2DP));
        assertEquals(LeAudioPlaybackState.CLASSIC, LeAudioPlaybackState.resolve(
                true, false, true, LeAudioIpc.MEDIA_PROFILE_A2DP));
    }
}
