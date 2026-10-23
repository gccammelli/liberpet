package io.github.gccammelli.liberpet

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalOnlyTest {
    @Test fun homeNetworkAddressesAreLocal() {
        for (h in listOf("10.20.30.40", "10.20.0.5", "172.20.1.1", "127.0.0.1", "100.101.102.103", "homeassistant.local",
                "ha.home.arpa", "localhost", "[fe80::1]", "fd12::3")) assertTrue(isLocalHost(h), h)
    }

    @Test fun internetAddressesAreNot() {
        for (h in listOf("8.8.8.8", "172.32.0.1", "example.duckdns.org", "ha.example.com", "2001:db8::1", "10.20.30",
                "999.1.1.1", "local.example.com", "homeassistant", "nas.lan", "ha.internal")) assertFalse(isLocalHost(h), h)
    }

    @Test fun privateRangeEdgesAndIpv6FormsStayWithinTheCleartextBoundary() {
        for (h in listOf("[::1]", "fc00::1", "fe80::1", "169.254.0.1", "100.64.0.1", "100.127.255.255", "172.16.0.1", "172.31.255.255")) assertTrue(isLocalHost(h), h)
        for (h in listOf("192.169.0.1", "172.15.0.1", "100.63.255.255", "100.128.0.1", "169.253.0.1", "169.255.0.1", "10.-1.0.1", "1.2.3.4.5")) assertFalse(isLocalHost(h), h)
    }
}
