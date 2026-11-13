package com.protek.booking.service

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * Stops the scheduler firing the sweeps in the background, so a test that invokes
 * `ReservationLifecycleService.sweep()` by hand is the only thing that ran it.
 *
 * The sweeps run every 30s by default and the suite takes minutes, so a background pass can
 * land between a test seeding its rows and asserting on them. Elsewhere that is only a narrow
 * flake window; for [ReservationLifecycleDifferentialTest] it would be worse than flaky —
 * the background pass runs with the ordinary superuser `Db`, so it would do the work the
 * test is trying to prove the *unprivileged* role can do, and the assertion would pass having
 * measured nothing.
 */
class SchedulerOffTestProfile : QuarkusTestProfile {
    override fun getConfigOverrides(): Map<String, String> =
        mapOf("quarkus.scheduler.enabled" to "false")
}
