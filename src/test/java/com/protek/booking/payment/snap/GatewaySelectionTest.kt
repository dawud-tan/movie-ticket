package com.protek.booking.payment.snap

import com.protek.booking.payment.MidtransGateway
import io.quarkus.arc.ClientProxy
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.QuarkusTestProfile
import io.quarkus.test.junit.TestProfile
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * That `midtrans.api=snap` selects the BI-SNAP gateway.
 *
 * Worth pinning because the failure would be silent in the worst direction: if
 * `@Reserve` or `@IfBuildProperty` ever stopped behaving as expected, the likely
 * outcome is charges quietly continuing to go to one API while everyone believes they go to the
 * other.
 *
 * **Carries an explicit profile even though `snap` is the shipped production default**,
 * because `%test.midtrans.api=legacy` deliberately pins the suite to the legacy gateway —
 * the payment stubs extend `HttpMidtransGateway` and `QuarkusMock` resolves by bean
 * class. Reading "whatever the suite happens to run on" would therefore prove nothing about what
 * production ships. [com.protek.booking.payment.ShippedGatewayDefaultTest] pins that
 * separately, and [LegacyGatewaySelectionTest] covers the rollback direction.
 *
 * A separate class per direction because the property is **build-time**: it decides which
 * bean exists, so the cases cannot share one Quarkus instance, and Quarkus rejects
 * `@TestProfile` on a `@Nested` class outright.
 */
@QuarkusTest
@TestProfile(GatewaySelectionTest.SnapSelected::class)
class GatewaySelectionTest {

    @Inject
    lateinit var injected: MidtransGateway

    @Test
    fun theSnapGatewayDisplacesTheDefault() {
        // @Reserve on HttpMidtransGateway is what makes this a displacement rather than an
        // ambiguous dependency. If this ever fails as a deployment exception instead of an
        // assertion, that annotation is the thing to look at.
        assertInstanceOf(SnapMidtransGateway::class.java, ClientProxy.unwrap(injected),
            "midtrans.api=snap must put BI-SNAP in charge of creating charges")
    }

    @Test
    fun itReportsUnconfiguredUntilCredentialsExist_ratherThanFailingABooking() {
        // The pay-at-counter fallback has to survive the switch: selecting BI-SNAP before its
        // credential exchange finishes must cost a tenant nothing worse than card-free checkout.
        assertFalse(injected.isConfigured())
    }

    class SnapSelected : QuarkusTestProfile {
        override fun getConfigOverrides(): Map<String, String> = mapOf("midtrans.api" to "snap")
    }
}
