package com.protek.booking.counter

import com.protek.booking.db.Db
import com.protek.booking.repository.AuditLogRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * The counter sale: staff selling to a walk-in who has no identity.
 *
 * The properties worth testing are the ones the walk-in design argues for specifically — that the
 * placeholder customer keeps the FK graph and `NOT NULL` guarantees intact, and that
 * `created_by_actor`/`audit_log.actor_type` make a counter sale distinguishable
 * from a self-service booking "without special-casing." The second is the load-bearing one:
 * if a counter sale were indistinguishable in the record, the tenant could not reconcile cash
 * takings against the system at end of day.
 */
@QuarkusTest
class CounterSaleTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var auditRepo: AuditLogRepository

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun counterSale_attachesToWalkInPlaceholder_andRecordsStaffAsActor() {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "A1", "A", 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))

        assertTrue(result.success, "counter sale should succeed: " + result.failureReason)
        assertEquals(1, result.reservations.size)

        val walkIn = db.inTenantTransaction(tenant.tenantId) { c ->
            customerRepo.findWalkInPlaceholder(c, tenant.tenantId)
        }.orElseThrow()
        assertTrue(walkIn.walkInPlaceholder)

        val group = db.inTenantTransaction(tenant.tenantId) { c ->
            groupRepo.findById(c, tenant.tenantId, result.group!!.reservationGroupId)
        }.orElseThrow()
        assertEquals(walkIn.customerId, group.customerId,
            "a counter sale must attach to the tenant's walk-in placeholder, keeping "
                + "reservation_group.customer_id NOT NULL rather than nullable")

        val reservation = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId,
                result.reservations[0].reservationId)
        }.orElseThrow()
        assertEquals("tenant_staff", reservation.createdByActor,
            "created_by_actor is what makes a counter sale distinguishable from a "
                + "self-service booking in the record")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun counterSale_auditTrailMirrorsTheStaffActor_andCarriesAnActorId() {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "B2", "B", 2)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val result = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))
        assertTrue(result.success)

        val entries = db.inTenantTransaction(tenant.tenantId) { c ->
            auditRepo.findByEntity(c, tenant.tenantId, "reservation",
                result.reservations[0].reservationId)
        }

        assertEquals(1, entries.size)
        val insert = entries[0]
        assertEquals("INSERT", insert.action)
        assertEquals("tenant_staff", insert.actorType,
            "audit_log.actor_type mirrors reservation.created_by_actor")
    }

    @Test
    @TestSecurity(user = "customer@demo-cinema.example", roles = ["customer"],
        attributes = [SecurityAttribute(key = "email", value = "customer@demo-cinema.example")])
    fun selfServiceBooking_isRecordedAsCustomer_notStaff() {
        // The other half of "distinguishable without special-casing": the same code path must
        // produce a different actor for a customer, or the distinction is meaningless.
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "C3", "C", 3)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val result = bookingService.hold(
            pool.resourcePoolId, showing.resourcePoolSessionId,
            java.util.List.of(seat.resourceUnitId), "Self Service", "+62811000111",
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(result.success, "self-service hold should succeed: " + result.failureReason)

        val reservation = db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.findById(c, tenant.tenantId,
                result.reservations[0].reservationId)
        }.orElseThrow()
        assertEquals("customer", reservation.createdByActor)

        val group = db.inTenantTransaction(tenant.tenantId) { c ->
            groupRepo.findById(c, tenant.tenantId, result.group!!.reservationGroupId)
        }.orElseThrow()
        val walkIn = db.inTenantTransaction(tenant.tenantId) { c ->
            customerRepo.findWalkInPlaceholder(c, tenant.tenantId)
        }.orElseThrow()
        assertNotEquals(walkIn.customerId, group.customerId,
            "a self-service booking must NOT be attached to the walk-in placeholder")
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
        attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun counterSale_usesTheSameExcludeConstraint_soASoldSeatCannotBeSoldAgain() {
        // The point of routing counter sales through the same service rather than a parallel
        // path: the double-booking guarantee applies identically. A separate counter-sale
        // implementation is exactly where that guarantee would quietly not hold.
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "D4", "D", 4)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val first = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))
        assertTrue(first.success)

        val second = bookingService.holdForCounterSale(
            pool.resourcePoolId, showing.resourcePoolSessionId, java.util.List.of(seat.resourceUnitId))
        assertFalse(second.success, "the second counter sale for the same seat must fail")
        assertEquals(seat.resourceUnitId, second.failedResourceUnitId,
            "The richer hold response: staff need to know WHICH seat blocked the sale so they "
                + "can offer a different specific one")
    }
}
