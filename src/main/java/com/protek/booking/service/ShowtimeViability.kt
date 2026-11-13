package com.protek.booking.service

import com.protek.booking.i18n.Msg
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Duration

/**
 * A cinema's viability rule, in the two places it applies.
 *
 * **Where it comes from.** Recorded at a small-town cinema branch on Saturday 22 August 2026:
 * a screening only runs if at least two seats are sold, and one that has not sold two by
 * fifteen minutes past its advertised start is cancelled. It reads oddly from a city-cinema
 * point of view and it is not arbitrary — a projectionist, an usher and a room's worth of
 * electricity cost the same for one ticket as for two, and a small-town branch running a
 * near-empty late show loses money on every one of them. It is the same shape as the minimum
 * covers a restaurant applies to a table.
 *
 * **Two halves that must agree, which is why they are one bean.** The seat map tells the
 * customer they need two before they tick anything, `BookingService` refuses a first
 * hold of one, and the sweep cancels a showing that never got two. Three readers of the same
 * number — exactly the arrangement [PurchaseCap] exists to prevent drifting, and for the
 * same reason: a page that says two and a server that refuses at three is the worst kind of
 * wrong.
 *
 * **Both numbers are configuration, defaulting to what that cinema actually does.** This is a
 * multi-tenant platform and the next tenant may not have this rule at all — setting the
 * minimum to 1 turns the whole thing off, since
 * one seat is then never below the minimum and no showing can ever be under-sold.
 */
@Named("showtimeViability")
@ApplicationScoped
class ShowtimeViability {

    /**
     * The fewest seats a showing has to sell to run. `1` disables both halves of the
     * rule: a hold of one seat is never below it, and no showing can ever be under-sold.
     */
    // A public field, as PurchaseCap's: lateinit cannot hold an Int. It shares its
    // name with the method below, and {inject:showtimeViability.minimumSeats} reaches either
    // one; both read the same int.
    @ConfigProperty(name = "booking.minimum-seats-per-showtime", defaultValue = "2")
    @JvmField
    final var minimumSeats: Int = 2

    /**
     * How long after the advertised start an under-sold showing is given before it is
     * cancelled.
     *
     * Measured from `starts_at`, which is what "15 minutes after display time" means
     * at the branch: the doors are open, the trailers are running, and the decision is whether
     * to start the projector. Not from when the schedule was published — that would cancel next
     * month's listings the moment they went up.
     */
    @ConfigProperty(name = "booking.showtime-viability-grace", defaultValue = "PT15M")
    lateinit var grace: Duration

    fun minimumSeats(): Int = minimumSeats

    fun grace(): Duration = grace

    /** Whether the rule is in force at all. Read by the seat map so a tenant who has turned it
     * off gets no notice rather than one reading "at least 1 seat". */
    fun enforced(): Boolean = minimumSeats > 1

    /** The grace period as whole minutes, for the sentence the seat map shows. Minutes rather
     * than a `Duration#toString` that renders "PT15M" at a customer. */
    fun graceMinutes(): Long = grace.toMinutes()

    /**
     * Whether a hold of `requested` seats is allowed to be the *first* on a
     * showing that currently has `alreadyLive` seats claimed.
     *
     * **Only the first buyer is bound, and that is the rule as the branch states it.**
     * Once two seats are sold the showing runs, so the third ticket has nothing to prove; a
     * customer arriving alone to a screening that is already viable is an ordinary sale and
     * refusing them would cost the branch money for no reason. That asymmetry is the whole of
     * why this takes `alreadyLive` rather than being a flat minimum on every hold.
     */
    fun permitsHold(alreadyLive: Int, requested: Int): Boolean {
        if (!enforced() || alreadyLive > 0) {
            return true
        }
        return requested >= minimumSeats
    }

    /** What the customer is told when [permitsHold] says no. Built here so the refusal
     * and the seat map's up-front notice cannot describe different rules.
     *
     * The bundle is handed in rather than injected: this class is consulted from inside
     * `BookingService`'s transaction lambda, which already holds the `Msg` it
     * resolved once for the request, and a second lookup here would be a second answer to the
     * same question. */
    fun refusal(msg: Msg, requested: Int): String =
        msg.holdFirstBuyerMinimum(minimumSeats, graceMinutes(), requested)
}
