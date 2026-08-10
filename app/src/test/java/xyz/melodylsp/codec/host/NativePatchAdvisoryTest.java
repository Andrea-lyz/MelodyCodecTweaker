package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import xyz.melodylsp.codec.label.CodecLabelTable;

public final class NativePatchAdvisoryTest {

    private static final long TIER_1000 = CodecLabelTable.LHDC_QUALITY_FIXED_1000;
    private static final long TIER_900 = CodecLabelTable.LHDC_QUALITY_FIXED_900;
    private static final long TIER_500 = CodecLabelTable.LHDC_QUALITY_MID_500;
    private static final long TIER_ABR = CodecLabelTable.LHDC_QUALITY_ABR;

    @Before
    public void reset() {
        NativePatchAdvisory.update(false, false);
    }

    @Test
    public void fixedTierDetection() {
        assertTrue(NativePatchAdvisory.isFixedLhdcTier(TIER_1000));
        assertTrue(NativePatchAdvisory.isFixedLhdcTier(TIER_900));
        assertFalse(NativePatchAdvisory.isFixedLhdcTier(TIER_500));
        assertFalse(NativePatchAdvisory.isFixedLhdcTier(TIER_ABR));
        assertFalse(NativePatchAdvisory.isFixedLhdcTier(0L));
    }

    @Test
    public void selectionToastOnlyWarnsWhenBitrateOkAndFastSwitchMissing() {
        NativePatchAdvisory.update(true, false);
        assertNull(NativePatchAdvisory.selectionToast(TIER_1000));
        assertNull(NativePatchAdvisory.selectionToast(TIER_900));

        NativePatchAdvisory.update(false, false);
        assertNull(NativePatchAdvisory.selectionToast(TIER_1000));

        NativePatchAdvisory.update(false, true);
        assertEquals(Strings.TOAST_NATIVE_PATCH_PARTIAL,
                NativePatchAdvisory.selectionToast(TIER_1000));
        assertEquals(Strings.TOAST_NATIVE_PATCH_PARTIAL,
                NativePatchAdvisory.selectionToast(TIER_900));
        // 非固定档（连接优先 / 自适应）不提醒
        assertNull(NativePatchAdvisory.selectionToast(TIER_500));
        assertNull(NativePatchAdvisory.selectionToast(TIER_ABR));

        NativePatchAdvisory.update(true, true);
        // B 缺失时预提醒保持静默（拒绝语义由写失败路径承担）
        assertNull(NativePatchAdvisory.selectionToast(TIER_1000));
        assertNull(NativePatchAdvisory.selectionToast(0L));
    }

    @Test
    public void replayToastPrefersBitrateThenFastSwitch() {
        NativePatchAdvisory.update(true, false);
        assertEquals(Strings.TOAST_NATIVE_PATCH_UNSUPPORTED,
                NativePatchAdvisory.replayToast(TIER_1000));
        assertEquals(Strings.TOAST_NATIVE_PATCH_UNSUPPORTED,
                NativePatchAdvisory.replayToast(TIER_900));

        NativePatchAdvisory.update(true, true);
        assertEquals(Strings.TOAST_NATIVE_PATCH_UNSUPPORTED,
                NativePatchAdvisory.replayToast(TIER_1000));

        NativePatchAdvisory.update(false, true);
        assertEquals(Strings.TOAST_NATIVE_PATCH_PARTIAL,
                NativePatchAdvisory.replayToast(TIER_1000));
        assertEquals(Strings.TOAST_NATIVE_PATCH_PARTIAL,
                NativePatchAdvisory.replayToast(TIER_900));

        NativePatchAdvisory.update(false, false);
        assertNull(NativePatchAdvisory.replayToast(TIER_1000));
        assertNull(NativePatchAdvisory.replayToast(TIER_900));
        assertNull(NativePatchAdvisory.replayToast(TIER_500));
        assertNull(NativePatchAdvisory.replayToast(TIER_ABR));
    }
}
