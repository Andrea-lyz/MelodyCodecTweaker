package xyz.melodylsp.codec.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrustedBroadcastsTest {

    @Test
    public void allowedIdentityRequiresFrameworkPackageToBelongToUid() {
        assertTrue(TrustedBroadcasts.isAllowedIdentity(
                10_123,
                "com.oplus.melody",
                new String[]{"com.oplus.melody"},
                "com.oplus.melody"));

        assertFalse(TrustedBroadcasts.isAllowedIdentity(
                10_123,
                "com.oplus.melody",
                new String[]{"com.example.attacker"},
                "com.oplus.melody"));
    }

    @Test
    public void allowedIdentityRejectsDifferentSharedUidPackage() {
        assertFalse(TrustedBroadcasts.isAllowedIdentity(
                1_002,
                "com.example.sameuid",
                new String[]{"com.android.bluetooth", "com.example.sameuid"},
                "com.android.bluetooth"));
    }
}
