package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.service.SeatMapPlan
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.Collections
import java.util.Optional
import java.util.UUID

/** Backs the tenant-facing "configure a hall's seat map" use case — one row per seat. */
@ApplicationScoped
class ResourceUnitRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, resourcePoolId: UUID?,
               label: String?, groupKey: String?, sequenceNo: Int?): ResourceUnit =
        db.queryOne(c,
            "INSERT INTO resource_unit (tenant_id, resource_pool_id, label, group_key, sequence_no) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, resourcePoolId, label, groupKey, sequenceNo
        ).orElseThrow()

    /**
     * Inserts a whole generated seat map in one batch. Returns the row count.
     *
     * The caller supplies the connection, so this runs inside the caller's transaction —
     * a partially-generated auditorium is worse than a rejected one, and the generator relies
     * on the whole plan committing or none of it.
     */
    fun insertAll(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, seats: List<SeatMapPlan.Seat>): Int {
        val rows = ArrayList<Array<Any?>>(seats.size)
        for (seat in seats) {
            rows.add(arrayOf(tenantId, resourcePoolId, seat.label, seat.groupKey, seat.sequenceNo,
                seat.accessible == true))
        }
        return db.executeBatch(c,
            "INSERT INTO resource_unit (tenant_id, resource_pool_id, label, group_key, sequence_no, attributes) " +
                    "VALUES (?, ?, ?, ?, ?, " + ACCESSIBLE_ONLY + ")",
            rows)
    }

    /**
     * Every label already used in this hall, active or not.
     *
     * Deliberately does *not* filter on `is_active`, unlike [findByPool]:
     * a deactivated seat still owns its label, and generating a second "A1" beside it would put
     * two seats with one name on the seat map. There is no unique constraint on
     * `(resource_pool_id, label)` to lean on — adding one would have to contend with
     * existing tenant data, so the generator checks instead.
     */
    fun findLabelsByPool(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): Set<String> =
        HashSet(db.query(c,
            "SELECT label FROM resource_unit WHERE tenant_id = ? AND resource_pool_id = ?",
            { rs -> rs.getString("label") }, tenantId, resourcePoolId))

    fun findById(c: Connection, tenantId: UUID?, resourceUnitId: UUID?): Optional<ResourceUnit> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM resource_unit WHERE tenant_id = ? AND resource_unit_id = ?",
            ::map, tenantId, resourceUnitId)

    fun findByPool(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): List<ResourceUnit> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM resource_unit " +
                    "WHERE tenant_id = ? AND resource_pool_id = ? AND is_active " +
                    "ORDER BY group_key, sequence_no",
            ::map, tenantId, resourcePoolId)

    // --- Relayout ----------------------------------------------------------

    /**
     * Every seat in the hall, retired ones included — what a relayout has to diff against.
     *
     * Retired rows are the point rather than an oversight: a hall that lost row A last
     * season and is getting it back must reinstate the original A1, not insert a second seat
     * with that name beside it. Same reasoning as [findLabelsByPool].
     */
    fun findAllByPool(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): List<ResourceUnit> =
        db.query(c, allByPool(""), ::map, tenantId, resourcePoolId)

    /**
     * As [findAllByPool], with every row locked until the transaction ends.
     *
     * This lock is half of the interlock that stops a seat being retired out from under a
     * booking landing at the same instant; [findBookableIds] is the other half. Taken
     * over the whole hall rather than the seats being retired, because the diff that decides
     * which those are is computed from the rows this statement returns — reading first and
     * locking afterwards is the race, not the fix.
     */
    fun findAllByPoolForUpdate(c: Connection, tenantId: UUID?, resourcePoolId: UUID?): List<ResourceUnit> =
        db.query(c, allByPool(" FOR UPDATE"), ::map, tenantId, resourcePoolId)

    /**
     * Which of these seats may actually be booked in this hall right now: they exist, they
     * belong to *this* pool, and they have not been retired.
     *
     * Not an availability check — availability is the `EXCLUDE` constraint's business
     * and nothing here reads a reservation. This answers a different question, the one a
     * form post cannot be trusted on: whether the seat ids in the request are seats of this
     * hall at all. A customer whose tab was open across a relayout has retired seat ids in
     * their DOM, and without this they could hold a seat that is no longer in the room.
     *
     * **`FOR SHARE` is load-bearing.** It is what makes the check and the insert
     * that follows it one decision: a relayout wanting to retire one of these seats takes
     * `FOR UPDATE` on the same rows, so the two serialise. Whichever gets there first
     * wins cleanly — the hold blocks and then finds the seat gone, or the relayout blocks and
     * then finds the seat booked. Shared rather than exclusive, so two customers holding
     * different seats (or racing for the same one) never queue behind each other; that contest
     * is still settled by the constraint.
     */
    fun findBookableIds(c: Connection, tenantId: UUID?, resourcePoolId: UUID?, resourceUnitIds: List<UUID?>): Set<UUID> {
        if (resourceUnitIds.isEmpty()) {
            return java.util.Set.of()
        }
        // Built rather than bound as an array: Db binds plain JDBC parameters, and the lists
        // here are a booking's worth of seats, not a hall's.
        val params = ArrayList<Any?>()
        params.add(tenantId)
        params.add(resourcePoolId)
        params.addAll(resourceUnitIds)
        return HashSet(db.query(c,
            "SELECT resource_unit_id FROM resource_unit " +
                    "WHERE tenant_id = ? AND resource_pool_id = ? AND is_active " +
                    "  AND resource_unit_id IN (" + placeholders(resourceUnitIds.size) + ") " +
                    "FOR SHARE",
            { rs -> rs.getObject("resource_unit_id", UUID::class.java) }, *params.toTypedArray()))
    }

    /**
     * Moves, retires and reinstates seats in one batched statement (relayout).
     *
     * One statement for all three because they are one operation on one row: a relayout
     * either lands whole or not at all, and a seat map half-way between two layouts is the
     * state nothing else in this system knows how to read. Each row is
     * `(groupKey, sequenceNo, isActive, accessible, accessible, tenantId, resourceUnitId)`, where a
     * null `accessible` keeps the seat's mark and true/false sets it; the other
     * keys of `attributes` are left alone either way.
     */
    fun updateAll(c: Connection, rows: List<Array<Any?>>): Int =
        db.executeBatch(c,
            "UPDATE resource_unit SET group_key = ?, sequence_no = ?, is_active = ?, " +
                    "attributes = CASE WHEN CAST(? AS boolean) IS NULL THEN attributes " +
                    "WHEN CAST(? AS boolean) THEN attributes || '{\"accessible\": true}'::jsonb " +
                    "ELSE attributes - 'accessible' END " +
                    "WHERE tenant_id = ? AND resource_unit_id = ?",
            rows)

    private fun map(rs: ResultSet): ResourceUnit =
        ResourceUnit(
            rs.getObject("resource_unit_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getObject("resource_pool_id", UUID::class.java),
            rs.getString("label"),
            rs.getString("group_key"),
            // A cast, as the Java's (Integer) was: a null stays null.
            rs.getObject("sequence_no") as Int?,
            rs.getBoolean("is_active"),
            rs.getBoolean("accessible"),
        )

    private companion object {
        /** The one reading of the mark: absent or anything but JSON true is "not accessible". */
        const val ACCESSIBLE = "COALESCE(attributes -> 'accessible' = 'true'::jsonb, false) AS accessible"

        /** A new seat's `attributes`, from one boolean parameter. */
        const val ACCESSIBLE_ONLY =
            "CASE WHEN CAST(? AS boolean) THEN '{\"accessible\": true}'::jsonb ELSE '{}'::jsonb END"

        const val COLUMNS =
            "resource_unit_id, tenant_id, resource_pool_id, label, group_key, sequence_no, is_active, " +
                    ACCESSIBLE

        fun allByPool(locking: String): String =
            ("SELECT " + COLUMNS + " FROM resource_unit "
                    + "WHERE tenant_id = ? AND resource_pool_id = ? "
                    + "ORDER BY group_key, sequence_no" + locking)

        fun placeholders(count: Int): String = Collections.nCopies(count, "?").joinToString(",")
    }
}
