package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

import xyz.melodylsp.codec.bridge.CodecSnapshot;
import xyz.melodylsp.codec.label.CodecLabelTable;
import xyz.melodylsp.codec.storage.PreferenceStore;

public final class ConnectionStateReplayerTest {

    @Test
    public void normalConnectedReplayKeepsCompatibilityDelay() {
        assertEquals(1_500L, ConnectionStateReplayer.REPLAY_DELAY_MS);
    }

    @Test
    public void activeDeviceReadyAcceleratesPendingReplay() {
        assertEquals(100L, ConnectionStateReplayer.replayDelayAfterActiveReady(
                ConnectionStateReplayer.REPLAY_DELAY_MS));
    }

    @Test
    public void activeDeviceReadyDoesNotDelayAlreadyFasterReplay() {
        assertEquals(50L, ConnectionStateReplayer.replayDelayAfterActiveReady(50L));
    }

    @Test
    public void replayAdvisoryDedupsWithinWindowPerMac() {
        Map<String, Long> lastShown = new HashMap<>();
        assertTrue(ConnectionStateReplayer.shouldShowReplayAdvisory(
                lastShown, "AA:BB:CC:DD:EE:01", 1_000L));
        // 窗口内重复调度 / 重试 → 去重
        assertFalse(ConnectionStateReplayer.shouldShowReplayAdvisory(
                lastShown, "AA:BB:CC:DD:EE:01", 20_000L));
        // 窗口过期后再次放行
        assertTrue(ConnectionStateReplayer.shouldShowReplayAdvisory(
                lastShown, "AA:BB:CC:DD:EE:01", 61_000L));
        // 不同 MAC 互不影响
        assertTrue(ConnectionStateReplayer.shouldShowReplayAdvisory(
                lastShown, "AA:BB:CC:DD:EE:02", 21_000L));
    }

    @Test
    public void explicitCapabilityMissRejectsRememberedVendorCodec() {
        CodecSnapshot live = snapshot(
                CodecLabelTable.CODEC_SBC,
                new int[]{CodecLabelTable.CODEC_SBC, CodecLabelTable.CODEC_AAC});

        assertEquals(-1, ConnectionStateReplayer.replayCodecType(
                live, CodecLabelTable.CODEC_LDAC));
    }

    @Test
    public void missingCapabilityArrayKeepsVendorFallback() {
        CodecSnapshot live = snapshot(CodecLabelTable.CODEC_SBC, new int[0]);

        assertEquals(CodecLabelTable.CODEC_LDAC, ConnectionStateReplayer.replayCodecType(
                live, CodecLabelTable.CODEC_LDAC));
    }

    @Test
    public void missingCapabilityArrayStillRestoresMandatorySbc() {
        CodecSnapshot live = snapshot(CodecLabelTable.CODEC_AAC, new int[0]);

        assertEquals(CodecLabelTable.CODEC_SBC, ConnectionStateReplayer.replayCodecType(
                live, CodecLabelTable.CODEC_SBC));
    }

    @Test
    public void missingCapabilityArrayStillRestoresRememberedAac() {
        CodecSnapshot live = snapshot(CodecLabelTable.CODEC_SBC, new int[0]);

        assertEquals(CodecLabelTable.CODEC_AAC, ConnectionStateReplayer.replayCodecType(
                live, CodecLabelTable.CODEC_AAC));
    }

    @Test
    public void explicitCapabilityMissRejectsRememberedAac() {
        CodecSnapshot live = snapshot(
                CodecLabelTable.CODEC_SBC,
                new int[]{CodecLabelTable.CODEC_SBC});

        assertEquals(-1, ConnectionStateReplayer.replayCodecType(
                live, CodecLabelTable.CODEC_AAC));
    }

    @Test
    public void standardCodecMemoryStillRequiresRememberedSampleRate() {
        CodecSnapshot live = snapshot(
                CodecLabelTable.CODEC_AAC,
                new int[]{CodecLabelTable.CODEC_SBC, CodecLabelTable.CODEC_AAC});
        PreferenceStore.RememberedValue stored = new PreferenceStore.RememberedValue(
                CodecLabelTable.CODEC_AAC,
                0L,
                0x1);

        assertFalse(ConnectionStateReplayer.matchesStoredValue(live, stored));
    }

    @Test
    public void standardCodecMemoryAcceptsMatchingSampleRate() {
        CodecSnapshot live = snapshot(
                CodecLabelTable.CODEC_AAC,
                new int[]{CodecLabelTable.CODEC_SBC, CodecLabelTable.CODEC_AAC});
        PreferenceStore.RememberedValue stored = new PreferenceStore.RememberedValue(
                CodecLabelTable.CODEC_AAC,
                0L,
                0x8);

        assertTrue(ConnectionStateReplayer.matchesStoredValue(live, stored));
    }

    @Test
    public void explicitRememberedLhdcVariantMustActuallyBecomeActive() {
        int activeVariant = CodecLabelTable.CODEC_LHDC;
        int rememberedVariant = CodecLabelTable.CODEC_LHDC_V3_LEGACY;
        CodecSnapshot live = snapshot(
                activeVariant,
                new int[]{activeVariant, rememberedVariant});
        PreferenceStore.RememberedValue stored = new PreferenceStore.RememberedValue(
                rememberedVariant,
                CodecLabelTable.LHDC_QUALITY_ABR,
                0x8);

        assertFalse(ConnectionStateReplayer.matchesStoredValue(live, stored));
    }

    @Test
    public void unenumeratedLhdcVariantCanUseFamilyAlias() {
        CodecSnapshot live = snapshot(
                CodecLabelTable.CODEC_LHDC,
                new int[]{CodecLabelTable.CODEC_LHDC});
        PreferenceStore.RememberedValue stored = new PreferenceStore.RememberedValue(
                CodecLabelTable.CODEC_LHDC_V3_LEGACY,
                CodecLabelTable.LHDC_QUALITY_ABR,
                0x8);

        assertTrue(ConnectionStateReplayer.matchesStoredValue(live, stored));
    }

    private static CodecSnapshot snapshot(int activeCodec, int[] selectableCodecs) {
        int[] rates = new int[selectableCodecs.length];
        int[] bits = new int[selectableCodecs.length];
        int[] channels = new int[selectableCodecs.length];
        long[] specific1 = new long[selectableCodecs.length];
        for (int i = 0; i < selectableCodecs.length; i++) {
            rates[i] = 0x8;
            bits[i] = 0x2;
            channels[i] = 0x2;
            specific1[i] = CodecLabelTable.LHDC_QUALITY_ABR;
        }
        return new CodecSnapshot(
                "AA:BB:CC:DD:EE:FF",
                activeCodec,
                0x8,
                0x2,
                0x2,
                CodecLabelTable.LHDC_QUALITY_ABR,
                0L,
                0L,
                0L,
                new long[]{CodecLabelTable.LHDC_QUALITY_ABR},
                0x8,
                selectableCodecs,
                rates,
                bits,
                channels,
                specific1,
                1,
                1,
                1L);
    }
}
