package xyz.melodylsp.codec.bridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class GameModeCodecPolicyTest {
    @Test
    public void knownSbcEntryCommandsAreSuppressedOnlyWhenMasterIsOff() {
        assertTrue(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, 3L));
        assertTrue(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, 2L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(true, 1, 0, 3L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(true, 1, 0, 2L));
    }

    @Test
    public void exitCommandMustPassAndMustNotKeepAacReplaySuppressed() {
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, 5L));
        assertFalse(GameModeCodecPolicy.isSbcLowLatencyEntry(0, 5L));
    }

    @Test
    public void normalSbcAndExplicitAacChoicesRemainAvailable() {
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, 0L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 1, 0L));
        assertFalse(GameModeCodecPolicy.isSbcLowLatencyEntry(1, 3L));
        assertFalse(GameModeCodecPolicy.isSbcLowLatencyEntry(19, 3L));
    }

    @Test
    public void mixedCodecListsAndUnknownMarkersArePreserved() {
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 2, 0, 3L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 0, 0, 3L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, 1L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, 4L));
        assertFalse(GameModeCodecPolicy.shouldBlockSbcEntry(false, 1, 0, -1L));
    }
}
