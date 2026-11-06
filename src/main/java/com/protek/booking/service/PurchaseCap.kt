package com.protek.booking.service

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * The purchase caps: how many seats one self-service hold may ask for, and how
 * many seats one customer may hold for one calendar day. The incumbent's stated policy is 8 per
 * showtime and 10 per day, which are the defaults here.
 *
 * **Why this is a bean and not just a `@ConfigProperty` on [BookingService].**
 * Two places need the numbers — the service that refuses the hold, and the seat map that tells
 * the customer the limits before they spend a minute ticking seats. Injecting the properties
 * twice would let the enforced number and the displayed number drift, and the failure mode of
 * that is the worst kind: a page that says 8 and a server that refuses at 6. The seat map reads
 * this bean by name (`{inject:purchaseCap...}`, the same mechanism `SignInLink`
 * uses), so there is exactly one value of each.
 *
 * **Deliberately not enforced in the browser.** `seatmap-selection.js` states as a
 * rule that nothing in it alters the selection or blocks a submit — the server is the authority
 * and the DOM is already correct by the time it runs. A cap that disabled checkboxes would be
 * the first exception to that, and it would still have to exist here anyway, because the hold
 * endpoint takes a form POST that nothing stops a caller sending directly. The per-day cap
 * could not be enforced there in any case: the browser does not know what the customer booked
 * this morning.
 */
@Named("purchaseCap")
@ApplicationScoped
class PurchaseCap {

    // A public field, as it was in Java. It shares its name with the
    // method below, and the seat map's {inject:purchaseCap.maxSeatsPerHold} reaches either one;
    // both read the same int.
    @ConfigProperty(name = "booking.max-seats-per-hold", defaultValue = "8")
    @JvmField
    final var maxSeatsPerHold: Int = 8

    @ConfigProperty(name = "booking.max-seats-per-day", defaultValue = "10")
    @JvmField
    final var maxSeatsPerDay: Int = 10

    fun maxSeatsPerHold(): Int = maxSeatsPerHold

    fun maxSeatsPerDay(): Int = maxSeatsPerDay

    /**
     * Whether the per-hold cap is in force at all. The seat map asks this rather than comparing
     * to zero in a template, so a tenant who has turned the cap off gets no hint rather than a
     * hint reading "up to 0 seats".
     */
    fun enforced(): Boolean = maxSeatsPerHold > UNCAPPED

    /** The same question for the per-day cap, which the seat map states on its own line. */
    fun dailyEnforced(): Boolean = maxSeatsPerDay > UNCAPPED

    /** What the self-service channel enforces — [BookingService.hold]'s policy. */
    fun limits(): Limits = Limits(maxSeatsPerHold, maxSeatsPerDay)

    /**
     * One channel's purchase policy, passed into the booking path rather than read from this
     * bean there.
     *
     * **A record rather than two `int` parameters**, and that is not tidiness: two
     * adjacent unnamed ints of the same type are transposable at every call site and by every
     * future caller, and transposing these two produces a system that caps a booking at 10 seats
     * and a day at 8 — plausible enough to survive review and wrong in a direction nobody would
     * think to test.
     *
     * @param perHold the ceiling on one request, or [UNCAPPED].
     * @param perDay the ceiling on one customer's seats for one calendar day, or
     *        [UNCAPPED].
     */
    @JvmRecord
    data class Limits(val perHold: Int, val perDay: Int) {

        fun capsHold(): Boolean = perHold > UNCAPPED

        fun capsDay(): Boolean = perDay > UNCAPPED

        companion object {
            /**
             * The counter's policy: neither cap applies. Named rather than spelled
             * `new Limits(UNCAPPED, UNCAPPED)` at the call site so that "this channel is
             * deliberately exempt" is legible, and so a fourth booking entry point cannot inherit
             * "no cap" by saying nothing — every caller has to name a policy to compile.
             */
            @JvmField
            val NONE = Limits(UNCAPPED, UNCAPPED)
        }
    }

    companion object {
        /**
         * What [Limits.NONE] carries in both fields: no ceiling at all. Also what a tenant
         * sets either property to when they want that cap off, so "off" and "counter sale" are the
         * same value rather than two ways of saying nothing.
         */
        const val UNCAPPED = 0
    }
}
