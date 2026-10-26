package xyz.melodylsp.codec.system;

import static org.junit.Assert.*;

import org.junit.Test;

import xyz.melodylsp.codec.bridge.CodecSnapshot;

public class LhdcPlatformProtectionTest {
    private static final String A = "40:00:00:00:00:9D";
    private static final String B = "40:00:00:00:00:9E";

    private static CodecSnapshot live(String mac, long quality, int rate) {
        return new CodecSnapshot(mac, 19, rate, 2, 2, quality, 0, 1543503872L, 0,
                new long[] {0x8008}, 0x3f, 0);
    }

    private static CodecSnapshot live(long quality) {
        return live(A, quality, 0x20);
    }

    private static LhdcPlatformProtection.Decision trigger(LhdcPlatformProtection p) {
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 0));
        p.onChoppy(A, 1_000);
        p.onChoppy(A, 12_000);
        return p.observe(live(0x8008), true, true, false, true, true, 25_000);
    }

    @Test public void twoReportsAfterStartupIssueOne900RequestPreservingFormat() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        LhdcPlatformProtection.Decision d = trigger(p);
        assertNotNull(d);
        assertFalse(d.restoring);
        assertEquals(0x8007, d.request.codecSpecific1);
        assertEquals(0x20, d.request.sampleRate);
        assertEquals(2, d.request.bitsPerSample);
        assertEquals(2, d.request.channelMode);
        assertEquals(1543503872L, d.request.codecSpecific3);
        assertEquals(0, d.request.codecSpecific2);
        assertEquals(0, d.request.codecSpecific4);
        assertEquals("pending_900", p.phase(A));
        assertFalse(p.confirmed(A));
    }

    @Test public void setterAcceptanceDoesNotInventConfigurationOrBitrateConfirmation() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        LhdcPlatformProtection.Decision d = trigger(p);
        p.onSubmission(A, d.id, true);
        assertFalse(p.confirmed(A));
        assertEquals(0x8008, p.annotate(live(0x8008)).activeCodecSpecific1);
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 26_000));
        assertFalse(p.confirmed(A));
        assertNull(p.observe(live(0x8007), true, true, false, true, true, 27_000));
        assertTrue(p.confirmed(A));
        assertEquals("protected_900", p.phase(A));
    }

    @Test public void temporaryTransportNeverBecomesTheRememberedSelection() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        CodecSnapshot physical = live(0x8007);
        CodecSnapshot decorated = p.annotate(physical);
        assertEquals(0x8007, physical.selectionCodecSpecific1());
        assertEquals(0x8007, decorated.activeCodecSpecific1);
        assertEquals(0x8008, decorated.selectionCodecSpecific1());
        assertEquals(physical.activeSampleRate, decorated.activeSampleRate);
    }

    @Test public void disablingGovernorRestoresOnlyTheOwnedConfiguration() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        LhdcPlatformProtection.Decision d = p.observe(
                live(0x8007), true, false, false, true, true, 26_000);
        assertNotNull(d);
        assertTrue(d.restoring);
        assertEquals(0x8008, d.request.codecSpecific1);
        assertNull(p.observe(live(0x8008), true, false, false, true, true, 27_000));
        assertFalse(p.hasOverride(A));
    }

    @Test public void softwareHandoffRestoresSelectionBeforeUsingSoftwareGovernor() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        LhdcPlatformProtection.Decision d = p.observe(
                live(0x8007), true, true, true, true, true, 26_000);
        assertNotNull(d);
        assertTrue(d.restoring);
    }

    @Test public void governorOffNeverLowersTheSelected1000() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        for (int i = 0; i < 60; i++) {
            assertNull(p.observe(live(0x8008), true, false, false, true, true, i * 1_000L));
            p.onChoppy(A, i * 1_000L);
        }
        assertFalse(p.hasOverride(A));
    }

    @Test public void unavailableOrInapplicablePathsCannotWrite() {
        for (int gate = 0; gate < 5; gate++) {
            LhdcPlatformProtection p = new LhdcPlatformProtection();
            for (int i = 0; i < 40; i++) {
                assertNull(p.observe(live(gate == 4 ? 0x8009 : 0x8008), gate != 0,
                        true, gate == 1, gate == 2 ? null : true, gate != 3, i * 1_000L));
                p.onChoppy(A, i * 1_000L);
            }
        }
    }

    @Test public void duplicatedOrExpiredReportsDoNotTriggerProtection() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        p.observe(live(0x8008), true, true, false, true, true, 0);
        p.onChoppy(A, 1_000);
        p.onChoppy(A, 1_002);
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 25_000));
        p.onChoppy(A, 35_000);
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 35_000));
    }

    @Test public void startupTransientWaitsForGuardAndAnotherReport() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        p.observe(live(0x8008), true, true, false, true, true, 0);
        p.onChoppy(A, 1_000);
        p.onChoppy(A, 12_000);
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 24_999));
        assertNotNull(p.observe(live(0x8008), true, true, false, true, true, 25_000));
    }

    @Test public void reportDuringBriefReconfigurationIdleIsNotLostToPlayingCache() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        p.observe(live(0x8008), true, true, false, false, true, 0);
        p.onChoppy(A, 1_000);
        p.observe(live(0x8008), true, true, false, true, true, 2_000);
        p.onChoppy(A, 12_000);
        assertNotNull(p.observe(live(0x8008), true, true, false, true, true, 27_000));
    }

    @Test public void failedOrTimedOutWriteIsNotRetriedEveryPoll() {
        for (boolean rejected : new boolean[] {false, true}) {
            LhdcPlatformProtection p = new LhdcPlatformProtection();
            LhdcPlatformProtection.Decision d = trigger(p);
            p.onSubmission(A, d.id, !rejected);
            for (int i = 26; i < 90; i++) {
                p.onChoppy(A, i * 1_000L);
                assertNull(p.observe(live(0x8008), true, true, false, true, true, i * 1_000L));
            }
            assertFalse(p.confirmed(A));
            assertEquals(rejected ? "write_rejected" : "write_timeout", p.phase(A));
        }
    }

    @Test public void lateAcknowledgementAfterSwitchOffIsRestored() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        assertNull(p.observe(live(0x8008), true, false, false, true, true, 41_000));
        assertEquals("write_timeout", p.phase(A));
        LhdcPlatformProtection.Decision restore = p.observe(
                live(0x8007), true, false, false, true, true, 42_000);
        assertNotNull(restore);
        assertTrue(restore.restoring);
    }

    @Test public void userWriteCancelsOwnershipAndPendingRequest() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        LhdcPlatformProtection.Decision d = trigger(p);
        p.onUserWrite(A);
        assertFalse(p.isPending(A, d.id));
        assertNull(p.observe(live(0x8007), true, false, false, true, true, 26_000));
        assertEquals(0x8007, p.annotate(live(0x8007)).selectionCodecSpecific1());
    }

    @Test public void formatOrPolicyChangeIsNotOverwrittenByRestore() {
        for (CodecSnapshot current : new CodecSnapshot[] {
                live(A, 0x8007, 0x8), live(0x8009)}) {
            LhdcPlatformProtection p = new LhdcPlatformProtection();
            trigger(p);
            assertNull(p.observe(current, true, false, false, true, true, 26_000));
            assertFalse(p.hasOverride(A));
        }
    }

    @Test public void anotherHeadsetDoesNotInheritProtectionOrItsSelection() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        CodecSnapshot b = live(B, 0x8007, 0x20);
        assertNull(p.observe(b, true, false, false, true, true, 26_000));
        assertFalse(p.confirmed(B));
        assertEquals(0x8007, p.annotate(b).selectionCodecSpecific1());
    }

    @Test public void routeChangeRestartsTheContinuousPlaybackGuard() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        p.observe(live(0x8008), true, true, false, true, true, 0);
        p.onChoppy(A, 1_000);
        p.observe(live(B, 0x8008, 0x20), true, true, false, true, true, 10_000);
        p.onChoppy(A, 12_000); // Late callback from the inactive route.
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 20_000));
        p.onChoppy(A, 21_000);
        p.onChoppy(A, 23_000);
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 30_000));
        assertNotNull(p.observe(live(0x8008), true, true, false, true, true, 45_000));
    }

    @Test public void inactiveRouteCannotKeepOldCongestionEvidence() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        p.observe(live(0x8008), true, true, false, true, true, 0);
        p.onChoppy(A, 1_000);
        p.onChoppy(A, 12_000);
        p.onInactive(A);
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 25_000));
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 50_000));
    }

    @Test public void automaticFormatChangeRestartsGuardBeforeFirstRequest() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        p.observe(live(0x8008), true, true, false, true, true, 0);
        p.onChoppy(A, 1_000);
        p.onChoppy(A, 12_000);
        CodecSnapshot changed = live(A, 0x8008, 0x8);
        assertNull(p.observe(changed, true, true, false, true, true, 24_000));
        assertNull(p.observe(changed, true, true, false, true, true, 25_000));
        p.onChoppy(A, 26_000);
        p.onChoppy(A, 28_000);
        assertNotNull(p.observe(changed, true, true, false, true, true, 49_000));
    }

    @Test public void unknownOrMultibitFormatNeverBecomesAnAutomaticWrite() {
        for (CodecSnapshot current : new CodecSnapshot[] {
                live(A, 0x8008, 0), live(A, 0x8008, 0x3),
                new CodecSnapshot(A, 19, 0x20, 0, 2, 0x8008, 0, 0, 0, new long[0], 0, 0),
                new CodecSnapshot(A, 19, 0x20, 2, 0, 0x8008, 0, 0, 0, new long[0], 0, 0)}) {
            LhdcPlatformProtection p = new LhdcPlatformProtection();
            for (int i = 0; i < 40; i++) {
                assertNull(p.observe(current, true, true, false, true, true, i * 1_000L));
                p.onChoppy(A, i * 1_000L);
            }
        }
    }

    @Test public void protectedConfigurationIsHeldWithoutRepeatedReconfiguration() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        for (int i = 26; i < 300; i++) {
            p.onChoppy(A, i * 1_000L);
            assertNull(p.observe(live(0x8007), true, true, false, true, true, i * 1_000L));
        }
        assertTrue(p.confirmed(A));
    }

    @Test public void neverObservedRouteReportsUnavailableInsteadOfIdleProtection() {
        // 2.5.7-offload-test2 shipped a plural-only active-device lookup that ColorOS does
        // not expose, so observe() was never reached and every feedback sample read
        // "available=false" with no explanation. Keep that state distinguishable from an
        // observed-but-gated route.
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        assertFalse(p.available(A));
        assertFalse(p.acceptsReports(A));
        assertFalse(p.confirmed(A));
        assertEquals("idle", p.phase(A));
    }

    @Test public void acceptsReportsMirrorsEveryGateThatBlocksTheFallback() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        assertNull(p.observe(live(0x8008), true, true, false, true, true, 0));
        assertTrue(p.available(A));
        assertTrue(p.acceptsReports(A));
        // A governor switch-off, a paused stream or an unreadable codec status must not
        // keep reporting the protection as armed.
        assertNull(p.observe(live(0x8008), true, false, false, true, true, 1_000));
        assertFalse(p.acceptsReports(A));
        assertFalse(p.available(A));
        assertEquals("waiting", p.phase(A));
    }

    @Test public void rejectedRestoreRemainsVisibleWithoutRetryLoop() {
        LhdcPlatformProtection p = new LhdcPlatformProtection();
        trigger(p);
        LhdcPlatformProtection.Decision restore = p.observe(
                live(0x8007), true, false, false, true, true, 26_000);
        p.onSubmission(A, restore.id, false);
        for (int i = 27; i < 60; i++) {
            assertNull(p.observe(live(0x8007), true, false, false, true, true, i * 1_000L));
        }
        assertEquals("restore_rejected", p.phase(A));
    }
}
