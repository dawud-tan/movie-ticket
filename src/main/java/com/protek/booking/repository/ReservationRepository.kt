package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.SeatMapEntry
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The core table: `reservation_no_overlap` — the `EXCLUDE USING gist`
 * constraint — is what makes double-booking structurally impossible, not this repository.
 * [insertHold] simply issues the insert and lets a `23P01` exclusion violation
 * propagate as a [com.protek.booking.db.DataAccessException] for the service layer
 * to interpret as "seat no longer available" — it never pre-checks availability itself,
 * since that check would itself be racy.
 */
@ApplicationScoped
class ReservationRepository {

    @Inject
    lateinit var db: Db

    fun insertHold(c: Connection, tenantId: UUID?, reservationGroupId: UUID?, resourceUnitId: UUID?,
                   resourcePoolSessionId: UUID?, policyDocumentId: UUID?, periodStart: Instant?,
                   periodEnd: Instant?, priceAmount: BigDecimal?, priceCurrency: String?,
                   holdExpiresAt: Instant?, createdByActor: String?, idempotencyKey: UUID?): Reservation =
        db.queryOne(c,
            "INSERT INTO reservation (tenant_id, reservation_group_id, resource_unit_id, resource_pool_session_id, " +
                    "policy_document_id, period, price_amount, price_currency, hold_expires_at, created_by_actor, idempotency_key) " +
                    "VALUES (?, ?, ?, ?, ?, tstzrange(?, ?), ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, reservationGroupId, resourceUnitId, resourcePoolSessionId, policyDocumentId,
            periodStart, periodEnd, priceAmount, priceCurrency, holdExpiresAt, createdByActor, idempotencyKey
        ).orElseThrow()

    fun findById(c: Connection, tenantId: UUID?, reservationId: UUID?): Optional<Reservation> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM reservation WHERE tenant_id = ? AND reservation_id = ?",
            ::map, tenantId, reservationId)

    /**
     * A group's seats in a stable order. `reservation_id` breaks the tie that is the common
     * case, not the edge: every seat of one hold is inserted in one transaction and so shares one
     * `now()`. Callers that update the rows they walk use [findByGroupForUpdate], which locks
     * them in this order and before the first audited write (the audit chain's lock-order rule).
     */
    fun findByGroup(c: Connection, tenantId: UUID?, reservationGroupId: UUID?): List<Reservation> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM reservation WHERE tenant_id = ? AND reservation_group_id = ? " +
                    "ORDER BY created_at, reservation_id",
            ::map, tenantId, reservationGroupId)

    /**
     * The same rows, locked — the first statement of `PaymentService.settleCash`.
     *
     * **Why the seats and not the group row.** Every path that changes both writes its
     * reservations before its `reservation_group` row — the QRIS webhook, the pay-at-counter
     * confirm, a hold that fails part-way — and cancellation and the expiry sweep never write the
     * group at all. A settlement that locked the group first and then confirmed seats would take
     * the two in the opposite order to a webhook landing on the same order, and one of them would
     * die as a deadlock. Locking the seats first, in [findByGroup]'s order, queues behind
     * those paths instead of crossing them.
     */
    fun findByGroupForUpdate(c: Connection, tenantId: UUID?, reservationGroupId: UUID?): List<Reservation> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM reservation WHERE tenant_id = ? AND reservation_group_id = ? " +
                    "ORDER BY created_at, reservation_id FOR UPDATE",
            ::map, tenantId, reservationGroupId)

    /**
     * How many seats this customer already holds for showings starting inside `[from, to)`
     * — the per-day purchase cap (10 tickets a day is a common limit).
     *
     * **The window is an instant range, not a `date_trunc`.** A calendar day only
     * exists in a timezone, and the caller has already resolved which one
     * (`CalendarPricing.zoneFor` — the branch's, not the hall's). Doing the truncation
     * here would either hardcode UTC, which sells a 00:30 Jakarta showing against the previous
     * day, or take a zone as a string and re-derive an answer that already has one home.
     *
     * **Counted on `lower(period)` — the showing's start**, not on
     * `created_at`. The cap asks "how many seats does this person have for Saturday",
     * which is the question anti-scalping is about; a booking-time window would refuse eleven
     * tickets bought this afternoon for eleven different weeks.
     *
     * **`completed` counts.** It is the same ticket as a `confirmed` one — the
     * only difference is that the scheduled sweep has since run over a showing that has ended.
     * Leaving it out would make the answer depend on sweep timing: the morning's four seats
     * would count against an evening booking made at 14:00 and stop counting at 22:00, with
     * nothing in the code saying so. `cancelled` and `expired` are excluded for the
     * mirror-image reason — those seats went back on sale, so they are somebody else's now.
     */
    fun countSeatsForCustomerBetween(c: Connection, tenantId: UUID?, customerId: UUID?,
                                     fromInclusive: Instant?, toExclusive: Instant?): Int =
        db.queryOne(c,
            "SELECT count(*) FROM reservation r " +
                    "JOIN reservation_group g ON g.reservation_group_id = r.reservation_group_id " +
                    "WHERE r.tenant_id = ? AND g.customer_id = ? " +
                    "  AND r.status IN ('held','confirmed','completed') " +
                    "  AND lower(r.period) >= ? AND lower(r.period) < ?",
            { rs -> rs.getInt(1) }, tenantId, customerId, fromInclusive, toExclusive)
            .orElse(0)

    /**
     * This customer's `held` seats on one showing, in [findByGroup]'s order — what a resent
     * hold is recognised by (`BookingService.holdInternal`) and what `/api/app`'s seat map marks
     * as the caller's own. Only this customer's rows: nobody else's claim on a seat is read, so
     * it says nothing about whether a seat is free, which stays the `EXCLUDE` constraint's call.
     * A subquery rather than a join because [COLUMNS] is unqualified and the two tables share names.
     */
    fun findHeldByCustomerForSession(c: Connection, tenantId: UUID?, customerId: UUID?,
                                     sessionId: UUID?): List<Reservation> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM reservation WHERE tenant_id = ? AND resource_pool_session_id = ? " +
                    "  AND status = 'held' AND reservation_group_id IN " +
                    "    (SELECT reservation_group_id FROM reservation_group WHERE tenant_id = ? AND customer_id = ?) " +
                    "ORDER BY created_at, reservation_id",
            ::map, tenantId, sessionId, tenantId, customerId)

    /** Every held or confirmed seat of one showing, for "Cancel showing"; [forUpdate] locks them. */
    fun findLiveBySession(c: Connection, tenantId: UUID?, sessionId: UUID?, forUpdate: Boolean): List<Reservation> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM reservation WHERE tenant_id = ? AND resource_pool_session_id = ? " +
                    "AND status IN ('held','confirmed') ORDER BY reservation_id" + if (forUpdate) " FOR UPDATE" else "",
            ::map, tenantId, sessionId)

    /** Live seat map — computed directly against `held`/`confirmed`
     * rows for this showing, never a static or cached image. */
    fun findSeatMap(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, sessionId: UUID?): List<SeatMapEntry> =
        db.query(c,
            "SELECT ru.resource_unit_id, ru.label, ru.group_key, ru.sequence_no, " +
                    "r.reservation_id, r.status AS reservation_status, r.hold_expires_at, " +
                    "COALESCE(ru.attributes -> 'accessible' = 'true'::jsonb, false) AS accessible " +
                    "FROM resource_unit ru " +
                    "LEFT JOIN reservation r ON r.resource_unit_id = ru.resource_unit_id " +
                    "  AND r.resource_pool_session_id = ? AND r.status IN ('held','confirmed') " +
                    "WHERE ru.tenant_id = ? AND ru.resource_pool_id = ? AND ru.is_active " +
                    "ORDER BY ru.group_key, ru.sequence_no",
            ::mapSeatMapEntry, sessionId, tenantId, resourcePoolId)

    /**
     * The seats of this hall that a live booking points at — what a relayout may not retire.
     *
     * `held` or `confirmed`, which is deliberately the `EXCLUDE`
     * constraint's own predicate and the same pair [findSeatMap] treats as taken: a seat
     * is retirable exactly when it is not currently claimed, so there is one definition of
     * "occupied" in this system rather than a second one invented here. A stale hold whose
     * `hold_expires_at` has passed but which the sweep has not reached yet still counts,
     * for the same reason it still counts on the seat map — it is still inside the predicate.
     *
     * Completed and cancelled rows do *not* count, and that is the case retirement
     * exists for: last season's tickets keep naming the seat, the row outlives the layout, and
     * the hall can still be re-seated.
     */
    fun findClaimedUnitIds(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): Set<UUID> =
        HashSet(db.query(c,
            "SELECT DISTINCT r.resource_unit_id FROM reservation r " +
                    "JOIN resource_unit ru ON ru.resource_unit_id = r.resource_unit_id " +
                    "WHERE r.tenant_id = ? AND ru.resource_pool_id = ? " +
                    "  AND r.status IN ('held','confirmed')",
            { rs -> rs.getObject("resource_unit_id", UUID::class.java) }, tenantId, resourcePoolId))

    fun confirm(c: Connection, tenantId: UUID?, reservationId: UUID?): Int =
        db.execute(c,
            "UPDATE reservation SET status = 'confirmed', confirmed_at = now(), updated_at = now() " +
                    "WHERE tenant_id = ? AND reservation_id = ? AND status = 'held'",
            tenantId, reservationId)

    fun cancel(c: Connection, tenantId: UUID?, reservationId: UUID?, reason: String?): Int =
        db.execute(c,
            "UPDATE reservation SET status = 'cancelled', cancelled_at = now(), cancellation_reason = ?, updated_at = now() " +
                    "WHERE tenant_id = ? AND reservation_id = ? AND status IN ('held','confirmed')",
            reason, tenantId, reservationId)

    /** The sweep's statement without the seat maps. Only `ReservationRepositoryTest`
     * calls it: the sweep uses [expireStaleHoldsReturningSeatMaps], and the hold path does
     * not expire inline, so a stale hold blocks its seat until the next sweep. */
    fun expireStaleHolds(c: Connection, tenantId: UUID?): Int =
        db.execute(c,
            "UPDATE reservation SET status = 'expired', updated_at = now() " +
                    "WHERE tenant_id = ? AND status = 'held' AND hold_expires_at < now()",
            tenantId)

    /** One seat map whose availability changed: the pair the watcher lookup is keyed on.
     * `sessionId` may be null, since `reservation.resource_pool_session_id` is
     * nullable for verticals with no discrete occasions (a hospitality booking, say).
     *
     * A `@JvmRecord`, as the nested Java record was, so Java callers keep `resourcePoolId()`. */
    @JvmRecord
    data class AffectedSeatMap(val resourcePoolId: UUID?, val resourcePoolSessionId: UUID?)

    /**
     * Expires stale holds and reports which seat maps changed, so the sweep can notify their
     * watchers — the one case with no live request to piggyback on.
     *
     * A single statement rather than expire-then-query, because between two statements a
     * concurrent booking could re-hold the same seat and the second query would report a seat map
     * whose availability had already changed back — or worse, another sweep could claim the same
     * rows. `RETURNING` inside a CTE reports exactly the rows this statement expired.
     *
     * The join to `resource_unit` is not incidental: `reservation` carries
     * `resource_unit_id` but no `resource_pool_id` (the schema puts `tenant_id` on
     * every table but not the pool, which is reachable through the unit), and the watcher lookup
     * is keyed on the pool. DISTINCT because a sweep typically expires several seats of the same
     * showing and that warrants one notification, not one per seat.
     */
    fun expireStaleHoldsReturningSeatMaps(c: Connection, tenantId: UUID?): List<AffectedSeatMap> =
        db.query(c,
            "WITH expired AS (" +
                    "  UPDATE reservation SET status = 'expired', updated_at = now() " +
                    // SKIP LOCKED: the first row expired takes the tenant's audit lock, so
                    // the sweep must never then wait on a row; a locked one waits for the next tick.
                    "  WHERE reservation_id IN (SELECT reservation_id FROM reservation " +
                    "    WHERE tenant_id = ? AND status = 'held' AND hold_expires_at < now() " +
                    "    FOR UPDATE SKIP LOCKED) " +
                    "  RETURNING resource_unit_id, resource_pool_session_id" +
                    ") " +
                    "SELECT DISTINCT ru.resource_pool_id, e.resource_pool_session_id " +
                    "FROM expired e JOIN resource_unit ru ON ru.resource_unit_id = e.resource_unit_id",
            { rs ->
                AffectedSeatMap(
                    rs.getObject("resource_pool_id", UUID::class.java),
                    rs.getObject("resource_pool_session_id", UUID::class.java))
            },
            tenantId)

    /**
     * Moves confirmed reservations to `completed` once their showing has finished —
     * the lifecycle edge `confirmed --> completed : showing date has passed`, which
     * previously had nothing implementing it.
     *
     * Only `confirmed` rows transition, deliberately. A `held` row whose period
     * has passed is a hold nobody ever paid for and belongs to [expireStaleHolds];
     * completing it would record an attended booking that never existed. `cancelled` and
     * `expired` are terminal and must stay as they are — a cancelled booking does not
     * become attended because time passed.
     *
     * `upper(period) < now()` uses the range's own end rather than a duplicated column
     * (the range is the source of truth for when a claim runs). An unbounded upper bound
     * yields NULL, and `NULL < now()` is NULL rather than true, so an open-ended
     * reservation is never auto-completed — the right outcome, since nothing has established
     * that it ended.
     *
     * Note on the audit trail: the `reservation_audit` trigger attributes this UPDATE
     * to `NEW.created_by_actor` — the row's original creator — so a system-driven bulk
     * completion is currently logged as though the customer performed it. That is a
     * pre-existing defect shared by [confirm], [cancel] and
     * [expireStaleHolds] (none of which reset `created_by_actor` either), not one
     * introduced here, and fixing it properly means adding an actor-type GUC alongside
     * `app.current_actor_id` and rewriting the trigger — a change across every write path
     * that deserves its own commit and its own per-path attribution tests.
     */
    fun completePastShowings(c: Connection, tenantId: UUID?): Int =
        db.execute(c,
            "UPDATE reservation SET status = 'completed', updated_at = now() " +
                    // SKIP LOCKED for the reason expireStaleHoldsReturningSeatMaps gives.
                    "WHERE reservation_id IN (SELECT reservation_id FROM reservation " +
                    "  WHERE tenant_id = ? AND status = 'confirmed' AND upper(period) < now() " +
                    "  FOR UPDATE SKIP LOCKED)",
            tenantId)

    /**
     * The door's lookup: one scanned barcode to one seat.
     *
     * Tenant-scoped like every other read here, and the redundancy with RLS is deliberate —
     * a token minted for another cinema returns empty, which the scan endpoint reports as an
     * unknown code rather than as someone else's ticket.
     */
    fun findByAdmissionToken(c: Connection, tenantId: UUID?, admissionToken: String?): Optional<Reservation> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM reservation WHERE tenant_id = ? AND admission_token = ?",
            ::map, tenantId, admissionToken)

    /**
     * Walks one seat through the door, once — `admitted_at IS NULL` in the WHERE clause
     * **is** the anti-passback mechanism, in the same sense that the `EXCLUDE`
     * constraint is the anti-double-booking one. Never put a read-then-decide in front of it:
     * two ushers scanning the same screenshotted ticket at two doors would both read NULL and
     * both let someone in.
     *
     * Under READ COMMITTED the loser of that race blocks on the winner's row lock, re-evaluates
     * this predicate against the committed new version, matches nothing, and returns empty — which
     * is exactly the "already admitted" answer the second usher needs. That is why this returns the
     * row it changed rather than a boolean: an empty result is not an error, it is the signal to go
     * and find out *why* nothing moved.
     *
     * `status = 'confirmed'` rather than a looser check, because that is the only status
     * meaning a seat is sold and the showing has not ended. A `held` row is unpaid, and
     * `cancelled`/`expired`/`completed` are all terminal. Note what this does
     * *not* do: it does not move `status`. See `reservation.admitted_at` in V1 for why an 'admitted' status would
     * release the seat the holder is sitting in.
     */
    fun admit(c: Connection, tenantId: UUID?, admissionToken: String?, admittedByAppUserId: UUID?): Optional<Reservation> =
        db.queryOne(c,
            "UPDATE reservation SET admitted_at = now(), admitted_by_app_user_id = ?, updated_at = now() " +
                    "WHERE tenant_id = ? AND admission_token = ? AND status = 'confirmed' " +
                    "AND admitted_at IS NULL RETURNING " + COLUMNS,
            ::map, admittedByAppUserId, tenantId, admissionToken)

    private fun map(rs: ResultSet): Reservation {
        val confirmedAt = rs.getTimestamp("confirmed_at")
        val cancelledAt = rs.getTimestamp("cancelled_at")
        val holdExpiresAt = rs.getTimestamp("hold_expires_at")
        val admittedAt = rs.getTimestamp("admitted_at")
        return Reservation(
            rs.getObject("reservation_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("reservation_group_id", UUID::class.java),
            rs.getObject("resource_unit_id", UUID::class.java),
            rs.getObject("resource_pool_session_id", UUID::class.java),
            rs.getObject("policy_document_id", UUID::class.java),
            rs.getTimestamp("period_start").toInstant(),
            rs.getTimestamp("period_end").toInstant(),
            rs.getString("status"),
            rs.getBigDecimal("price_amount"),
            rs.getString("price_currency"),
            holdExpiresAt?.toInstant(),
            rs.getString("created_by_actor"),
            confirmedAt?.toInstant(),
            cancelledAt?.toInstant(),
            rs.getString("cancellation_reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getString("admission_token"),
            admittedAt?.toInstant(),
            rs.getObject("admitted_by_app_user_id", UUID::class.java),
        )
    }

    private fun mapSeatMapEntry(rs: ResultSet): SeatMapEntry {
        val reservationStatus = rs.getString("reservation_status")
        val holdExpiresAt = rs.getTimestamp("hold_expires_at")
        // Lowercase, matching CSS classes and Qute template comparisons directly — avoids
        // calling String.toLowerCase() from inside a template (native-image: keep template
        // expressions to build-time-resolvable record/extension accessors, not ad hoc JDK
        // method calls that would need their own reflection registration).
        val seatStatus = reservationStatus ?: "available"
        return SeatMapEntry(
            rs.getObject("resource_unit_id", UUID::class.java),
            rs.getString("label"),
            rs.getString("group_key"),
            // A cast, as the Java's (Integer) was: a null stays null.
            rs.getObject("sequence_no") as Int?,
            seatStatus,
            rs.getObject("reservation_id", UUID::class.java),
            holdExpiresAt?.toInstant(),
            rs.getBoolean("accessible"),
        )
    }

    private companion object {
        const val COLUMNS =
            "reservation_id, tenant_id, reservation_group_id, resource_unit_id, resource_pool_session_id, " +
                    "policy_document_id, lower(period) AS period_start, upper(period) AS period_end, status, " +
                    "price_amount, price_currency, hold_expires_at, created_by_actor, confirmed_at, " +
                    "cancelled_at, cancellation_reason, created_at, updated_at, " +
                    "admission_token, admitted_at, admitted_by_app_user_id"
    }
}
