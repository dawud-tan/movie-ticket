package com.protek.booking.service

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.domain.Customer
import com.protek.booking.domain.Payment
import com.protek.booking.domain.PolicyDocument
import com.protek.booking.domain.RateRule
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Tenant
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.i18n.Msg
import com.protek.booking.i18n.StatusLabels
import com.protek.booking.isJavaBlank
import com.protek.booking.payment.PaymentService
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.ConsentRecordRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.FilmRepository
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.PolicyDocumentRepository
import com.protek.booking.repository.RateRuleRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourceUnitRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.web.dto.ReservationGroupDetail
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.NotFoundException
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Hold → confirm/cancel, and the correctness mechanisms behind them:
 * the `EXCLUDE` constraint is what actually prevents double-booking (never
 * pre-checked here, since a pre-check would itself be racy — see [hold]), and
 * the session-status re-validation uses `SELECT ... FOR UPDATE` rather than a
 * plain read, so a concurrent cancellation of the same showing can't race a booking
 * that's already in flight.
 *
 * **Audited against Adya's anomaly taxonomy, not against an isolation level.** The question
 * is which anomalies these reads and writes can produce at all, since an isolation level is the
 * wrong tool for anything that is not one of them. Everything here runs at `READ COMMITTED`.
 * (G-single is a cycle with *exactly one* anti-dependency edge; G2 covers *one or more*, so
 * classic two-doctor write skew is G2, not G-single.)
 *
 * | Anomaly | Hold | Confirm | Cancel |
 * |---|---|---|---|
 * | **G0** write cycles | Never: PostgreSQL row write locks serialise any two writers of a row | same | same |
 * | **G1a/G1b** aborted or intermediate reads | Never: MVCC shows only committed versions, and `READ UNCOMMITTED` is an alias for `READ COMMITTED` | same | same |
 * | **G1c** circular information flow | Never at `READ COMMITTED` or above | same | same |
 * | **G2-item** over known rows | Two customers claiming one seat. Closed by the `EXCLUDE` constraint as a write-time conflict, like a unique index, with no SSI needed | Rows already claimed; `findByGroupForUpdate` locks them first | Same row lock as confirm |
 * | **G-single / G2** over a predicate | The per-day cap is a `COUNT` over rows that do not exist yet, so nothing to lock there. Closed by locking the customer row the count is scoped to (`CustomerRepository.findByIdForUpdate`) | No predicate read | No predicate read |
 * | **Not a cycle**: a missing invariant | Book while a cancellation of the showing commits: one anti-dependency edge, a valid serial history, a wrong outcome. Isolation cannot fix it; the session row's `FOR UPDATE` does | — | — |
 *
 * The last row turns into genuine G2 if showing cancellation ever checks "does this session have
 * active reservations" first: that read is the return edge. Classification follows the exact
 * read/write footprint of both transactions, not how the scenario looks from outside.
 */
@ApplicationScoped
class BookingService {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var paymentRepo: PaymentRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var consentRepo: ConsentRecordRepository
    @Inject
    lateinit var filmRepo: FilmRepository
    @Inject
    lateinit var policyRepo: PolicyDocumentRepository
    @Inject
    lateinit var rateRuleRepo: RateRuleRepository
    @Inject
    lateinit var poolRepo: ResourcePoolRepository
    @Inject
    lateinit var unitRepo: ResourceUnitRepository
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var paymentService: PaymentService

    @Inject
    lateinit var refundService: com.protek.booking.payment.RefundService

    @Inject
    lateinit var buyerMail: BuyerMail
    @Inject
    lateinit var orderPush: OrderPush
    @Inject
    lateinit var calendarPricing: CalendarPricing
    @Inject
    lateinit var currentActor: CurrentActor
    @Inject
    lateinit var appUserRepo: AppUserRepository
    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @ConfigProperty(name = "booking.hold-duration-minutes", defaultValue = "10")
    @JvmField
    final var holdDurationMinutes: Int = 10

    @Inject
    lateinit var purchaseCap: PurchaseCap

    @Inject
    lateinit var viability: ShowtimeViability

    /** The hold refusals render in the seat map's .error-banner, on a page that is otherwise
     * entirely in the reader's language. A bundle can reach them because this is a bean —
     * ShowtimeResource injects the same thing for the same reason. Outside a request this
     * resolves to English, which is what LanguagePreference.current() is built to do. */
    @Inject
    lateinit var language: LanguagePreference

    /** The status word inside holdShowingNotOpen, so an Indonesian page does not say
     * "(status: closed)". Same split as every badge in customer/: the column value is a CSS
     * hook and a schema value, the word a reader sees is copy. */
    @Inject
    lateinit var statusLabels: StatusLabels

    /**
     * Test-only race-window seam: a `Thread.sleep`
     * inserted between the session-status read and the reservation insert, gated behind a
     * config property that is 0 (a no-op) everywhere except a test explicitly setting it.
     * This is what widens the window enough for a concurrent cancellation to land inside it
     * reliably in `SessionCancellationRaceTest`, instead of hoping two HTTP calls
     * happen to arrive close enough together.
     *
     * It sits *after* the seat-validation read below rather than before it, so a test
     * holding this window open is holding that read's `FOR SHARE` lock too — which is what
     * lets `SeatRetirementRaceTest` drive a relayout into a booking that is genuinely
     * mid-flight rather than one that has already finished. Either position satisfies the
     * requirement, which is a sleep between the session-status read and the insert.
     */
    @ConfigProperty(name = "booking.test.race-delay-ms", defaultValue = "0")
    @JvmField
    final var testRaceDelayMs: Int = 0

    /**
     * Runs a hold's transaction, re-running it whole if PostgreSQL picked it as a deadlock victim.
     *
     * **Where the deadlock comes from.** Every attempt on one seat inserts a
     * `reservation` referencing the same `resource_unit` row, so each holds a shared
     * foreign-key lock on it while also waiting on the others' speculative insert into the gist
     * `EXCLUDE`. With enough of them at once the wait graph closes and the database breaks
     * it. Observed, not theorised: `SeatBookingConcurrencyTest` raced eight identical holds
     * and reported `[40P01 x7]`.
     *
     * **Why a retry is correct here when a 23P01 is never retried.** The two states
     * say different things. A `23P01` is the constraint's permanent answer — the seat is
     * taken, and the identical request fails identically until an external event changes reality.
     * A `40P01` decided nothing: this transaction rolled back in full and the request has
     * not been answered yet. Retrying it is how it gets an answer, and that answer is then either
     * the hold or a clean `23P01` the customer can read.
     *
     * **Why the whole transaction, and not a savepoint.** A deadlock aborts the transaction
     * itself, so `ROLLBACK TO SAVEPOINT` — the trick the booking-code retry uses to recover
     * one statement — cannot reach it. The work is re-run from the top.
     *
     * **What makes re-running safe, and the condition that would end it.** Everything
     * inside this transaction is database work that rolled back with it: the session lock, the
     * seat validation, the customer row, the group and the reservations. There is no external
     * call in there — the push is fire-and-forget *after* commit and the payment is a
     * later request entirely. Put an outbound call inside this transaction and this wrapper stops
     * being safe, because the retry would make it twice.
     */
    private fun <T> holdTransaction(tenantId: UUID, work: Db.TxWork<T>): T {
        var attempt = 1
        while (true) {
            try {
                return db.inTenantTransaction(tenantId, work)
            } catch (e: DataAccessException) {
                if (attempt >= DEADLOCK_ATTEMPTS || !e.isDeadlock()) {
                    throw e
                }
                LOG.debugf("Deadlock (40P01) on hold attempt %d of %d; re-running the transaction",
                        attempt, DEADLOCK_ATTEMPTS)
            }
            attempt++
        }
    }

    @JvmRecord
    data class HoldResult(val success: Boolean, val group: ReservationGroup?, val reservations: List<Reservation>,
                          val failedResourceUnitId: UUID?, val failureReason: String?) {
        companion object {
            internal fun success(group: ReservationGroup, reservations: List<Reservation>): HoldResult =
                HoldResult(true, group, reservations, null, null)

            internal fun failure(failedResourceUnitId: UUID?, reason: String?): HoldResult =
                HoldResult(false, null, java.util.List.of(), failedResourceUnitId, reason)
        }
    }

    /**
     * Holds one seat, or a small block. All-or-nothing: if any unit in
     * the requested block is already taken, every unit this attempt already grabbed is
     * released and the group is marked `failed` with
     * `failed_resource_unit_id` set to the seat that blocked it — committed as one
     * coherent record (not rolled away), so composite bookings get a richer response:
     * the client can offer a different specific block.
     *
     * Sent twice, it answers the second time with the first one's group rather than a refusal
     * from the customer's own seats — see `replaysOwnHold` on [holdInternal].
     */
    fun hold(resourcePoolId: UUID?, sessionId: UUID?, resourceUnitIds: List<UUID?>?,
             customerName: String?, customerPhone: String?,
             ageAttestation: AgeClassification.Attestation?): HoldResult =
        holdInternal(resourcePoolId, sessionId, resourceUnitIds, purchaseCap.limits(),
                ageAttestation, true
        ) { c, tenantId -> resolveBookingCustomer(c, tenantId, customerName, customerPhone) }

    /**
     * The counter sale: staff select seats on
     * behalf of a walk-in who has no identity and wants no account.
     *
     * Differs from [hold] in exactly three ways — the customer is the per-tenant
     * walk-in placeholder rather than a named person, `created_by_actor` records
     * `tenant_staff` rather than `customer` (which `audit_log.actor_type`
     * mirrors, so a counter sale is distinguishable in the record), and the purchase cap does
     * not apply. Everything else, including the `EXCLUDE` constraint and the
     * `FOR UPDATE` session re-validation, is the same code path: a counter sale is not a
     * second booking engine.
     *
     * **Why [PurchaseCap.Limits.NONE] here — both halves of it.** The caps are an
     * anti-scalping control on the self-service channel; the counter is the
     * *dominant* channel at Indonesian cinemas, and a party of twelve standing at the desk
     * is an ordinary sale, not scalping. Capping staff would break the majority channel to police
     * the minority one. The per-day cap is exempt here for a second and stronger reason: every
     * walk-in in the building shares the one placeholder `customer` row, so a per-customer
     * daily count would refuse the eleventh walk-in ticket of the day on the grounds that ten
     * other people had already bought one. Staff selling on the customer seat map *are*
     * capped, because that endpoint is the self-service one — the counter screen is where a large
     * group is served.
     *
     * **The age attestation is skipped here for a third reason of the same
     * shape.** Every walk-in in the building shares the one placeholder `customer` row,
     * so ticking a box on its behalf would write "attested" onto a row that is not a person and
     * then carry that claim into every future counter sale. The control the counter has is
     * better than the one being skipped: the customer is standing in front of staff, which is
     * where the age attestation says enforcement belongs anyway. The rating is shown on the counter's own seat
     * picker so staff can ask for ID.
     */
    fun holdForCounterSale(resourcePoolId: UUID?, sessionId: UUID?, resourceUnitIds: List<UUID?>?): HoldResult =
        holdInternal(resourcePoolId, sessionId, resourceUnitIds, PurchaseCap.Limits.NONE,
                AgeClassification.Attestation.NOT_ASKED, false
        ) { c, tenantId ->
            customerRepo.findWalkInPlaceholder(c, tenantId)
                    .orElseThrow {
                        IllegalStateException(
                                "Tenant " + tenantId + " has no walk-in placeholder customer — " +
                                        "provisioning did not complete. Counter sales cannot proceed.")
                    }
        }

    /** How a hold decides which `customer` row it belongs to. Along with the purchase
     * cap, this is what separates the two callers above; everything else they share. */
    private fun interface CustomerResolver {
        fun resolve(c: Connection, tenantId: UUID): Customer
    }

    /**
     * @param limits the calling channel's purchase policy, or [PurchaseCap.Limits.NONE].
     *               Passed in rather than read from [purchaseCap] here on purpose: every
     *               caller then has to state its channel's policy out loud, so a fourth booking
     *               entry point cannot inherit "no cap" by saying nothing.
     * @param ageAttestation what the caller's channel did about the age tick box.
     *               Passed in for exactly the reason above, and it matters more here: this one is
     *               a regulatory control, so a new entry point that forgot to mention it must not
     *               silently be granted the counter's exemption.
     * @param replaysOwnHold whether a request for exactly the seats this customer already holds
     *               on this showing answers with that hold. False for the counter: its customer is
     *               the one walk-in row every walk-in shares, so two sales of one seat there are two
     *               people, not one request sent twice.
     */
    private fun holdInternal(resourcePoolId: UUID?, sessionId: UUID?, resourceUnitIds: List<UUID?>?,
                             limits: PurchaseCap.Limits,
                             ageAttestation: AgeClassification.Attestation?,
                             replaysOwnHold: Boolean,
                             customerResolver: CustomerResolver): HoldResult {
        // Resolved once at the top, not per refusal: the language is a property of the request,
        // and a lookup inside the transaction lambda would make what the lambda captures depend
        // on which branch fired.
        val msg = language.messages()
        if (resourceUnitIds == null || resourceUnitIds.isEmpty()) {
            return HoldResult.failure(null, msg.holdSelectAtLeastOne())
        }
        // The per-booking cap. Input validation, in the same place as the empty check and for
        // the same reason: it reads no availability whatsoever, so it is emphatically NOT the
        // pre-check forbidden in front of the EXCLUDE constraint. The constraint remains the
        // sole arbiter of whether a seat is free; this only bounds how many were asked for.
        //
        // Above the transaction, not inside it, because nothing here needs the database — a
        // refused request never opens a connection or takes the session's FOR UPDATE lock.
        //
        // Counted per hold, not aggregated per customer per showing, and that is a correctness
        // point rather than a shortcut: every counter sale for a tenant attaches to the ONE
        // walk-in placeholder customer row, so a per-customer aggregate would count the whole
        // evening's walk-ins against a single row and refuse the ninth ticket of the night.
        //
        // The OTHER cap — 10 seats per customer per day — is genuinely per-customer, and it is
        // a separate check inside the transaction rather than a wider WHERE on this one, because
        // this one touches no database at all. See refuseIfDayIsFull.
        if (limits.capsHold() && resourceUnitIds.size > limits.perHold) {
            return HoldResult.failure(null, msg.holdPerHoldCap(limits.perHold,
                    resourceUnitIds.size, resourceUnitIds.size - limits.perHold))
        }
        val tenantId = currentTenant.id()
        val createdByActor = currentActor.createdByActor()
        val result = holdTransaction(tenantId) { c ->
            val session = sessionRepo.findByIdForUpdate(c, tenantId, sessionId)
                    .orElseThrow { NotFoundException("Showing not found: " + sessionId) }

            if ("on_sale" != session.status) {
                return@holdTransaction HoldResult.failure(null,
                        msg.holdShowingNotOpen(statusLabels.of(session.status)))
            }

            // A tenant suspended for non-payment stops new sales, on every channel, since all of them
            // hold through here. Read after the session lock, so that lock stays the first
            // statement, and from the row rather than DefaultTenant's cache, which would keep a
            // suspension invisible until a restart. Not an availability pre-check: it reads no seat.
            // What it leaves alone on purpose: holds already taken can still be paid, and paid
            // tickets still admit, because those buyers have done nothing wrong.
            if (tenantRepo.findById(c, tenantId).map { it.status }.orElse(null) == Tenant.STATUS_SUSPENDED) {
                return@holdTransaction HoldResult.failure(null, msg.holdTenantSuspended())
            }

            // Are these seats of this hall, and are they still in the room? Input validation,
            // emphatically not the forbidden availability pre-check: it reads no reservation
            // and cannot tell whether a seat is free — the EXCLUDE constraint below is still the
            // only thing that decides that. What it refuses is a request naming a seat that this
            // hall does not have, which a form post is not to be trusted on.
            //
            // The case that made this necessary is a relayout: a customer whose tab was open
            // across one is holding retired seat ids in their DOM, and the seat map they are
            // looking at no longer exists. FOR SHARE inside findBookableIds is what serialises
            // this against the relayout itself, so "still in the room" cannot go stale between
            // here and the insert.
            val bookable = unitRepo.findBookableIds(c, tenantId, resourcePoolId, resourceUnitIds)
            for (unitId in resourceUnitIds) {
                if (!bookable.contains(unitId)) {
                    return@holdTransaction HoldResult.failure(unitId, msg.holdSeatNotInHall())
                }
            }

            if (testRaceDelayMs > 0) {
                sleepForTest(testRaceDelayMs)
            }

            val customer = customerResolver.resolve(c, tenantId)

            // A resent hold. A phone's network stack can send this request again after the first
            // copy committed and its answer was lost, and the web's
            // double submit does the same; the EXCLUDE constraint would then refuse the second copy
            // with the customer's own seats. So the customer's own unexpired hold on exactly these
            // seats is the answer. Not the forbidden pre-check: it reads only this customer's rows,
            // after the FOR UPDATE above that every hold on this showing queues behind, so it can
            // say "you already hold these" and never "this seat is free"; when it finds nothing,
            // every seat still goes to the insert and the constraint decides. Before the attestation
            // and the caps, so a resend writes no second consent row and is not refused by the day
            // cap counting its own first copy. The push after it repeats one watchers already had.
            if (replaysOwnHold) {
                val wanted = resourceUnitIds.toSet()
                val now = Instant.now()
                val same = reservationRepo.findHeldByCustomerForSession(c, tenantId, customer.customerId, sessionId)
                        .filter { it.holdExpiresAt?.isAfter(now) == true }
                        .groupBy { it.reservationGroupId }.values
                        .firstOrNull { seats -> seats.map { it.resourceUnitId }.toSet() == wanted }
                if (same != null) {
                    LOG.debugf("Hold on %s repeats live group %s; answering with it", sessionId, same[0].reservationGroupId)
                    return@holdTransaction HoldResult.success(
                            groupRepo.findById(c, tenantId, same[0].reservationGroupId).orElseThrow(), same)
                }
            }

            // Read once here rather than again inside resolvePrice: the per-day window and
            // the calendar pricing band are both decided in the branch's timezone, and this row is
            // where resolving that zone starts.
            val hall = poolRepo.findById(c, tenantId, resourcePoolId)
                    .orElseThrow { NotFoundException("Hall not found: " + resourcePoolId) }

            val notOldEnough = attestAgeOrRefuse(msg, c, tenantId, session, customer, ageAttestation)
            if (notOldEnough.isPresent) {
                return@holdTransaction notOldEnough.get()
            }

            if (limits.capsDay()) {
                val dayIsFull = refuseIfDayIsFull(msg, c, tenantId, hall,
                        session.startsAt!!, customer, resourceUnitIds.size, limits.perDay)
                if (dayIsFull.isPresent) {
                    return@holdTransaction dayIsFull.get()
                }
            }

            val notEnoughSeats =
                    refuseIfBelowFirstBuyerMinimum(msg, c, tenantId, sessionId, resourceUnitIds.size)
            if (notEnoughSeats.isPresent) {
                return@holdTransaction notEnoughSeats.get()
            }

            val policy: PolicyDocument? = policyRepo.findLatest(c, tenantId, "cancellation_policy").orElse(null)
            // Priced from the SHOWING's start, not from now. A Monday matinee
            // bought on Saturday afternoon is a Monday ticket.
            val priceEach = resolvePrice(c, tenantId, hall, session.startsAt!!)
            val holdExpiresAt = Instant.now().plus(Duration.ofMinutes(holdDurationMinutes.toLong()))

            val group = groupRepo.insert(c, tenantId, resourcePoolId, customer.customerId, resourceUnitIds.size)

            val created = ArrayList<Reservation>()
            for (unitId in resourceUnitIds) {
                // A failed statement aborts the whole Postgres transaction, not just
                // itself — every later statement on this connection would be rejected
                // with "current transaction is aborted" until either the whole
                // transaction rolls back or a savepoint taken before the failing
                // statement does. This is exactly the SAVEPOINT/ROLLBACK TO pattern:
                // it's what lets the cleanup below (cancelling partial holds, marking
                // the group failed) still run in the same transaction after a 23P01.
                val savepoint = c.setSavepoint()
                try {
                    created.add(reservationRepo.insertHold(c, tenantId, group.reservationGroupId, unitId, sessionId,
                            policy?.policyDocumentId, session.startsAt, session.endsAt,
                            priceEach, "IDR", holdExpiresAt, createdByActor, UUID.randomUUID()))
                } catch (e: DataAccessException) {
                    if (!e.isExclusionViolation()) {
                        throw e
                    }
                    // A 23P01 here is never retried — the identical request fails
                    // identically until an external event changes reality.
                    c.rollback(savepoint)
                    for (partial in created) {
                        reservationRepo.cancel(c, tenantId, partial.reservationId,
                                "block booking failed: seat " + unitId + " became unavailable")
                    }
                    groupRepo.markFailed(c, tenantId, group.reservationGroupId, unitId)
                    return@holdTransaction HoldResult.failure(unitId, msg.holdSeatJustTaken())
                }
            }
            HoldResult.success(group, created)
        }
        notifySeatMap(result, sessionId, SeatAvailabilityNotifier.Reason.SEATS_HELD)
        return result
    }

    /**
     * Notify-on-commit, applied to the three booking transitions.
     *
     * Called with the transaction already returned — that is the whole point of "on commit":
     * a watcher told "seats just taken" will immediately refetch the seat map, and if the push
     * went out before the commit that refetch could still read the old availability and show the
     * seat as free. Failures are swallowed inside the notifier, so a push problem can never fail
     * a booking that has already succeeded.
     */
    private fun notifySeatMap(result: HoldResult, sessionId: UUID?,
                              reason: SeatAvailabilityNotifier.Reason) {
        if (!result.success || result.group == null) {
            return
        }
        notifier.notifyWatchers(result.group.tenantId, result.group.resourcePoolId,
                sessionId, reason)
    }

    /**
     * Resolves which `customer` row a self-service booking belongs to.
     *
     * For an authenticated customer this is their own linked row, found or created and
     * then linked to the login — *not* a phone-number lookup. That distinction is the
     * point: keying on a caller-supplied phone number means anyone who knows a phone number
     * can book (and later be shown) reservations under that person's customer row. The
     * submitted name and phone become contact details on the row rather than the identity of
     * it.
     *
     * Staff booking for a named phone customer (a real everyday case alongside the
     * counter sale) keeps the phone-lookup path, because there is no login to key on and the
     * authenticated party is the staff member, whose identity is already recorded in
     * `created_by_actor` and `audit_log.actor_id`.
     */
    private fun resolveBookingCustomer(c: Connection, tenantId: UUID,
                                       customerName: String?, customerPhone: String?): Customer {
        val linked = currentActor.customerId()
        if (linked.isPresent) {
            val existing = customerRepo.findById(c, tenantId, linked.get())
            if (existing.isPresent) {
                return existing.get()
            }
            // Linked to a row that no longer exists in this tenant — a data defect, not
            // something to silently recover from by inventing a new identity.
            throw IllegalStateException("Authenticated customer is linked to customer " +
                    linked.get() + ", which does not exist in tenant " + tenantId)
        }
        if (currentActor.isCustomer()) {
            val email: String? = currentActor.email().orElse(null)
            val customer = resolveOrCreateByEmail(c, tenantId, email, customerName, customerPhone)
            // Bind the customer row to the login so the next booking takes the branch above
            // and the ownership checks have something to compare against.
            //
            // On the same connection as everything else in this hold, deliberately: opening a
            // second transaction here would take a second pooled connection while still
            // holding this one (connection exhaustion is the dominant failure mode on
            // Cloud Run plus Cloud SQL), and would let the link commit while the hold
            // rolls back. app_user has no RLS policy, so the tenant context already set on
            // this connection neither helps nor hinders the write.
            currentActor.appUserId().ifPresent { appUserId ->
                appUserRepo.linkCustomer(c, appUserId, customer.customerId)
            }
            return customer
        }
        return customerRepo.findByPhone(c, tenantId, customerPhone)
                .orElseGet { customerRepo.insert(c, tenantId, customerName, customerPhone, null, false) }
    }

    private fun resolveOrCreateByEmail(c: Connection, tenantId: UUID, email: String?,
                                       customerName: String?, customerPhone: String?): Customer {
        if (email != null) {
            val byEmail = customerRepo.findByEmail(c, tenantId, email)
            if (byEmail.isPresent) {
                return byEmail.get()
            }
        }
        val name = if (customerName == null || customerName.isJavaBlank())
            (email ?: "Customer")
        else
            customerName
        return customerRepo.insert(c, tenantId, name, customerPhone, email, false)
    }

    /**
     * Customer-facing "confirm the hold into a reservation" — mocked
     * payment confirmation, used where QRIS settlement does not apply;
     * every reservation in the group moves `held → confirmed` atomically, or none does.
     */
    fun confirmGroup(reservationGroupId: UUID?): Boolean {
        val tenantId = currentTenant.id()
        // Captured inside the transaction and read afterwards, because the notification needs the
        // group's pool and the reservations' session — neither of which the boolean return carries.
        val confirmedGroup = AtomicReference<ReservationGroup>()
        val confirmedSession = AtomicReference<UUID>()

        val confirmed = db.inTenantTransaction(tenantId) { c ->
            val group = groupRepo.findById(c, tenantId, reservationGroupId)
                    .orElseThrow { NotFoundException("Reservation group not found: " + reservationGroupId) }
            assertMayActOn(group)
            // Locked before the first confirm takes the tenant's audit lock, as in the webhook.
            val reservations = reservationRepo.findByGroupForUpdate(c, tenantId, reservationGroupId)
            val allHeld = !reservations.isEmpty() && reservations.stream().allMatch { r -> "held" == r.status }
            if (!allHeld) {
                return@inTenantTransaction false
            }
            for (r in reservations) {
                reservationRepo.confirm(c, tenantId, r.reservationId)
            }
            groupRepo.markConfirmed(c, tenantId, reservationGroupId)
            confirmedGroup.set(group)
            confirmedSession.set(reservations[0].resourcePoolSessionId)
            true
        }

        if (confirmed && confirmedGroup.get() != null) {
            notifier.notifyWatchers(tenantId, confirmedGroup.get().resourcePoolId,
                    confirmedSession.get(), SeatAvailabilityNotifier.Reason.SEATS_CONFIRMED)
        }
        return confirmed
    }

    /**
     * The per-customer authorization check that `@RolesAllowed` structurally cannot
     * make, and that RLS does not cover.
     *
     * RLS isolates tenants: every customer of one cinema can see every other customer's
     * rows as far as the database is concerned, because they all share a `tenant_id`.
     * So a `customer`-role caller who guesses or is shown another customer's
     * `reservation_group_id` would otherwise be able to view, confirm, or cancel it —
     * a real gap, not a theoretical one, since the group id travels in the URL after a hold.
     *
     * Staff and platform admins pass: releasing and cancelling other people's
     * reservations is a tenant-facing use case, and phone cancellations are the
     * everyday reason it exists.
     */
    private fun assertMayActOn(group: ReservationGroup) {
        if (currentActor.isStaff() || currentActor.isPlatformAdmin()) {
            return
        }
        val actingCustomer = currentActor.customerId()
        if (actingCustomer.isPresent && actingCustomer.get() == group.customerId) {
            return
        }
        // Deliberately ForbiddenException (403), not NotFoundException (404): the caller is
        // authenticated and the resource exists, so 403 is the honest status. Hiding
        // existence behind a 404 would only matter if group ids were guessable, and they are
        // random UUIDs.
        throw ForbiddenException(
                "This reservation belongs to a different customer.")
    }

    /** Cancel a held or confirmed reservation (customer-facing; and
     * "manually release or cancel a reservation," tenant-facing — phone cancellations and
     * no-shows). `actor` distinguishes who acted, for the audit trail. */
    fun cancelReservation(reservationId: UUID?, actor: String?, reason: String?): Boolean {
        val tenantId = currentTenant.id()
        val poolId = AtomicReference<UUID>()
        val sessionId = AtomicReference<UUID>()
        val owing = LinkedHashSet<UUID>()

        val cancelled = db.inTenantTransaction(tenantId) { c ->
            val reservation = reservationRepo.findById(c, tenantId, reservationId)
                    .orElseThrow { NotFoundException("Reservation not found: " + reservationId) }
            val group = groupRepo.findById(c, tenantId, reservation.reservationGroupId)
                    .orElseThrow {
                        NotFoundException(
                                "Reservation group not found: " + reservation.reservationGroupId)
                    }
            assertMayActOn(group)
            assertMayCancelSettled(c, tenantId, reservation)
            val did = reservationRepo.cancel(c, tenantId, reservationId, reason) > 0
            if (did) {
                poolId.set(group.resourcePoolId)
                sessionId.set(reservation.resourcePoolSessionId)
                recordRefundObligation(c, tenantId, reservation, reason, owing)
            }
            did
        }
        // Only staff reach a paid seat here (assertMayCancelSettled); its debt goes back automatically.
        refundService.refundAfterCommit(tenantId, owing)

        // A cancellation is the change bystanders most want to hear about: a seat they were
        // watching just became available again (the "seat available" notification).
        if (cancelled) {
            notifier.notifyWatchers(tenantId, poolId.get(), sessionId.get(),
                    SeatAvailabilityNotifier.Reason.SEATS_RELEASED)
        }
        return cancelled
    }

    /**
     * Staff's "Cancel showing": the showing, every held and
     * confirmed seat on it, and the refund each paid seat is owed, in one commit.
     *
     * The showing's `FOR UPDATE` comes first, as in every hold, so no hold can slip in between:
     * `holdInternal` re-reads the status under that lock and refuses anything not `on_sale`.
     * The payments are locked next and the seats after them, the order a settlement takes them in
     * (payment, then `confirmGroupSeats`), and all of it before the first audited write (V1's
     * advisory-lock rule), so a webhook settling one of these orders cannot deadlock against it.
     * No availability is read to decide anything: every live seat is cancelled.
     *
     * After commit, fire-and-forget: the seat map's watchers hear `SHOWING_CANCELLED_BY_CINEMA`, and each
     * affected order's sockets refetch the order page, which now shows the refund owed.
     */
    fun cancelShowing(sessionId: UUID?): Int {
        val tenantId = currentTenant.id()
        val groups = LinkedHashSet<UUID>()
        val withdrawn = mutableListOf<Payment>()
        val owing = LinkedHashSet<UUID>()
        // Per order, what its paid payment took as a debt here, for the buyer's mail; null when unpaid.
        val owedBack = LinkedHashMap<UUID, BigDecimal?>()
        var poolId: UUID? = null
        val cancelled = db.inTenantTransaction(tenantId) { c ->
            val showing = sessionRepo.findByIdForUpdate(c, tenantId, sessionId)
                .orElseThrow { NotFoundException("Showing not found: $sessionId") }
            poolId = showing.resourcePoolId
            reservationRepo.findLiveBySession(c, tenantId, sessionId, false)
                .mapNotNullTo(groups) { it.reservationGroupId }
            paymentRepo.lockForGroups(c, tenantId, groups.sorted())
            val live = reservationRepo.findLiveBySession(c, tenantId, sessionId, true)
            sessionRepo.updateStatus(c, tenantId, sessionId, "cancelled")
            var count = 0
            for (reservation in live) {
                if (reservationRepo.cancel(c, tenantId, reservation.reservationId, SHOWING_CANCELLED) > 0) {
                    val group = reservation.reservationGroupId!!
                    owedBack.putIfAbsent(group, null)
                    if (recordRefundObligation(c, tenantId, reservation, SHOWING_CANCELLED, owing)) {
                        owedBack[group] = (owedBack[group] ?: BigDecimal.ZERO) + (reservation.priceAmount ?: BigDecimal.ZERO)
                    }
                    count++
                }
            }
            // A QR still open for these seats would take money for nothing: withdraw it under the
            // payments' lock above, and cancel it at the gateway after commit, as settleCash does.
            for (group in groups) {
                for (p in paymentRepo.findByReservationGroup(c, tenantId, group)) {
                    if (Payment.STATUS_PENDING == p.status && Payment.CHANNEL_QRIS == p.paymentChannel &&
                        paymentRepo.markWithdrawn(c, tenantId, p.paymentId) > 0) {
                        withdrawn.add(p)
                    }
                }
            }
            count
        }
        LOG.infof("Showing %s cancelled by staff: %d seat(s) cancelled across %d order(s)",
            sessionId, cancelled, groups.size)
        notifier.notifyWatchers(tenantId, poolId, sessionId, SeatAvailabilityNotifier.Reason.SHOWING_CANCELLED_BY_CINEMA)
        for (group in groups) notifier.notifyOrder(tenantId, group)
        paymentService.cancelAtGateway(withdrawn, "its showing was cancelled")
        buyerMail.showingCancelled(tenantId, owedBack)
        orderPush.showingCancelled(tenantId, owedBack.keys)
        refundService.refundAfterCommit(tenantId, owing)
        return cancelled
    }

    /**
     * A booking that has actually been paid for may be cancelled by staff, not by the customer.
     *
     * **This is a business decision, recorded 2026-08-17, and it is provisional.** It is
     * the cheaper of two honest resolutions and matches Indonesian cinema norms — the
     * usual stated policy is that customers cannot cancel at all. A cinema's own staff
     * may run their counter differently; until that is confirmed, refusing is the
     * safer default, because a refused cancellation is a phone call and an over-permissive one
     * is money out of the door with nobody having decided to send it.
     *
     * Deliberately narrow in two ways. It applies only once a payment has actually
     * *settled*: cancelling an unpaid hold stays a customer action,
     * which is the common case and the one that matters for seat turnover, and a pending charge
     * expires by itself. And it refuses rather than escalating — nothing here decides on a
     * customer's behalf that staff would approve.
     *
     * When staff do cancel, the refund obligation is recorded by the same transaction, so
     * this rule does not create a path where money moves without a trace; it decides who is
     * allowed to start that path.
     */
    private fun assertMayCancelSettled(c: Connection, tenantId: UUID, reservation: Reservation) {
        if (currentActor.isStaff() || currentActor.isPlatformAdmin()) {
            return
        }
        for (payment in paymentRepo.findByReservationGroup(
                c, tenantId, reservation.reservationGroupId)) {
            if (Payment.STATUS_PAID == payment.status) {
                throw ForbiddenException(
                        language.messages().cancelPaidByStaffOnly())
            }
        }
    }

    /**
     * Records that a cancelled seat's money is owed back, when that seat was actually paid for.
     *
     * Runs **inside** the cancelling transaction, and that is the whole point: if the
     * cancellation could commit without the obligation, the money would be stranded exactly as
     * it was before the refund obligation — only now with a schema that implies otherwise. One commit, both facts,
     * or neither.
     *
     * Deliberately does not touch `payment.status`. Nothing here refunds anything: the debt is
     * the record, and [owing] collects the payments that took one, for the caller to hand to
     * `RefundService` after commit. See `payment.refund_due_amount` in V1 for why `'refunded'` would be a false settlement.
     *
     * Only `paid` payments are charged an obligation —
     * [PaymentRepository.recordRefundDue] enforces that in SQL, so a group whose charge is still
     * pending (it will expire by itself) or that was never paid at all falls through as a
     * harmless no-op. A group may hold several payment rows over its life (an expired attempt,
     * then a successful one); every settled one is offered the debt, which in practice is the
     * single paid row.
     */
    private fun recordRefundObligation(c: Connection, tenantId: UUID, reservation: Reservation,
                                       reason: String?, owing: MutableSet<UUID>): Boolean {
        var owed = false
        for (payment in paymentRepo.findByReservationGroup(
                c, tenantId, reservation.reservationGroupId)) {
            if (Payment.STATUS_PAID != payment.status) {
                continue
            }
            val updated = paymentRepo.recordRefundDue(
                    c, tenantId, payment.paymentId, reservation.priceAmount, reason)
            if (updated > 0) {
                owed = true
                owing.add(payment.paymentId!!)
                LOG.infof("Refund of %s %s owed on payment %s after cancelling reservation %s",
                        reservation.priceAmount, payment.currency,
                        payment.paymentId, reservation.reservationId)
            }
        }
        return owed
    }

    /**
     * A returning customer's own bookings, newest first — the list behind
     * `GET /reservations`.
     *
     * **Scoped by the acting identity, never by a parameter.** There is no customer id in
     * the signature and there must not be one: the same reasoning `CurrentTenant` records
     * for tenancy applies a level down, since RLS isolates tenants and not customers within a
     * tenant. A method that accepted whose history to show would be one URL away from being an
     * enumeration of every booking the cinema holds.
     *
     * **Empty is a real answer, not a failure.** A caller with no linked `customer`
     * row — staff, a platform admin, or a customer who has not booked yet — has no history, and
     * the page says so in their terms rather than being refused. Staff looking for someone
     * else's booking have their own screen: `/admin/bookings`.
     */
    fun listBookingHistory(): List<ReservationGroupRepository.HistoryEntry> {
        val customer = currentActor.customerId()
        if (customer.isEmpty) {
            return java.util.List.of()
        }
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            groupRepo.findHistoryForCustomer(c, tenantId, customer.get(), HISTORY_LIMIT)
        }
    }

    /** The caller's own `held` seats on one showing, for `/api/app`'s seat map; empty, with no
     * transaction, for anyone with no customer row yet — the same short cut as [listBookingHistory]. */
    fun heldByCaller(sessionId: UUID?): List<Reservation> {
        val customer = currentActor.customerId()
        if (customer.isEmpty) {
            return java.util.List.of()
        }
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            reservationRepo.findHeldByCustomerForSession(c, tenantId, customer.get(), sessionId)
        }
    }

    /**
     * The orders page's two lists.
     *
     * **Split here rather than by two queries.** The rows are already in hand, ordered by
     * the database, and the boundary between "active" and "history" is a comparison against
     * `now()` — running it twice would be two snapshots of a clock that moves between
     * them, and an order that is in both lists or in neither is a page nobody can explain.
     * `HISTORY_LIMIT` then means what it says: the fifty most recent, across both
     * sections, rather than fifty of each.
     */
    fun listOrders(): Orders {
        val now = Instant.now()
        val all = listBookingHistory()
        return Orders(
                all.stream().filter { entry -> entry.isActive(now) }.toList(),
                all.stream().filter { entry -> !entry.isActive(now) }.toList())
    }

    /**
     * A customer's orders, in the two sections the page renders.
     *
     * @param active  bookings for a showing that has not happened yet, most recently paid first
     *                — the tickets somebody is about to use.
     * @param past    everything else: attended showings, cancellations, and the failed groups
     *                that never got a seat. Same ordering, which reads as "what I did, latest
     *                first".
     */
    @JvmRecord
    data class Orders(val active: List<ReservationGroupRepository.HistoryEntry>,
                      val past: List<ReservationGroupRepository.HistoryEntry>) {

        /** Whether the page has anything at all to show. Asked once here rather than as two
         * conditions in the template, because the empty state is a different page and not an
         * empty section — see `orders.html`. */
        fun isEmpty(): Boolean = active.isEmpty() && past.isEmpty()
    }

    /**
     * Finds a booking from the short code a customer read down the phone.
     *
     * Normalised here rather than in SQL, for the reason
     * `AdmissionService.normalise` gives about admission tokens: the lookup stays an exact
     * match that uses `idx_reservation_group_booking_code`, while a human typing
     * `k7m2-qprt` still finds their row. Everything that is not a letter or digit goes —
     * hyphens and spaces are how people group eight characters to read them aloud, and none of
     * them are in the stored value.
     *
     * Runs the same ownership check as every other read here. Staff pass it, which is who
     * this is for; it matters for what it refuses, since a code is a value a customer could in
     * principle try their neighbour's on.
     */
    fun findGroupByBookingCode(rawCode: String?): Optional<ReservationGroup> {
        // Regex(…) is the same java.util.regex pattern as Java's replaceAll, and "" carries no
        // $ or \ for it to read as a group reference. uppercase(Locale.ROOT) is
        // toUpperCase(Locale.ROOT), the same call.
        val code = rawCode?.replace(Regex("[^A-Za-z0-9]"), "")
                ?.uppercase(Locale.ROOT) ?: ""
        if (code.isEmpty()) {
            return Optional.empty()
        }
        val tenantId = currentTenant.id()
        val found = db.inTenantTransaction(tenantId) { c ->
            groupRepo.findByBookingCode(c, tenantId, code)
        }
        found.ifPresent { assertMayActOn(it) }
        return found
    }

    /** Composite read for the reservation-detail page — spans `reservation_group`,
     * `resource_pool`, `resource_pool_session` and each seat's
     * `resource_unit`, so it lives here rather than in any one repository. */
    fun getGroupDetail(reservationGroupId: UUID?): ReservationGroupDetail {
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            val group = groupRepo.findById(c, tenantId, reservationGroupId)
                    .orElseThrow { NotFoundException("Reservation group not found: " + reservationGroupId) }
            assertMayActOn(group)
            val hall = poolRepo.findById(c, tenantId, group.resourcePoolId)
                    .orElseThrow { NotFoundException("Hall not found: " + group.resourcePoolId) }
            val reservations = reservationRepo.findByGroup(c, tenantId, reservationGroupId)
            val showing: ResourcePoolSession? = if (reservations.isEmpty()) null
                    else sessionRepo.findById(c, tenantId, reservations[0].resourcePoolSessionId).orElse(null)
            val lines = ArrayList<ReservationGroupDetail.ReservationLine>()
            for (r in reservations) {
                val seat = unitRepo.findById(c, tenantId, r.resourceUnitId).orElse(null)
                lines.add(ReservationGroupDetail.ReservationLine(r, seat))
            }
            ReservationGroupDetail(group, hall, showing, lines,
                    calendarPricing.inclusionsAt(c, tenantId, group.resourcePoolId!!, group.createdAt!!),
                    calendarPricing.zoneFor(c, tenantId, hall))
        }
    }

    /**
     * What one seat of this showing costs, under whichever rate rule is in force now.
     *
     * **Two different times, deliberately.** `findActive` asks which rule applies
     * — a question about *now*, because a tenant who raised prices this morning charges
     * this morning's prices for a booking taken this afternoon. `showingStartsAt` then
     * picks the band *within* that rule, because a Monday matinee is a Monday ticket
     * whenever it was bought. Collapsing the two — pricing the band from `now()` — is
     * the mistake this signature exists to make hard, and it charges the weekend band for
     * every advance booking made at the weekend.
     *
     * The result is written to `reservation.price_amount` and never recomputed. That
     * predates calendar pricing (the refund obligation rests on it) and matters more now there is more to
     * recompute wrongly: a holiday added to the list in March must not restate what a
     * February booking cost.
     */
    private fun resolvePrice(c: Connection, tenantId: UUID, hall: ResourcePool,
                             showingStartsAt: Instant): BigDecimal? {
        val rule: RateRule = rateRuleRepo.findActive(c, tenantId, hall.resourcePoolId)
                .orElseThrow {
                    IllegalStateException(
                            "No active rate_rule configured for resource_pool " + hall.resourcePoolId)
                }
        return calendarPricing.priceFor(c, tenantId, rule, hall, showingStartsAt).amount
    }

    /**
     * The age attestation's age gate: refuse a restricted film that was not attested to, and record the
     * attestation when it was.
     *
     * **Input validation, before the insert loop — not the forbidden pre-check.** It
     * reads the film's rating and never touches availability, so the `EXCLUDE` constraint
     * remains the sole arbiter of whether a seat is free. It sits above [refuseIfDayIsFull]
     * so a refusal has not taken the customer lock, and refuses before any `reservation`
     * row exists, so no seat is taken off sale to enforce a rule about who may buy it.
     *
     * **Nothing is asked of a showing with no film or a film with no rating**, which is the
     * normal state of most fixtures and of every non-cinema vertical this
     * schema stays open to. That is why `film.classification` stays nullable: it records a rating
     * where one is known, and the absence of one is not a control failure.
     *
     * **The write happens inside the hold's own transaction**, so a booking of a restricted
     * film cannot commit without the evidence that permitted it. Two rows, doing different jobs:
     * `customer.date_of_birth_attested` is the durable summary V1 has been promising since
     * the beginning, and the `consent_record` is the per-attestation evidence — the
     * classification asserted, the moment, and a hash of the exact sentence displayed. One
     * attestation per hold, including repeats: the row is an event, and a customer who attests
     * twice has attested twice.
     *
     * **The flag is never read here.** It cannot be: one boolean over three thresholds
     * would let a 13+ attestation clear a 21+ film. `AgeClassification` carries that
     * argument in full and `AgeAttestationTest` guards it.
     */
    private fun attestAgeOrRefuse(msg: Msg, c: Connection, tenantId: UUID,
                                  session: ResourcePoolSession, customer: Customer,
                                  attestation: AgeClassification.Attestation?): Optional<HoldResult> {
        if (attestation == AgeClassification.Attestation.NOT_ASKED || session.filmId == null) {
            return Optional.empty()
        }
        // The gate is made for THIS request's language, and the sentence it produces is the one
        // hashed a few lines below. That is the whole of what the language parameter buys:
        // consent_record.policy_text_hash records what the customer actually read, rather than
        // an English sentence they were never shown.
        val gate: AgeClassification.Gate? = filmRepo.findById(c, tenantId, session.filmId)
                .map { film -> AgeClassification.gateFor(film.classification, language.current()) }
                .orElse(null)
        if (gate == null || !gate.restricted()) {
            return Optional.empty()
        }
        if (attestation != AgeClassification.Attestation.TICKED) {
            // Names the rating, because the customer is looking at a seat map and the refusal has
            // to explain itself: "this film is 17+" is the fact, and the checkbox is the remedy. The
            // rating is LSF's vocabulary and is interpolated as it stands — SU and 17+ are the
            // same characters in both languages.
            //
            // The sentence NEXT to this one, on the tick box itself, is translated too as of
            // 2026-09-17 — it was held open longer precisely because it IS hashed. See
            // AgeClassification's javadoc for the decision and what it cost. This refusal is not
            // hashed and was free to move on its own.
            return Optional.of(HoldResult.failure(null, msg.holdAgeGate(gate.classification)))
        }
        customerRepo.markDateOfBirthAttested(c, tenantId, customer.customerId)
        // gate.statement(), never a sentence rebuilt here: the gate is the one place that knows
        // which language it made, and gate.consentPurpose() carries that tag so the hash beside
        // it stays resolvable back to a sentence.
        consentRepo.insert(c, tenantId, customer.customerId, gate.consentPurpose(),
                AgeClassification.LEGAL_BASIS, AgeClassification.hashOf(gate.statement))
        return Optional.empty()
    }

    /**
     * The per-day purchase cap: 10 seats a day by default, counted per customer across
     * every showing on that day.
     *
     * **"Day" is a local date in the branch's timezone**, resolved by the same
     * [CalendarPricing.zoneFor] calendar pricing prices its bands with — not a UTC day, and not the
     * hall's own column, which is `NOT NULL DEFAULT 'Asia/Jakarta'` and so cannot tell
     * "the operator chose this" from "nobody touched it". Every Indonesian zone is *ahead*
     * of UTC, so what a UTC window gets wrong is the first hours of a local day: 00:30 on Monday
     * in Jakarta is 17:30 on Sunday in UTC, and its seats would be counted against Sunday. Same
     * trap as calendar pricing's, in the same direction.
     *
     * **Counted on the showing's start, not on when the booking was made.** The policy is
     * about how many seats one person can hold for one day's screenings, which is what
     * anti-scalping is about; a booking-time window would refuse eleven tickets bought this
     * afternoon for eleven different weeks.
     *
     * **Refuses before anything is inserted, so no seat is taken off sale** — the property
     * the per-showtime cap gets for free by sitting above the transaction, kept here where the
     * check genuinely needs the database. One row can be written on a refusal: a first-time
     * customer's own `customer` row, created by the resolver above. That is the identity
     * the count is scoped to rather than inventory, and their next successful hold would create
     * it anyway.
     *
     * **Three queries per self-service hold** — the lock, the branch lookup inside
     * `zoneFor`, and the count — including for a flat-priced hall, whose pricing path calendar pricing
     * deliberately keeps free of both extra lookups. That is not a regression of that decision:
     * the measurements are about the seat-map *poll*, which every open tab runs every
     * few seconds, whereas a hold happens once at the end of a booking. A tenant who does not
     * want the cost sets `booking.max-seats-per-day=0` and none of it runs.
     */
    private fun refuseIfDayIsFull(msg: Msg, c: Connection, tenantId: UUID,
                                  hall: ResourcePool, showingStartsAt: Instant,
                                  customer: Customer, requested: Int, perDay: Int): Optional<HoldResult> {
        // Lock first, count second. Without the lock two of this customer's holds each read 9 of
        // 10 under READ COMMITTED and both commit; CustomerRepository.findByIdForUpdate explains
        // why the customer row is the thing to lock when the rows that would breach the cap do
        // not exist yet. This is NOT the forbidden pre-check: it reads no availability, and
        // the EXCLUDE constraint remains the sole arbiter of whether a seat is free.
        customerRepo.findByIdForUpdate(c, tenantId, customer.customerId)

        val zone: ZoneId = calendarPricing.zoneFor(c, tenantId, hall)
        val day: LocalDate = showingStartsAt.atZone(zone).toLocalDate()
        val already = reservationRepo.countSeatsForCustomerBetween(c, tenantId, customer.customerId,
                day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
        if (already + requested <= perDay) {
            return Optional.empty()
        }
        // Name the day, not just the limit: the seats already counted are usually for a DIFFERENT
        // showing, so a customer looking at an empty grid would otherwise have no idea what the
        // refusal is talking about.
        val remaining = Math.max(0, perDay - already)
        // The day name follows the reader, unlike the one on a printed ticket. DAY_FMT stays
        // pinned to English because TemplateExtensions.fmtDate does — see its javadoc for why
        // the print path must not move — so this sentence formats its own day in the request's
        // locale rather than reusing it. The narrow case where a date is read rather than
        // filed.
        val held = msg.seatCount(already)
        val dayName = DAY_FMT.withLocale(language.locale()).format(day)
        return Optional.of(HoldResult.failure(null, if (remaining == 0)
            msg.holdDayCapFull(held, dayName, perDay)
        else
            msg.holdDayCapPartial(held, dayName, perDay, remaining, requested - remaining)))
    }

    /**
     * The showtime viability rule: the first booking on a showing has to take at least two seats.
     *
     * **This is not the forbidden pre-check, and the distinction is worth stating because
     * it looks like one.** That rule forbids reading availability to decide whether a
     * *specific seat* is free — the `EXCLUDE` constraint is the sole arbiter of
     * that, and it still is: every seat in this request is still inserted and can still lose to
     * a 23P01 a line below. What this counts is how many seats the showing has sold, which is a
     * commercial policy about the size of an order. The same shape as
     * [refuseIfDayIsFull], and for the same reason it lives inside the transaction
     * rather than above it.
     *
     * **The count cannot race, and not by luck.** `holdInternal` has already taken
     * `FOR UPDATE` on the session row (the re-validation lock), so two customers
     * holding on the same showing are serialised before either one reaches this line. The
     * second sees the first's rows. The sweep that cancels an under-sold showing takes the same
     * lock on the same row, so it cannot land between this count and the insert either.
     *
     * **Applies to the counter too**, unlike the purchase caps, which the counter is exempt from.
     * The caps are an anti-scalping control on the self-service channel; this is the branch's
     * own operating rule, and a member of staff selling one seat at the desk to a screening
     * that then gets cancelled has created the exact problem it exists to prevent. Nothing is
     * passed in per channel for that reason — there is no channel this is not true of.
     */
    private fun refuseIfBelowFirstBuyerMinimum(msg: Msg, c: Connection,
                                               tenantId: UUID, sessionId: UUID?,
                                               requested: Int): Optional<HoldResult> {
        if (!viability.enforced()) {
            return Optional.empty()
        }
        val alreadyLive = sessionRepo.countLiveSeats(c, tenantId, sessionId)
        if (viability.permitsHold(alreadyLive, requested)) {
            return Optional.empty()
        }
        return Optional.of(HoldResult.failure(null, viability.refusal(msg, requested)))
    }


    private fun sleepForTest(millis: Int) {
        try {
            Thread.sleep(millis.toLong())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(BookingService::class.java)

        /** `cancellation_reason` and `refund_reason` for staff's "Cancel showing". */
        internal const val SHOWING_CANCELLED = "Showing cancelled by the cinema"

        /**
         * How many bookings [listBookingHistory] shows. A bound rather than pagination,
         * because the page has nothing useful to offer beyond it: a customer looking for a booking
         * older than their last fifty is looking for one they can no longer use, and staff finding an
         * old booking do it by code, which is keyed rather than scrolled.
         */
        private const val HISTORY_LIMIT = 50

        /**
         * How the per-day refusal names the day it is talking about ("Sat 23 Aug").
         *
         * Not `TemplateExtensions.fmtDate`, which formats the same shape: that is a
         * package-private Qute extension in the web layer, and a service reaching into it to build a
         * sentence would invert the dependency. The year is dropped because a showing a customer is
         * ticking seats for is days away, not years.
         *
         * The [Locale] is pinned for the reason that class records: the default is the
         * server's `LANG`, so the same build would refuse in English on one server and in
         * Indonesian on a developer machine, with nothing in the code saying so.
         */
        private val DAY_FMT: DateTimeFormatter =
                DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

        /**
         * Bounded for the reason `ReservationGroupRepository`'s booking-code retry is bounded:
         * an unbounded loop turns a pathological case into a hung request instead of an error
         * somebody reads. After the winner commits, a retry meets a committed row and gets
         * `23P01` immediately, with nothing left to wait on.
         *
         * **Three, and the ceiling is latency rather than stubbornness.** PostgreSQL does not
         * detect a deadlock until it has waited `deadlock_timeout` — **1 second** by default,
         * confirmed on the Dev Services container — so every round of this loop costs a waiting
         * request about a second before it is killed and re-run. Three attempts is therefore already
         * a ~3-second worst case on a request a human is sitting in front of, and the bound cannot be
         * raised without spending that person's time.
         *
         * **Raised to 8 on 2026-09-15 and reverted the same day; do not try it again without new
         * evidence.** The idea was that a bound at the number of contenders would let the pile-up
         * converge (chosen on the flake's sixth sighting). Measured over five full-suite runs it did
         * not fix the flake — it changed the failure into a worse one. Four runs were clean with zero
         * deadlocks; the fifth spent so long re-running that
         * `SeatBookingConcurrencyTest`'s 30-second budget expired first, so instead of a fast
         * assertion naming `[40P01 x7]` and the retry count, the run reported a bare
         * `Timeout` that captured nothing at all. More attempts against a 1-second detection
         * cost buys time, not convergence.
         *
         * Public so `SeatBookingConcurrencyTest` races against this exact number rather than
         * a hand-copied duplicate of it. The two loops are the same loop at different levels, and
         * until now they agreed only because a comment asked the next person to keep them in step.
         */
        const val DEADLOCK_ATTEMPTS = 3
    }
}
