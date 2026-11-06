package com.protek.booking.web.customer

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Turns on Agroal's pool counters, so a test can assert how many connections a request took.
 *
 * Off by default in Quarkus unless a metrics extension is present, and this application has
 * none — so the counters read a flat zero without this. [SeatMapRenderTest] checks that
 * they move before trusting them, because a silently-zero counter would make every "took one
 * connection" assertion pass against a render that took forty.
 *
 * The scheduler is stopped for the same reason `SchedulerOffTestProfile` stops it: the
 * hold-expiry and completion sweeps run every 30s, they take connections of their own, and one
 * landing between the before and after readings would be indistinguishable from the render
 * having done more work.
 */
class PoolMetricsTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf(
            "quarkus.datasource.jdbc.metrics.enabled" to "true",
            "quarkus.scheduler.enabled" to "false")
}
