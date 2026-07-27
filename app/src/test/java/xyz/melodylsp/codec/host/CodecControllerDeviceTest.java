package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import xyz.melodylsp.codec.bridge.LhdcQualityPolicy;

public final class CodecControllerDeviceTest {

    @Test
    public void deviceMatchNormalizesCaseAndWhitespace() {
        assertTrue(CodecController.sameDevice(
                " aa:bb:cc:dd:ee:ff ", "AA:BB:CC:DD:EE:FF"));
    }

    @Test
    public void deviceMatchRejectsDifferentOrMissingMac() {
        assertFalse(CodecController.sameDevice(
                "AA:BB:CC:DD:EE:FF", "AA:BB:CC:DD:EE:00"));
        assertFalse(CodecController.sameDevice(null, "AA:BB:CC:DD:EE:FF"));
        assertFalse(CodecController.sameDevice("", "AA:BB:CC:DD:EE:FF"));
    }

    @Test
    public void bitrateIsShownForAdaptiveAndQualityPolicies() {
        assertEquals("音质优先（当前 500 kbps）", CodecController.lhdcQualitySummary(
                "音质优先", LhdcQualityPolicy.QUALITY, 500));
        assertEquals("自适应（当前 500 kbps）", CodecController.lhdcQualitySummary(
                "自适应", LhdcQualityPolicy.ADAPTIVE, 500));
        assertEquals("连接优先", CodecController.lhdcQualitySummary(
                "连接优先", LhdcQualityPolicy.CONNECTION, 500));
        assertEquals("音质优先", CodecController.lhdcQualitySummary(
                "音质优先", LhdcQualityPolicy.QUALITY, 0));
    }
}
