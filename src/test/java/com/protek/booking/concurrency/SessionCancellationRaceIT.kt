package com.protek.booking.concurrency

import com.protek.booking.support.FakeGoogleOidc
import com.protek.booking.support.NativeItSupport
import io.quarkus.test.common.DevServicesContext
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusIntegrationTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern

/**
 * Black-box re-run of the session-status race fix against the packaged native binary.
 * Mirrors [SessionCancellationRaceTest]'s intent, but `@QuarkusIntegrationTest`
 * launches the native executable as a separate OS process, so there is no CDI injection —
 * everything here goes over real HTTP. Reuses the same `booking.test.race-delay-ms`
 * seam (`BookingService.hold`) via the same [RaceDelayTestProfile]; Quarkus
 * translates a `QuarkusTestProfile`'s config overrides into environment variables for
 * the launched process, so the (runtime-read, not build-time-fixed) config property is
 * overridable even in a native image.
 *
 * Creates its own dedicated showing rather than reusing the shared demo session, so this
 * test is free to cancel it as its final state without any ordering hazard against
 * [SeatBookingConcurrencyIT], which targets the shared demo session/seats in the same
 * `mvn verify` run.
 */
@QuarkusIntegrationTest
@TestProfile(RaceDelayTestProfile::class)
@WithTestResource(FakeGoogleOidc::class)
class SessionCancellationRaceIT {

    companion object {
        private val POOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val SEAT_PATTERN = Pattern.compile("name=\"seatIds\" value=\"([0-9a-fA-F-]{36})\"")

        // The seeded tenant_staff row. The admin endpoints are @RolesAllowed, and staff are never
        // auto-provisioned, so this is the only address that reaches them.
        private const val STAFF = "staff@demo-cinema.example"
    }

    // No CDI here, but Quarkus sets a field of exactly this type on an integration test before
    // each test (TestResourceManager.inject): the Dev Services properties the binary was given,
    // the database's JDBC URL and credentials among them.
    lateinit var devServices: DevServicesContext

    @Test
    fun cancellationDuringHold_blocksUntilHoldCommits_evenAgainstNativeBinary() {
        val staff = NativeItSupport.signIn(STAFF)
        val customer = NativeItSupport.signIn("race-customer@it.example")
        val sessionId = createFreshShowing(staff)
        val seatId = findAvailableSeatId(sessionId)

        val holdStartedAt = AtomicReference<Instant>()
        val holdCompletedAt = AtomicReference<Instant>()
        val cancelCompletedAt = AtomicReference<Instant>()
        val holdSucceeded = AtomicReference<Boolean>()
        // Int?, so assertEquals(303, …) takes JUnit's (int, Integer) overload, as the Java did.
        val cancelStatus = AtomicReference<Int?>()
        val holdError = AtomicReference<Exception>()
        val cancelError = AtomicReference<Exception>()

        val holdThread = Thread({
            try {
                holdStartedAt.set(Instant.now())
                val response = RestAssured.given()
                        .redirects().follow(false)
                        .cookies(customer)
                        .formParam("seatIds", seatId)
                        .formParam("customerName", "Race Customer")
                        .formParam("customerPhone", "+62800009999")
                        .`when`()
                        .post("/halls/{poolId}/showings/{sessionId}/hold", POOL_ID, sessionId)
                holdSucceeded.set(response.getHeader("HX-Redirect") != null)
                holdCompletedAt.set(Instant.now())
            } catch (e: Exception) {
                holdError.set(e)
            }
        }, "it-hold-thread")

        val cancelThread = Thread({
            try {
                // Head start for the hold thread: by the time this fires, the native
                // process must already be past its FOR UPDATE read and into the
                // race-delay-injected sleep, or this isn't testing "lands between read
                // and insert" at all.
                Thread.sleep(400)
                val response = RestAssured.given()
                        .redirects().follow(false)
                        .cookies(staff)
                        .formParam("status", "cancelled")
                        .`when`()
                        .post("/admin/showings/{sessionId}/status", sessionId)
                cancelStatus.set(response.statusCode)
                cancelCompletedAt.set(Instant.now())
            } catch (e: Exception) {
                cancelError.set(e)
            }
        }, "it-cancel-thread")

        holdThread.start()
        cancelThread.start()
        // Inside the hold's window, and after the cancel has queued behind it: the cancel's
        // UPDATE once more, run where it can say why it waits. Asserted after the joins.
        Thread.sleep(700)
        val probe = cancelUpdateUnderLockTimeout(sessionId)
        holdThread.join(20_000)
        cancelThread.join(20_000)

        assertNull(holdError.get(), "hold thread threw: " + holdError.get())
        assertNull(cancelError.get(), "cancel thread threw: " + cancelError.get())
        assertNotNull(holdCompletedAt.get())
        assertNotNull(cancelCompletedAt.get())
        assertEquals(303, cancelStatus.get())
        assertTrue(holdSucceeded.get(), "the hold started while the session was genuinely on_sale, so it must succeed")

        // The direct proof, which the timing below cannot give: mid-window, the cancel's UPDATE
        // was waiting on a lock in the native binary's database. 55P03 is lock_timeout expiring,
        // which only a lock wait raises, so a cancel held up some other way cannot pass for one.
        // The timing alone would accept that, with the session lock gone (SessionCancellationRaceTest has the same probe).
        assertEquals("55P03", probe?.sqlState,
                "the cancel's UPDATE, tried 700 ms into the hold's " + RaceDelayTestProfile.RACE_DELAY_MS
                        + " ms window, must be waiting on the session row lock; it "
                        + (if (probe == null) "went straight through, so nothing held the row"
                           else "failed for another reason: " + probe.message))

        // The proof that FOR UPDATE actually serialized the two requests in the native
        // process: the cancellation's UPDATE could only have completed once the hold's row
        // lock was released at commit, which is after the hold's injected sleep. Unblocked,
        // it would answer ~400 ms after the hold started. Not "cancel answered after hold":
        // these are client-side clocks read after each HTTP response, and once the hold
        // commits both responses race back — on 2026-10-09 the cancel's arrived 1.1 ms
        // first in a run that took 2.8 s, i.e. one where the lock plainly held.
        val earliestBlockedFinish = holdStartedAt.get().plusMillis(RaceDelayTestProfile.RACE_DELAY_MS)
        assertFalse(cancelCompletedAt.get().isBefore(earliestBlockedFinish),
                "cancellation completed at " + cancelCompletedAt.get() + ", before the hold's "
                        + RaceDelayTestProfile.RACE_DELAY_MS + " ms race window ended at " + earliestBlockedFinish
                        + " — it was not blocked by the session row lock in the native binary")
    }

    /** `ResourcePoolSessionRepository.updateStatus`'s UPDATE, which the cancel endpoint runs, on
     * the test's own JDBC connection to the Dev Services database the binary uses. It gives up after
     * 500 ms of waiting for a lock and is always rolled back, so it never cancels anything.
     * Returns what it threw, or null if it went through. 700 + 500 ms ends well before the hold's
     * 2000 ms window does. */
    private fun cancelUpdateUnderLockTimeout(sessionId: UUID): SQLException? {
        val props = devServices.devServicesProperties()
        val url = props["quarkus.datasource.jdbc.url"]
            ?: throw IllegalStateException("no Dev Services JDBC URL among " + props.keys)
        DriverManager.getConnection(url, props["quarkus.datasource.username"],
                props["quarkus.datasource.password"]).use { c ->
            c.autoCommit = false
            try {
                // The demo tenant's id, read off the showing rather than written down twice.
                val tenantId = c.prepareStatement(
                        "SELECT tenant_id FROM resource_pool_session WHERE resource_pool_session_id = ?").use { ps ->
                    ps.setObject(1, sessionId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1, UUID::class.java)
                    }
                }
                c.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
                    ps.setString(1, tenantId.toString())
                    ps.execute()
                }
                c.prepareStatement("SET LOCAL lock_timeout = '500ms'").use { ps -> ps.execute() }
                c.prepareStatement(
                        "UPDATE resource_pool_session SET status = ? WHERE tenant_id = ? AND resource_pool_session_id = ?").use { ps ->
                    ps.setString(1, "cancelled")
                    ps.setObject(2, tenantId)
                    ps.setObject(3, sessionId)
                    ps.executeUpdate()
                }
                return null
            } catch (e: SQLException) {
                return e
            } finally {
                c.rollback()
            }
        }
    }

    private fun createFreshShowing(staff: Map<String, String>): UUID {
        val label = "IT-Race-" + UUID.randomUUID()
        val startsAt = LocalDateTime.now().plusDays(30).withHour(19).withMinute(0).withSecond(0).withNano(0)
        val endsAt = startsAt.plusHours(2)

        RestAssured.given()
                .redirects().follow(false)
                .cookies(staff)
                .formParam("label", label)
                .formParam("startsAt", startsAt.toString())
                .formParam("endsAt", endsAt.toString())
                .`when`()
                .post("/admin/halls/{poolId}/showings", POOL_ID)
                .then().statusCode(303)

        val html = RestAssured.given()
                .cookies(staff)
                .`when`()
                .get("/admin/halls/{poolId}", POOL_ID)
                .asString()
        val p = Pattern.compile("<td>" + Pattern.quote(label) + "</td>.*?/admin/showings/([0-9a-fA-F-]{36})/reservations",
                Pattern.DOTALL)
        val m = p.matcher(html)
        assertTrue(m.find(), "could not find newly created showing '" + label + "' in admin hall page")
        return UUID.fromString(m.group(1))
    }

    private fun findAvailableSeatId(sessionId: UUID): String {
        val html = RestAssured.given()
                .`when`()
                .get("/halls/{poolId}/showings/{sessionId}/fragment", POOL_ID, sessionId)
                .asString()
        val m = SEAT_PATTERN.matcher(html)
        assertTrue(m.find(), "no available seat found in seat map fragment: " + html)
        return m.group(1)
    }
}
