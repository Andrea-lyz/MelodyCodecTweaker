package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import xyz.melodylsp.codec.bridge.LhdcQualityPolicy;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;

public final class CodecControllerDeviceTest {
    private static final int ROM_LHDC_V5 = 19;

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

    @Test
    public void rememberedQualityRecognizesLegacyAndCeilingValues() {
        assertTrue(CodecController.isRememberedQualityPriority(
                new PreferenceStore.RememberedValue(-1, 0x8008L, 8),
                ROM_LHDC_V5));
        assertTrue(CodecController.isRememberedQualityPriority(
                new PreferenceStore.RememberedValue(ROM_LHDC_V5, 0x8007L, 8),
                ROM_LHDC_V5));
        assertTrue(CodecController.isRememberedQualityPriority(
                new PreferenceStore.RememberedValue(CodecLabelTable.CODEC_LDAC,
                        CodecLabelTable.LDAC_QUALITY_HIGH, 8), CodecLabelTable.CODEC_LDAC));
    }

    @Test
    public void adaptiveOrDifferentCodecMemoryDoesNotTriggerUpgradeNotice() {
        assertFalse(CodecController.isRememberedQualityPriority(null, ROM_LHDC_V5));
        assertFalse(CodecController.isRememberedQualityPriority(
                new PreferenceStore.RememberedValue(ROM_LHDC_V5, 0x8009L, 8),
                ROM_LHDC_V5));
        assertFalse(CodecController.isRememberedQualityPriority(
                new PreferenceStore.RememberedValue(CodecLabelTable.CODEC_LDAC,
                        CodecLabelTable.LDAC_QUALITY_HIGH, 8), ROM_LHDC_V5));
    }
}
