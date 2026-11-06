package com.protek.booking.tenancy

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.TenantRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.ActingAs
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * A non-payment suspension used to set `tenant.status` and change nothing else: every
 * channel kept selling. The hold now refuses, and a hold taken before the suspension is left alone.
 * The shared tenant is suspended for the test and reactivated in `finally`, because the suite
 * runs in one JVM against it.
 */
@QuarkusTest
class SuspendedTenantHoldTest {

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var db: Db

    private fun setStatus(tenantId: UUID, status: String) {
        db.inTransaction { c -> tenantRepo.updateStatus(c, tenantId, status) }
    }

    @Test
    fun aSuspendedCinemaTakesNoNewHoldOnTheWebOrAtTheCounter() {
        val tenant: Tenant = currentTenant.get()
        val hall = fixture.pool(tenant)
        val seat = fixture.unit(tenant, hall, "S1", "S", 1)
        val counterSeat = fixture.unit(tenant, hall, "S2", "S", 2)
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        val session = fixture.session(tenant, hall, "on_sale")
        QuarkusMock.installMockForType(ActingAs.customer(fixture.customer(tenant).customerId), CurrentActor::class.java)

        setStatus(tenant.tenantId, Tenant.STATUS_SUSPENDED)
        try {
            val web = bookingService.hold(hall.resourcePoolId, session.resourcePoolSessionId,
                listOf(seat.resourceUnitId), "Test Customer", "+62-800-" + UUID.randomUUID().toString().substring(0, 8),
                AgeClassification.Attestation.NOT_TICKED)
            assertFalse(web.success, "a suspended tenant's seat map still sold a seat")
            assertEquals("This cinema is not taking new bookings at the moment. Tickets already paid for are still valid.",
                web.failureReason)
            assertFalse(bookingService.holdForCounterSale(hall.resourcePoolId, session.resourcePoolSessionId,
                listOf(counterSeat.resourceUnitId)).success, "a suspended tenant's counter still sold a seat")
        } finally {
            setStatus(tenant.tenantId, Tenant.STATUS_ACTIVE)
        }

        assertTrue(bookingService.hold(hall.resourcePoolId, session.resourcePoolSessionId,
            listOf(seat.resourceUnitId), "Test Customer", "+62-800-" + UUID.randomUUID().toString().substring(0, 8),
            AgeClassification.Attestation.NOT_TICKED).success, "reactivating did not reopen sales")
    }
}
