package xyz.melodylsp.codec.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

public class LhdcOffloadPathTest {

    @After
    public void tearDown() {
        LhdcOffloadPath.resetForTest();
    }

    @Test
    public void halAdvertisedAndStackEnabledIsOffload() {
        LhdcOffloadPath.setForTest(Boolean.TRUE, Boolean.TRUE);
        assertTrue(LhdcOffloadPath.isKnown());
        assertTrue(LhdcOffloadPath.isHalAdvertised());
        assertTrue(LhdcOffloadPath.isOffloaded());
    }

    @Test
    public void halAdvertisedWithoutResolvedStackFlagStillCountsAsOffload() {
        // Never resolved the stack flag: assume the DSP path, because the HAL lists LHDC.
        LhdcOffloadPath.setForTest(Boolean.TRUE, null);
        assertTrue(LhdcOffloadPath.isOffloaded());
        assertNull(LhdcOffloadPath.stackOffloadEnabled());
    }

    @Test
    public void stackFlagOffKeepsGovernanceOnTheSoftwarePath() {
        LhdcOffloadPath.setForTest(Boolean.TRUE, Boolean.FALSE);
        assertFalse(LhdcOffloadPath.isOffloaded());
    }

    @Test
    public void halWithoutLhdcNeverCountsAsOffload() {
        LhdcOffloadPath.setForTest(Boolean.FALSE, Boolean.TRUE);
        assertTrue(LhdcOffloadPath.isKnown());
        assertFalse(LhdcOffloadPath.isOffloaded());
    }

    @Test
    public void failedProbeIsUnknownAndNeverBlocksGovernance() {
        LhdcOffloadPath.resetForTest();
        assertFalse(LhdcOffloadPath.isKnown());
        assertFalse(LhdcOffloadPath.isOffloaded());
        assertTrue(LhdcOffloadPath.describe().contains("known=false"));
    }

    @Test
    public void describeReportsBothGates() {
        LhdcOffloadPath.setForTest(Boolean.FALSE, Boolean.TRUE);
        String text = LhdcOffloadPath.describe();
        assertTrue(text.contains("halListsLhdc=false"));
        assertTrue(text.contains("stackOffloadEnabled=true"));
        assertTrue(text.contains("offloaded=false"));
    }
}
