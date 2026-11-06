package com.protek.booking.payment

import io.quarkus.arc.ClientProxy
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Says at boot which payment gateway is actually in charge.
 *
 * Exists because `midtrans.api` is a **build-time** property and no
 * other signal distinguishes the two APIs — the service starts, the seat map works, a booking
 * completes, identically either way. The first evidence would otherwise be a settlement arriving
 * in the wrong format, or not at all.
 *
 * It was originally written because forgetting `-Dmidtrans.api=snap` built cleanly and
 * kept charging through the legacy Core API. The default became `snap` on 2026-08-17, which
 * removes that particular trap and not the need for this line: a stale
 * `application.properties` on a box, a merge reverting the default, or an inherited
 * `-Dmidtrans.api=legacy` all look exactly the same until something prints the answer.
 *
 * Deliberately one line at INFO and nothing else. The alternative, discovering the answer by
 * grepping a packaged jar or by making a real charge and reading its reference number, is the kind
 * of thing that gets skipped exactly when it matters.
 */
@ApplicationScoped
class PaymentGatewayStartupLog {

    @Inject
    lateinit var gateway: MidtransGateway

    fun onStart(@Observes event: StartupEvent) {
        // Unwrapped, or every build reports the same synthetic ClientProxy subclass name and the
        // line answers nothing.
        LOG.infof("Payment gateway in use: %s (configured=%s)",
            ClientProxy.unwrap(gateway).javaClass.simpleName, gateway.isConfigured())
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(PaymentGatewayStartupLog::class.java)
    }
}
