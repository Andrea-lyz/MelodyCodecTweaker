package xyz.melodylsp.codec.host;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
}
