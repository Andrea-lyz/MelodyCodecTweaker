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

    @Test
    public void ceiling900TransportIsPreservedUnderQualityPolicy() {
        long requested = 0x8000L | CodecLabelTable.LHDC_QUALITY_FIXED_1000;
        long lowered = 0x8000L | CodecLabelTable.LHDC_QUALITY_FIXED_900;
        // A request already lowered to the 900 kbps peer ceiling must not be lifted back
        // to 1000 by the QUALITY transport mapping.
        assertEquals(
                lowered,
                LhdcQualityPolicy.transportSpecific1(lowered, LhdcQualityPolicy.QUALITY));
        // The logical mapping still speaks the QUALITY concept.
        assertEquals(
                requested,
                LhdcQualityPolicy.logicalSpecific1(lowered, LhdcQualityPolicy.QUALITY));
    }

    @Test
    public void normalizeTreats900AsQuality() {
        assertEquals(LhdcQualityPolicy.QUALITY,
                LhdcQualityPolicy.normalize(
                        (int) CodecLabelTable.LHDC_QUALITY_FIXED_900));
    }

    @Test
    public void clampToCeilingLowersOnlyFixed1000() {
        long lowered = 0x8000L | CodecLabelTable.LHDC_QUALITY_FIXED_900;
        long requested = 0x8000L | CodecLabelTable.LHDC_QUALITY_FIXED_1000;
        assertEquals(
                lowered,
                LhdcQualityPolicy.clampToCeiling(
                        requested, (int) CodecLabelTable.LHDC_QUALITY_FIXED_900));
        // ABR and other low bytes are untouched.
        long abr = 0x8000L | CodecLabelTable.LHDC_QUALITY_ABR;
        assertEquals(
                abr,
                LhdcQualityPolicy.clampToCeiling(
                        abr, (int) CodecLabelTable.LHDC_QUALITY_FIXED_900));
    }
}
