package xyz.melodylsp.codec.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class MLogTest {

    @Test
    public void redactsBluetoothAddressesInsideStructuredMessages() {
        assertEquals(
                "request mac=AA:**:**:**:**:FF device=11:**:**:**:**:66",
                MLog.redactBluetoothAddresses(
                        "request mac=AA:BB:CC:DD:EE:FF device=11:22:33:44:55:66"));
    }
}
