package com.protek.booking.repository

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.Tenant
import com.protek.booking.service.BookingService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.security.SecurityAttribute
import io.quarkus.test.security.TestSecurity
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Locale

/**
 * `reservation_group.booking_code`: the short reference a customer
 * reads down the phone.
 *
 * **Why this is not the admission token, and why the tests keep saying so.** The admission schema already
 * mints an unguessable 96-bit per-seat credential, and the obvious "simplification" for a later
 * reader is to display that instead of minting a second value. The two have opposite
 * requirements — one must be hard to guess, the other must survive being dictated over a phone
 * line — and a single string cannot be both. [theCodeIsNotTheAdmissionTokenUnderAnotherName]
 * is the guard against that collapse.
 */
@QuarkusTest
class BookingCodeTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var bookingService: BookingService

    private fun groupIn(tenant: Tenant): ReservationGroup {
        val pool = fixture.pool(tenant)
        val customer = fixture.customer(tenant)
        return fixture.group(tenant, pool, customer, 1)
    }

    @Test
    fun everyBookingIsBornWithAQuotableCode() {
        // Minted by a column DEFAULT rather than by application code, so there is no confirm path
        // that can forget to mint one — the same reasoning the admission schema records for admission_token.
        val group = groupIn(fixture.tenant())

        assertNotNull(group.bookingCode)
        assertTrue(group.bookingCode!!.matches(Regex(CODE_PATTERN)),
                "a code has to survive being read aloud in a foyer: expected 8 characters with no "
                        + "look-alikes, got '" + group.bookingCode + "'")
    }

    @Test
    fun twoBookingsAtOneCinemaGetDifferentCodes() {
        val tenant = fixture.tenant()

        assertNotEquals(groupIn(tenant).bookingCode, groupIn(tenant).bookingCode,
                "a volatile DEFAULT must be evaluated per row; a STABLE one would give every "
                        + "booking in a statement the same code")
    }

    @Test
    fun theSameCodeMayExistAtTwoDifferentCinemas() {
        // Unique per tenant, NOT globally — the deliberate opposite of the admission token's
        // index. Two cinemas sharing a code is invisible to both: every lookup carries a tenant.
        val first = fixture.tenant()
        val second = fixture.tenant()
        val theirs = groupIn(first)
        val ours = groupIn(second)

        val updated = db.inTenantTransaction(second.tenantId) { c -> db.execute(c,
                "UPDATE reservation_group SET booking_code = ? WHERE tenant_id = ? AND reservation_group_id = ?",
                theirs.bookingCode, second.tenantId, ours.reservationGroupId) }

        assertEquals(1, updated, "the unique index is on (tenant_id, booking_code), so another "
                + "tenant already holding this code must not block it")
    }

    @Test
    fun thatSameCodeTwiceAtOneCinemaIsRefused() {
        // The index is the arbiter — nothing pre-checks the code, in the same spirit as the
        // EXCLUDE constraint. This is what ReservationGroupRepository.insert retries against.
        val tenant = fixture.tenant()
        val first = groupIn(tenant)
        val second = groupIn(tenant)

        val e = assertThrows(DataAccessException::class.java) {
            db.inTenantTransaction(tenant.tenantId) { c -> db.execute(c,
                    "UPDATE reservation_group SET booking_code = ? WHERE tenant_id = ? AND reservation_group_id = ?",
                    first.bookingCode, tenant.tenantId, second.reservationGroupId) } }

        assertTrue(e.isUniqueViolation(), "expected a 23505 from idx_reservation_group_booking_code, got "
                + e.sqlState())
        assertTrue(e.message!!.contains("idx_reservation_group_booking_code"),
                "the retry in ReservationGroupRepository.insert matches on this index name, so the "
                        + "name has to be in the message: " + e.message)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
            attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun staffFindABookingFromWhateverTheCustomerActuallySays() {
        val tenant = currentTenant.get()
        val group = groupIn(tenant)
        val code = group.bookingCode!!

        // A customer reading eight characters aloud groups them, and a staff member types what
        // they hear. All three of these are the same booking.
        // lowercase(Locale.getDefault()) is Java's toLowerCase(); Kotlin's lowercase() is ROOT.
        for (typed in listOf(code, code.lowercase(Locale.getDefault()),
                code.substring(0, 4) + "-" + code.substring(4))) {
            val found = bookingService.findGroupByBookingCode(typed)
            assertTrue(found.isPresent, "'" + typed + "' should find the booking")
            assertEquals(group.reservationGroupId, found.get().reservationGroupId)
        }
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
            attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun anUnknownCodeIsSimplyNotFound() {
        // No exception and no hint. A code is a value somebody could try their neighbour's on,
        // and "not here" is the only answer that stays the same whatever the reason.
        assertTrue(bookingService.findGroupByBookingCode("ZZZZZZZZ").isEmpty)
        assertTrue(bookingService.findGroupByBookingCode("").isEmpty)
        assertTrue(bookingService.findGroupByBookingCode(null).isEmpty)
    }

    @Test
    @TestSecurity(user = "staff@demo-cinema.example", roles = ["tenant_staff"],
            attributes = [SecurityAttribute(key = "email", value = "staff@demo-cinema.example")])
    fun theCodeIsNotTheAdmissionTokenUnderAnotherName() {
        val tenant = currentTenant.get()
        val pool = fixture.pool(tenant)
        val seat = fixture.unit(tenant, pool, "K1", "K", 1)
        val showing = fixture.session(tenant, pool, "on_sale")
        fixture.rateRule(tenant, pool, BigDecimal("50000"))

        val booked = bookingService.holdForCounterSale(
                pool.resourcePoolId, showing.resourcePoolSessionId, listOf(seat.resourceUnitId))
        assertTrue(booked.success, "setup hold failed: " + booked.failureReason)

        val code = booked.group!!.bookingCode!!
        val token = booked.reservations[0].admissionToken!!

        assertNotEquals(code, token)
        assertTrue(token.startsWith("BPT-"), "the entry credential is still the token: " + token)
        assertTrue(token.length > code.length,
                "the token is unguessable and the code is dictatable; if these ever converge in "
                        + "length, one of the two requirements has been dropped")
    }

    companion object {
        /** The alphabet generate_booking_code() draws from: every look-alike pair (0/O, 1/I/L, 5/S, 2/Z, 8/B) removed. */
        private const val CODE_PATTERN = "[23456789ACDEFGHJKMNPQRTVWXY]{8}"
    }
}
