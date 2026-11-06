package com.protek.booking.concurrency

import com.protek.booking.support.FakeGoogleOidc
import com.protek.booking.support.NativeItSupport
import io.quarkus.test.common.WithTestResource
import io.quarkus.test.junit.QuarkusIntegrationTest
import io.quarkus.test.junit.TestProfile
import io.restassured.RestAssured
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Black-box re-run of the seat-booking concurrency acceptance test against the
 * *packaged native binary* — `@QuarkusIntegrationTest` launches the actual
 * artifact (the native executable, when run under the `native` Maven profile) as a
 * separate OS process and drives it purely over HTTP, unlike [SeatBookingConcurrencyTest]
 * which exercises an in-JVM instance directly. This is what proves native-image compilation
 * didn't change the *outcome* of the `reservation_no_overlap` EXCLUDE constraint over
 * the real HTTP/JDBC stack, not just at the repository/JUnit level.
 *
 * Each racer is a distinct customer signed in through [FakeGoogleOidc]: the hold
 * endpoint is `@RolesAllowed`, and an anonymous POST is a 401 that no amount of
 * concurrency turns into a booking.
 */
@QuarkusIntegrationTest
@TestProfile(NativeItProfile::class)
@WithTestResource(FakeGoogleOidc::class)
class SeatBookingConcurrencyIT {

    companion object {
        private val POOL_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val SEAT_PATTERN = Pattern.compile("name=\"seatIds\" value=\"([0-9a-fA-F-]{36})\"")
        private const val CONCURRENT_REQUESTS = 8
        private val ERROR_BANNER = Pattern.compile("<div class=\"error-banner\">([^<]*)</div>")
    }

    @Test
    fun eightConcurrentHoldsOnIdenticalSeat_exactlyOneSucceeds() {
        val seatId = findAvailableSeatId()
        // Signed in before the barrier, so the race is between holds and not between sign-ins.
        val sessions = ArrayList<Map<String, String>>()
        for (i in 0 until CONCURRENT_REQUESTS) {
            sessions.add(NativeItSupport.signIn("racer-" + i + "@it.example"))
        }

        val pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)
        val barrier = CyclicBarrier(CONCURRENT_REQUESTS)
        val futures = ArrayList<Future<Boolean>>()
        val refusals: MutableList<String> = Collections.synchronizedList(ArrayList())
        for (i in 0 until CONCURRENT_REQUESTS) {
            val n = i
            val task = Callable<Boolean> {
                barrier.await()
                val response = RestAssured.given()
                        .redirects().follow(false)
                        .cookies(sessions[n])
                        .formParam("seatIds", seatId)
                        // The demo film is rated 13+, so the age attestation is required; the
                        // browser sends this tick. Without it all eight were refused.
                        .formParam("ageAttested", "true")
                        .formParam("customerName", "Racer " + n)
                        .formParam("customerPhone", "+6280000" + String.format("%04d", n))
                        .`when`()
                        .post("/halls/{poolId}/showings/{sessionId}/hold", POOL_ID, SESSION_ID)
                if (response.getHeader("HX-Redirect") != null) {
                    return@Callable true
                }
                // Kept for the assertion below: "0 succeeded" alone says nothing about why.
                val banner = ERROR_BANNER.matcher(response.body.asString())
                refusals.add(response.statusCode.toString() + " " + (if (banner.find()) banner.group(1) else "(no error banner)"))
                false
            }
            futures.add(pool.submit(task))
        }

        var successCount = 0
        for (f in futures) {
            if (f.get(30, TimeUnit.SECONDS)) {
                successCount++
            }
        }
        pool.shutdown()

        assertEquals(1, successCount, "expected exactly one of " + CONCURRENT_REQUESTS
                + " concurrent holds on the identical seat to succeed against the native binary; refusals: "
                + refusals)
    }

    private fun findAvailableSeatId(): String {
        val html = RestAssured.given()
                .`when`()
                .get("/halls/{poolId}/showings/{sessionId}/fragment", POOL_ID, SESSION_ID)
                .asString()
        val m = SEAT_PATTERN.matcher(html)
        assertTrue(m.find(), "no available seat found in seat map fragment: " + html)
        return m.group(1)
    }
}
