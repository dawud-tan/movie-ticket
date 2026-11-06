package com.protek.booking.service

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Tenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Bulk seat-map entry against a real database.
 *
 * The load-bearing test here is the last one: a hall whose seats were generated must still
 * book through the untouched `EXCLUDE` path. The generator is a new way to create
 * `resource_unit` rows, and the one thing that must not change is that those rows behave
 * exactly like hand-entered ones in the reservation spine.
 */
@QuarkusTest
class SeatMapGeneratorTest {

    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var bookingService: BookingService
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var fixture: TestFixture

    /** CatalogService resolves its tenant from CurrentTenant rather than accepting one,
     * so fixtures exercised through the service layer must be built under the
     * configured tenant, not a fresh random one. */
    private fun hall(): ResourcePool = fixture.pool(currentTenant.get())

    @Test
    fun generatingAStudioCreatesEverySeatInOneGo() {
        val hall = hall()

        val created = catalogService.generateUniformSeats(hall.resourcePoolId, "A-J", 12)

        assertEquals(120, created)
        val seats = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(120, seats.size)
        assertEquals("A1", seats[0].label)
        assertEquals("J12", seats[119].label)
    }

    @Test
    fun anAisleSurvivesIntoTheDatabaseAsAGapInTheSequence() {
        val hall = hall()

        catalogService.generateSeats(hall.resourcePoolId, "C: 1-4, 7-9")

        val sequence = catalogService.listSeats(hall.resourcePoolId).map { it.sequenceNo }
        // Not 1..7 renumbered — the gap is what tells a future contiguous-block search that
        // seats 4 and 7 are on opposite sides of an aisle.
        assertEquals(listOf(1, 2, 3, 4, 7, 8, 9), sequence)
    }

    @Test
    fun rowsOfDifferingLengthAreCreatedAsDescribed() {
        val hall = hall()

        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4\nB: 1-8\nC: 1-2")

        val seats = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(14, seats.size)
        // Stream.count() is a long, which Java widened the expected int to.
        assertEquals(4L, seats.stream().filter { it.groupKey == "A" }.count())
        assertEquals(8L, seats.stream().filter { it.groupKey == "B" }.count())
        assertEquals(2L, seats.stream().filter { it.groupKey == "C" }.count())
    }

    @Test
    fun aPlanThatWouldDuplicateAnExistingSeatIsRefusedAndCreatesNothing() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        val e = assertThrows(IllegalArgumentException::class.java) {
            catalogService.generateSeats(hall.resourcePoolId, "A: 3-6\nB: 1-4")
        }

        assertTrue(e.message!!.contains("A3"), "message should name the clash: " + e.message)
        // The whole point of refusing rather than partially applying: row B must not exist,
        // even though it had no conflict of its own.
        val seats = catalogService.listSeats(hall.resourcePoolId)
        assertEquals(4, seats.size)
        assertTrue(seats.stream().noneMatch { it.groupKey == "B" })
    }

    @Test
    fun addingAMissingRowToAnAlreadyPopulatedHallIsAllowed() {
        // The flip side of refusing collisions: generation is additive, so a hall that gained
        // a row later does not have to be rebuilt.
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        val created = catalogService.generateSeats(hall.resourcePoolId, "B: 1-4")

        assertEquals(4, created)
        assertEquals(8, catalogService.listSeats(hall.resourcePoolId).size)
    }

    @Test
    fun previewReportsWhatWouldBeCreatedWithoutCreatingIt() {
        val hall = hall()

        val preview = catalogService.previewUniformSeats(hall.resourcePoolId, "A-C", 10)

        assertEquals(30, preview.seatCount())
        assertEquals(3, preview.rowCount)
        assertFalse(preview.hasConflicts())
        assertEquals(0, catalogService.listSeats(hall.resourcePoolId).size, "preview must not write")
    }

    @Test
    fun previewNamesTheSeatsThatWouldClash() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-3")

        val preview = catalogService.previewSeats(hall.resourcePoolId, "A: 2-5")

        assertTrue(preview.hasConflicts())
        assertEquals(listOf("A2", "A3"), preview.conflicts)
    }

    @Test
    fun cloningCopiesAnotherStudiosLayoutIncludingItsAisle() {
        val source = hall()
        val target = hall()
        catalogService.generateSeats(source.resourcePoolId, "A: 1-4, 7-10\nB: 1-6")

        val copied = catalogService.cloneSeatsFrom(target.resourcePoolId, source.resourcePoolId)

        assertEquals(14, copied)
        assertEquals(
            catalogService.listSeats(source.resourcePoolId)
                .map { s -> s.label + "/" + s.groupKey + "/" + s.sequenceNo },
            catalogService.listSeats(target.resourcePoolId)
                .map { s -> s.label + "/" + s.groupKey + "/" + s.sequenceNo })
    }

    @Test
    fun cloningAHallOntoItselfIsRefused() {
        val hall = hall()
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4")

        assertThrows(IllegalArgumentException::class.java) {
            catalogService.cloneSeatsFrom(hall.resourcePoolId, hall.resourcePoolId)
        }

        assertEquals(4, catalogService.listSeats(hall.resourcePoolId).size)
    }

    @Test
    fun cloningFromAnEmptyHallIsRefusedRatherThanSilentlyCopyingNothing() {
        val source = hall()
        val target = hall()

        assertThrows(IllegalArgumentException::class.java) {
            catalogService.cloneSeatsFrom(target.resourcePoolId, source.resourcePoolId)
        }
    }

    @Test
    fun aGeneratedHallStillBooksThroughTheUnchangedExclusionConstraint() {
        // The one that matters. Generation is a new way to write resource_unit rows; the
        // reservation spine must not be able to tell the difference.
        val tenant: Tenant = currentTenant.get()
        val hall: ResourcePool = fixture.pool(tenant)
        catalogService.generateSeats(hall.resourcePoolId, "A: 1-4, 7-10")
        fixture.rateRule(tenant, hall, BigDecimal("50000"))
        val showing: ResourcePoolSession = fixture.session(tenant, hall, "on_sale")

        val seats: List<ResourceUnit> = catalogService.listSeats(hall.resourcePoolId)
        val acrossTheAisle = seats[4]
        assertEquals("A7", acrossTheAisle.label)

        val first = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(acrossTheAisle.resourceUnitId),
            "First Customer", "+628120000001",
            AgeClassification.Attestation.NOT_TICKED)
        assertTrue(first.success, "a generated seat must be bookable")

        // And the constraint still refuses the second claim on that same generated seat.
        val second = bookingService.hold(hall.resourcePoolId,
            showing.resourcePoolSessionId, listOf(acrossTheAisle.resourceUnitId),
            "Second Customer", "+628120000002",
            AgeClassification.Attestation.NOT_TICKED)
        assertFalse(second.success, "the EXCLUDE constraint must still reject the double claim")
        assertEquals(acrossTheAisle.resourceUnitId, second.failedResourceUnitId)
    }
}
