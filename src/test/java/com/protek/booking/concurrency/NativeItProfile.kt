package com.protek.booking.concurrency

import io.quarkus.test.junit.QuarkusTestProfile

/**
 * What a `@QuarkusIntegrationTest` needs that `%test` gives the JVM suite. The
 * binary runs the prod profile, so `%test.booking.minimum-seats-per-showtime=1` never
 * reaches it, and the viability rule (a showing's first booking takes at least 2 seats) then
 * correctly refuses a racer's lone one-seat hold. Every racer was refused, so "exactly one wins"
 * read 0. The rule is switched off here as it is for the JVM suite, and nothing
 * else about the shipped configuration changes.
 */
class NativeItProfile : QuarkusTestProfile {

    companion object {
        val OVERRIDES: Map<String, String> = mapOf("booking.minimum-seats-per-showtime" to "1")
    }

    override fun getConfigOverrides(): Map<String, String> = OVERRIDES
}
