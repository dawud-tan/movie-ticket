package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.BookingDraftRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.repository.TenantRepository
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * The two reservation-lifecycle transitions with no live HTTP request to piggyback on,
 * swept on a short interval.
 *
 * **`held → expired`** — nothing actively changes a hold when
 * `hold_expires_at` passes; a customer who abandons a hold never calls anything.
 *
 * **A showing cancelled for want of two seats** — the showtime viability rule. Nobody is
 * waiting on a response fifteen minutes after a screening was due to start; the customer who
 * would have triggered it is the one who did not turn up.
 *
 * **`confirmed → completed`** — the reservation lifecycle has
 * `confirmed --> completed : showing date has passed`, and until this was added nothing
 * in the codebase, no scheduled job, and no database trigger ever produced `'completed'`.
 * The status was permitted by V1's CHECK constraint and part of the lifecycle, but never set on
 * a real row: a reservation for a showing that had come and gone stayed `confirmed`
 * indefinitely. Two consequences that made this worth closing rather than leaving:
 * `reservation_no_overlap` filters on `status IN ('held','confirmed')`, so every
 * past booking stayed a live claim on its seat and the constraint's index never shed settled
 * history; and data-retention rules give `cancelled` and `completed` different retention
 * justifications, which cannot be applied to rows that all sit in `confirmed`.
 *
 * Formerly `HoldExpiryService`, renamed once it covered a second transition — a class
 * named for one of the two things it does is the kind of drift that makes the next reader look
 * in the wrong place.
 *
 * Both sweeps iterate every tenant rather than the configured one. Scoped to a single
 * tenant, a shared pool instance would leave every other tenant's stale holds blocking seats
 * forever, and their past bookings never completing. Setting tenant context per tenant rather
 * than issuing one cross-tenant UPDATE is forced by `FORCE ROW LEVEL SECURITY` and is
 * the right shape anyway: there is no way to write a cross-tenant update by accident.
 */
@ApplicationScoped
class ReservationLifecycleService {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var tenantRepository: TenantRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var watchRepository: ResourcePoolWatchRepository
    @Inject
    lateinit var sessionRepository: ResourcePoolSessionRepository
    @Inject
    lateinit var draftRepository: BookingDraftRepository
    @Inject
    lateinit var notifier: SeatAvailabilityNotifier
    @Inject
    lateinit var viability: ShowtimeViability

    // Public where the Java was package-private: Kotlin has none, and ShowtimeViabilityTest and
    // ReservationLifecycleDifferentialTest run the sweep by hand.
    @Scheduled(every = "{booking.hold-expiry-sweep.interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    fun sweep() {
        // tenant carries no RLS policy, so this read needs no tenant context — and it is
        // the only kind of cross-tenant read the schema permits.
        val tenants: List<Tenant> = db.inTransaction(tenantRepository::findAll)
        var totalExpired = 0
        var totalCompleted = 0
        var totalWatchesPruned = 0
        var totalShowingsCancelled = 0
        var totalDraftsPruned = 0
        for (tenant in tenants) {
            // Suspended and offboarding tenants still get swept: their holds should still
            // expire and their past showings still complete, since leaving them otherwise
            // would misrepresent both seat availability and what actually took place in
            // whatever final reporting or export follows.
            try {
                totalExpired += expireHoldsFor(tenant)
                totalCompleted += completeShowingsFor(tenant)
                totalWatchesPruned += pruneExpiredWatchesFor(tenant)
                totalShowingsCancelled += cancelUnderSoldShowingsFor(tenant)
                totalDraftsPruned += pruneExpiredDraftsFor(tenant)
            } catch (e: RuntimeException) {
                // One tenant's failure must not stop the others — on a shared pool instance
                // that would turn a single tenant's problem into every tenant's unsellable
                // seats.
                LOG.errorf(e, "Reservation lifecycle sweep failed for tenant %s (%s); continuing " +
                        "with remaining tenants", tenant.slug, tenant.tenantId)
            }
        }
        if (totalExpired > 0 || totalCompleted > 0 || totalWatchesPruned > 0 ||
                totalShowingsCancelled > 0 || totalDraftsPruned > 0) {
            LOG.infof("Reservation lifecycle sweep across %d tenant(s): expired %d stale hold(s), " +
                    "completed %d past reservation(s), pruned %d expired watch(es), " +
                    "cancelled %d under-sold showing(s), pruned %d expired booking draft(s)",
                    tenants.size, totalExpired, totalCompleted, totalWatchesPruned,
                    totalShowingsCancelled, totalDraftsPruned)
        }
    }

    /**
     * Separate transactions per transition, not one covering both. They are independent facts
     * about different rows, and failing to complete last month's showings is no reason to leave
     * this minute's stale holds blocking seats — which is exactly what a shared transaction
     * would do on a rollback.
     *
     * Notifies each affected seat map afterwards. This is the one case with no
     * live request to piggyback on, a hold expiring on its own — nobody is waiting on a response
     * to carry the news, so without this the seats silently become available and every open seat
     * map keeps showing them as held until someone reloads.
     */
    private fun expireHoldsFor(tenant: Tenant): Int {
        val affected: List<ReservationRepository.AffectedSeatMap> = db.inTenantTransaction(
                tenant.tenantId) { c -> reservationRepo.expireStaleHoldsReturningSeatMaps(c, tenant.tenantId) }
        // After the transaction returns, so a watcher who reacts by refetching the seat map sees
        // the expiry that prompted the push rather than racing it.
        for (seatMap in affected) {
            notifier.notifyWatchers(tenant.tenantId, seatMap.resourcePoolId,
                    seatMap.resourcePoolSessionId, SeatAvailabilityNotifier.Reason.HOLDS_EXPIRED)
        }
        return affected.size
    }

    /** The safety net for watches whose page closed without unregistering — a crash, a killed
     * browser, a dropped connection. The client's own delete on page close is the primary path;
     * this stops abandoned rows accumulating in the lookup index forever. */
    private fun pruneExpiredWatchesFor(tenant: Tenant): Int =
        db.inTenantTransaction(tenant.tenantId) { c -> watchRepository.deleteExpired(c, tenant.tenantId) }

    /**
     * The showtime viability rule, second half: a showing that has not sold `minimumSeats`
     * by `grace` past its advertised start is cancelled.
     *
     * **Why this is a sweep and not a scheduled job per showing.** There is no moment a
     * request arrives to carry it — the decision is triggered by an absence. Per-showing timers
     * would also have to survive a restart and be cancelled when a showing is rescheduled,
     * where a sweep re-derives the answer from the rows every time it runs.
     *
     * **Why the query does the deciding and this method only writes.**
     * `findStartedAndUndersold` counts and locks in one statement, so nothing can sell
     * the second seat between the count and the cancel. Splitting it — read the showings, then
     * count each — would reintroduce exactly the race the `FOR UPDATE` closes, and the
     * failure would be a screening cancelled out from under somebody who had just paid.
     *
     * **Live seats are left alone, and there should never be any.** A showing with a live
     * seat is by definition not below a minimum of one, and a minimum of two with one seat sold
     * cannot happen at all: the first-buyer rule in `BookingService` refuses that hold.
     * So this cancels showings with *zero* claims in practice, and the query is written
     * as a threshold rather than as "= 0" so that a tenant who raises the minimum later does
     * not silently get a rule that never fires.
     *
     * **Every affected seat map is notified**, for the same reason: nobody is waiting on a
     * response, so without this a customer with the seat map open watches a cancelled showing
     * indefinitely. `SHOWING_CANCELLED` rather than reusing `HOLDS_EXPIRED` —
     * "these seats are free again" and "this screening is not happening" are different news.
     */
    private fun cancelUnderSoldShowingsFor(tenant: Tenant): Int {
        if (!viability.enforced()) {
            return 0
        }
        val cancelled: List<ResourcePoolSession> = db.inTenantTransaction(tenant.tenantId) { c ->
            val undersold = sessionRepository.findStartedAndUndersold(
                    c, tenant.tenantId, viability.grace(), viability.minimumSeats())
            for (showing in undersold) {
                sessionRepository.updateStatus(c, tenant.tenantId,
                        showing.resourcePoolSessionId, "cancelled")
            }
            undersold
        }
        for (showing in cancelled) {
            LOG.infof("Cancelled showing %s (%s, started %s): fewer than %d seats sold %d minutes " +
                    "after its start time", showing.resourcePoolSessionId, showing.label,
                    showing.startsAt, viability.minimumSeats(), viability.graceMinutes())
            notifier.notifyWatchers(tenant.tenantId, showing.resourcePoolId,
                    showing.resourcePoolSessionId,
                    SeatAvailabilityNotifier.Reason.SHOWING_CANCELLED)
        }
        return cancelled.size
    }

    /** Booking drafts, pruned on the same principle as the watches above: a draft is
     * abandoned by closing a tab, which calls nothing. It holds no seat, so an expired one
     * costs a row and not a booking. */
    private fun pruneExpiredDraftsFor(tenant: Tenant): Int =
        db.inTenantTransaction(tenant.tenantId) { c -> draftRepository.deleteExpired(c, tenant.tenantId) }

    private fun completeShowingsFor(tenant: Tenant): Int =
        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.completePastShowings(c, tenant.tenantId) }

    companion object {
        private val LOG: Logger = Logger.getLogger(ReservationLifecycleService::class.java)
    }
}
