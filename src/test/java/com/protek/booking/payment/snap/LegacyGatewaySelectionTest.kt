package com.protek.booking.payment.snap

import com.protek.booking.payment.HttpMidtransGateway
import com.protek.booking.payment.MidtransGateway
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The other half of [GatewaySelectionTest]: that `midtrans.api=legacy` still selects
 * the Core API v2 gateway.
 *
 * **This is the rollback path, which is why it keeps its own test.** The BI-SNAP
 * cutover has to stay reversible per environment; since the default became `snap`
 * on 2026-08-17, "reversible" means exactly that this property still works, and a rollback route
 * nobody exercises is one nobody can rely on under pressure.
 *
 * A separate class with its own profile because the property is **build-time** — it decides
 * which bean exists, so the two cases cannot share one Quarkus instance, and Quarkus refuses
 * `@TestProfile` on a `@Nested` class.
 */
@QuarkusTest
@TestProfile(LegacyGatewaySelectionTest.LegacySelected::class)
class LegacyGatewaySelectionTest {

    @Inject
    lateinit var injected: MidtransGateway

    @Test
    fun pinningTheLegacyApiBringsBackTheCoreApiGateway() {
        // @Reserve on HttpMidtransGateway is what makes this work: with midtrans.api set to
        // anything other than "snap", @IfBuildProperty suppresses the SNAP bean and the default
        // is the only MidtransGateway left. If this ever fails as a deployment exception rather
        // than an assertion, that annotation is the thing to look at.
        assertInstanceOf(HttpMidtransGateway::class.java, ClientProxy.unwrap(injected),
            "midtrans.api=legacy must leave the legacy Core API in charge")
    }

    class LegacySelected : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("midtrans.api" to "legacy")
    }
}
