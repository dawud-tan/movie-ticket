package com.protek.booking.web.customer

import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Tenant
import com.protek.booking.service.CatalogService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.support.TestFixture
import io.agroal.api.AgroalDataSource
import io.quarkus.test.junit.QuarkusTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

/**
 * What one seat-map render costs.
 *
 * The seat map is the only page this application polls, so its render is the one piece of
 * work whose cost is multiplied by every open tab: 0.33 requests a second each, forever, with
 * nobody pressing anything. It used to read itself four times over — the hall, the showing, the
 * map, the price — each through its own `CatalogService` method and therefore each in its
 * own transaction, paying an RLS `set_config` and a `COMMIT` for one query.
 *
 * **Why a connection count and not a stopwatch.** This change is worth doing "for clarity,
 * not speed": with the database on a Unix socket beside the app,
 * three saved transactions are microseconds, and a latency assertion would be a flake generator
 * measuring the machine rather than the code. The number of connections a render takes is the
 * thing that actually changed, it is exact, and it is what a later refactor would silently undo
 * — a reader who "tidies" the combined read back into four service calls gets four here.
 *
 * The counters come from Agroal, enabled by [PoolMetricsTestProfile]. Each
 * `Db.inTransaction` does exactly one `dataSource.getConnection()`, so an acquire
 * is a transaction.
 */
@QuarkusTest
@TestProfile(PoolMetricsTestProfile::class)
class SeatMapRenderTest {

    companion object {
        private val SEAT_PRICE = BigDecimal("50000")
    }

    @Inject
    lateinit var fixture: TestFixture
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var catalogService: CatalogService
    @Inject
    lateinit var dataSource: AgroalDataSource

    private data class Showing(val hall: ResourcePool, val session: ResourcePoolSession) {
        fun seatMapUrl(): String =
            "/halls/" + hall.resourcePoolId + "/showings/" + session.resourcePoolSessionId
    }

    /** A hall of three seats with a flat price, in the tenant whose catalogue this deployment
     * publishes — which is the tenant an anonymous request resolves to. */
    private fun showing(row: String): Showing {
        val tenant: Tenant = currentTenant.get()
        val hall: ResourcePool = fixture.pool(tenant)
        for (i in 1..3) {
            fixture.unit(tenant, hall, row + i, row, i)
        }
        fixture.rateRule(tenant, hall, SEAT_PRICE)
        return Showing(hall, fixture.session(tenant, hall, "on_sale"))
    }

    private fun acquired(): Long = dataSource.metrics.acquireCount()

    @Test
    fun onePollOfTheSeatMapTakesOneConnection_whereItUsedToTakeFour() {
        val showing = showing("M")

        // Warm-up, and a check on the instrument itself. The first request resolves the
        // configured tenant, which DefaultTenant then caches for the life of the application —
        // counting that lookup would make this assert 2 and read as the lever having failed.
        // The counter moving at all is also the only evidence that PoolMetricsTestProfile is in
        // effect: with metrics off Agroal reports a flat zero, and every assertion below would
        // pass against a render that took forty connections.
        val beforeWarmup = acquired()
        given().`when`().get(showing.seatMapUrl() + "/fragment").then().statusCode(200)
        assertTrue(acquired() > beforeWarmup,
            "Agroal is not counting acquisitions — is PoolMetricsTestProfile applied? "
                    + "Without it this whole class asserts nothing.")

        val before = acquired()
        given().`when`().get(showing.seatMapUrl() + "/fragment").then().statusCode(200)

        // 1L: the Java widened 1 to a long; a bare 1 would compare an Integer with a Long.
        assertEquals(1L, acquired() - before,
            "The polled fragment should read the hall, the showing, the seat map and the price "
                    + "in one transaction")
    }

    @Test
    fun theCounterSeatPickerReadsTheSameFourThingsInOneTransactionToo() {
        val showing = showing("C")
        catalogService.getSeatMapPageForShowing(showing.session.resourcePoolSessionId)

        val before = acquired()
        val page: CatalogService.SeatMapPage =
            catalogService.getSeatMapPageForShowing(showing.session.resourcePoolSessionId)

        assertEquals(1L, acquired() - before, "The counter's seat picker renders from one transaction")
        // Asserted here rather than taken on trust: the counter path finds its hall THROUGH the
        // showing, so a wrong turn gives a picker for another hall's seats and staff sell a seat
        // in the wrong room.
        assertEquals(showing.hall.resourcePoolId, page.hall.resourcePoolId)
        assertEquals(3, page.seats.size)
        assertEquals(0, SEAT_PRICE.compareTo(page.price!!.amount!!))
    }

    @Test
    fun collapsingTheFourReadsLeftTheFragmentSayingWhatItAlwaysSaid() {
        val showing = showing("K")

        given().`when`().get(showing.seatMapUrl() + "/fragment")
            .then().statusCode(200)
            // Every seat, from the read that used to be its own getSeatMap() call...
            .body(containsString("K1")).body(containsString("K2")).body(containsString("K3"))
            // ...the price, which used to be its own getPriceDisplay() call...
            .body(containsString("Rp 50.000 per seat"))
            // ...and the hall and showing, which the poll URL is built out of, so losing
            // either would leave a fragment that renders once and then stops refreshing.
            .body(containsString(showing.seatMapUrl() + "/fragment"))
    }

    @Test
    fun aHallThatIsNotThereIsStillANotFound_notAnEmptySeatMap() {
        val showing = showing("N")

        given().`when`().get("/halls/" + UUID.randomUUID() + "/showings/"
                + showing.session.resourcePoolSessionId + "/fragment")
            .then().statusCode(404)
    }

    @Test
    fun aShowingThatIsNotThereIsStillANotFound() {
        val showing = showing("P")

        given().`when`().get("/halls/" + showing.hall.resourcePoolId + "/showings/"
                + UUID.randomUUID() + "/fragment")
            .then().statusCode(404)
    }
}
