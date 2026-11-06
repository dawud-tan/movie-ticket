package com.protek.booking.repository

import com.protek.booking.db.DataAccessException
import com.protek.booking.db.Db
import com.protek.booking.domain.BookingDraft
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** Booking drafts — the `booking_ref` the seat-selection URL carries. */
@ApplicationScoped
class BookingDraftRepository {

    @Inject
    lateinit var db: Db

    /**
     * The draft this identity already has for this showing, or a new one — as one statement.
     *
     * **`ON CONFLICT` rather than select-then-insert**, against the booking_draft unique index
     * on `(tenant_id, app_user_id, resource_pool_session_id)`. A customer double-tapping
     * a showtime on a phone sends two POSTs a few milliseconds apart, and a read-then-write
     * would let both read "no draft" and mint two refs for one intention — leaving the second
     * page load looking at a different booking from the one the first tab is on. This is not
     * the no-pre-check rule in reverse: nothing here decides whether a seat is free, and the
     * index is doing the deciding exactly as that rule asks.
     *
     * The `DO UPDATE` is what makes it return the existing row rather than nothing —
     * `DO NOTHING` suppresses the `RETURNING` clause on a conflict, which reads as
     * "the draft vanished". It also pushes `expires_at` out, so re-entering a draft you
     * left open yesterday works instead of 404ing.
     */
    fun upsert(c: Connection, tenantId: UUID?, appUserId: UUID?, sessionId: UUID?,
               expiresAt: Instant?): BookingDraft =
        db.queryOne(c,
            "INSERT INTO booking_draft (tenant_id, app_user_id, resource_pool_session_id, expires_at) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT (tenant_id, app_user_id, resource_pool_session_id) DO UPDATE " +
                    "SET expires_at = EXCLUDED.expires_at, updated_at = now() " +
                    "RETURNING " + COLUMNS,
            ::map, tenantId, appUserId, sessionId, java.sql.Timestamp.from(expiresAt)
        ).orElseThrow()

    /** Not filtered on `expires_at`: expiry is decided by the caller so it can tell a
     * customer their draft went stale, rather than returning the same "no such booking" a
     * mistyped ref gets. */
    fun findByRef(c: Connection, tenantId: UUID?, bookingRef: UUID?): Optional<BookingDraft> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM booking_draft WHERE tenant_id = ? AND booking_ref = ?",
            ::map, tenantId, bookingRef)

    /**
     * Points an existing draft at a different showing — the date and time fields — and returns
     * the ref that now names that showing for this account.
     *
     * Usually [bookingRef]. The index above means the move can collide: a customer with a draft
     * on the 19:00 who opened the 21:00 separately, then moves the first onto the second. Then
     * the answer is the 21:00's own draft, via [upsert]: a draft carries nothing but its showing,
     * so it is exactly what was asked for. Neither draft changes, so a tab still open on either
     * keeps working; merging the two refs is what would strand one. This used to raise, as a 500.
     *
     * The index decides rather than a read first, for the double-tap reason [upsert] gives, and
     * the savepoint is what lets the transaction go on after it refuses.
     */
    fun moveToShowing(c: Connection, tenantId: UUID?, bookingRef: UUID?, appUserId: UUID?,
                      sessionId: UUID?, expiresAt: Instant?): UUID? {
        val mark = c.setSavepoint()
        try {
            db.execute(c,
                "UPDATE booking_draft SET resource_pool_session_id = ?, updated_at = now(), " +
                        "expires_at = ? WHERE tenant_id = ? AND booking_ref = ?",
                sessionId, java.sql.Timestamp.from(expiresAt), tenantId, bookingRef)
            return bookingRef
        } catch (e: DataAccessException) {
            if (!e.isUniqueViolation() || e.message?.contains(ONE_PER_SHOWING_INDEX) != true) {
                throw e
            }
            c.rollback(mark)
            return upsert(c, tenantId, appUserId, sessionId, expiresAt).bookingRef
        }
    }

    /** The expiry safety net, the same shape `resource_pool_watch` uses: a draft is abandoned
     * by closing a tab, which calls nothing. Without this the table only ever grows. */
    fun deleteExpired(c: Connection, tenantId: UUID?): Int =
        db.execute(c,
            "DELETE FROM booking_draft WHERE tenant_id = ? AND expires_at < now()", tenantId)

    private fun map(rs: ResultSet): BookingDraft =
        BookingDraft(
            rs.getObject("booking_ref", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("app_user_id", UUID::class.java),
            rs.getObject("resource_pool_session_id", UUID::class.java),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "booking_ref, tenant_id, app_user_id, resource_pool_session_id, created_at, updated_at, expires_at"
        const val ONE_PER_SHOWING_INDEX = "idx_booking_draft_one_per_showing"
    }
}
