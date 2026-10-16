package com.pixelclub.worldquest.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetRelayTest {

    @Test
    void noLpcRelayApiMeansOneServer() {
        assertFalse(NetRelay.shouldRelay(false, false));
        assertFalse(NetRelay.shouldRelay(false, true));
    }

    @Test
    void relayOffInNetworkYmlMeansOneServer() {
        assertFalse(NetRelay.shouldRelay(true, false));
    }

    @Test
    void relayOnSendsToTheOtherBackends() {
        assertTrue(NetRelay.shouldRelay(true, true));
    }
}
