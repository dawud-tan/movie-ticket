package com.protek.booking.service

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Turns the viability rule back on, and stops the scheduler running the sweep
 * behind the test's back.
 *
 * **Why the rule is off by default in the test profile at all.** It refuses a first hold
 * of one seat, and a large part of the suite — every concurrency test in particular — races
 * several callers for ONE seat, which is exactly the shape that demonstrates the `EXCLUDE`
 * constraint. Leaving it on globally would have meant rewriting those tests to be about two
 * seats, quietly changing what they prove. So the rule is enabled here, where it is the subject,
 * and `application.properties` says so beside the `%test` override.
 *
 * **The grace period is left at the production fifteen minutes, and that is deliberate.**
 * Shortening it to zero was the first attempt and it made the grace itself untestable: at
 * `PT0S`, `now() - interval` and `now() + interval` are the same instant, so
 * a sweep that applied the grace backwards — cancelling every screening fifteen minutes
 * *before* it starts — passed every scenario. Found by mutation, not by review. Tests
 * seed a showing that started twenty minutes ago (past the grace) or five (inside it) instead
 * of waiting for anything.
 */
class ViabilityRuleTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "booking.minimum-seats-per-showtime" to "2",
            "booking.showtime-viability-grace" to "PT15M",
            "quarkus.scheduler.enabled" to "false")
}
