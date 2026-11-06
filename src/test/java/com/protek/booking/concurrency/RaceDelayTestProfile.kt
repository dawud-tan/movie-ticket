package com.protek.booking.concurrency

import io.quarkus.test.junit.QuarkusTestProfile

/** Activates the test-only race-window seam in `BookingService.hold` —
 * inert (0ms) in every other profile. */
class RaceDelayTestProfile : QuarkusTestProfile {

    companion object {
        const val RACE_DELAY_MS = 2000L
    }

    override fun getConfigOverrides(): Map<String, String> {
        // NativeItProfile's override too: SessionCancellationRaceIT runs under this profile
        // against the prod-profile binary. Inert for the JVM tests, where %test already sets it.
        val overrides = HashMap(NativeItProfile.OVERRIDES)
        overrides["booking.test.race-delay-ms"] = RACE_DELAY_MS.toString()
        return overrides
    }
}
