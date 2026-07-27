package xyz.melodylsp.codec.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import xyz.melodylsp.codec.label.CodecLabelTable;

public class LhdcQualityPolicyTest {

    @Test
    public void qualityPolicyUsesFixed1000OnTransportAndPreservesHighBits() {
        long logical = 0x8000L | CodecLabelTable.LHDC_QUALITY_FIXED_1000;
        assertEquals(
                logical,
                LhdcQualityPolicy.transportSpecific1(logical, LhdcQualityPolicy.QUALITY));
        assertEquals(
                logical,
                LhdcQualityPolicy.logicalSpecific1(logical, LhdcQualityPolicy.QUALITY));
    }

    @Test
    public void visibleLhdcCodesMapToThreePolicies() {
        assertEquals(LhdcQualityPolicy.ADAPTIVE,
                LhdcQualityPolicy.fromSpecific1(CodecLabelTable.LHDC_QUALITY_ABR));
        assertEquals(LhdcQualityPolicy.CONNECTION,
                LhdcQualityPolicy.fromSpecific1(CodecLabelTable.LHDC_QUALITY_MID_500));
        assertEquals(LhdcQualityPolicy.QUALITY,
                LhdcQualityPolicy.fromSpecific1(CodecLabelTable.LHDC_QUALITY_FIXED_900));
        assertEquals(LhdcQualityPolicy.QUALITY,
                LhdcQualityPolicy.fromSpecific1(CodecLabelTable.LHDC_QUALITY_FIXED_1000));
    }

    @Test
    public void nonLhdcRequestIsNotRewritten() {
        CodecRequest request = new CodecRequest(
                "00:11:22:33:44:55",
                CodecLabelTable.CODEC_LDAC,
                CodecLabelTable.LDAC_QUALITY_HIGH,
                0L, 0L, 0L,
                0, 0, 0);
        assertSame(request, LhdcQualityPolicy.transportRequest(
                request, LhdcQualityPolicy.QUALITY));
    }
}
