package com.protek.booking.payment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * That the **shipped** `midtrans.api` default is still `snap`.
 *
 * Deliberately reads `application.properties` rather than injecting the resolved config,
 * and that is the entire point. `%test.midtrans.api=legacy` pins the running suite to the
 * legacy gateway — the payment stubs extend `HttpMidtransGateway` and `QuarkusMock`
 * resolves by bean class — so no `@QuarkusTest` can observe what production
 * will actually build with. Asking the file is the only way to assert it.
 *
 * What this guards: someone reverting the default, or a merge quietly losing it. Both build
 * cleanly, start cleanly, serve seat maps cleanly, and charge through the wrong Midtrans API —
 * the failure mode `PaymentGatewayStartupLog` exists to make visible at boot, caught here
 * before it ships instead.
 *
 * A plain JUnit test, no Quarkus: it is a statement about a file, needs no application, and
 * costs nothing to run.
 */
class ShippedGatewayDefaultTest {

    private fun shippedConfig(): Properties {
        val properties = Properties()
        javaClass.classLoader.getResourceAsStream("application.properties").use { `in` ->
            assertNotNull(`in`, "application.properties must be on the test classpath")
            properties.load(`in`)
        }
        return properties
    }

    @Test
    fun productionShipsWithTheSnapGatewaySelected() {
        assertEquals("snap", shippedConfig().getProperty("midtrans.api"),
            "the unprofiled midtrans.api default is what a plain `./mvnw package` builds with; " +
                "if this is not 'snap', production charges through the legacy Core API")
    }

    @Test
    fun theTestProfileIsStillPinnedToLegacy() {
        // The other half, and the one whose absence is baffling rather than merely wrong: without
        // it every payment stub fails to install with "not assignable" and 38 tests error out.
        assertEquals("legacy", shippedConfig().getProperty("%test.midtrans.api"),
            "the suite's payment stubs extend HttpMidtransGateway, so tests must stay on legacy")
    }
}
