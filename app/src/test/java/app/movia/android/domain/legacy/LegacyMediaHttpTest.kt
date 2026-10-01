package app.movia.android.domain.legacy

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class LegacyMediaHttpTest {
    @Test fun privateAndLocalDnsAddressesAreRejected() {
        for(value in listOf("127.0.0.1","10.1.2.3","172.16.1.2","192.168.1.1","169.254.1.2","::1","fc00::1","fe80::1","100.64.1.2"))
            assertFalse(value,LegacyMediaHttp.isPublicAddress(InetAddress.getByName(value)))
    }
    @Test fun ordinaryPublicIpv4AndIpv6DestinationsAreAllowed() {
        for(value in listOf("8.8.8.8","1.1.1.1","2606:4700:4700::1111"))
            assertTrue(value,LegacyMediaHttp.isPublicAddress(InetAddress.getByName(value)))
    }
}
