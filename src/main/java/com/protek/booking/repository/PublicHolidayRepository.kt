package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.PublicHoliday
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.Date
import java.sql.ResultSet
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/** The tenant's `hari libur nasional` list — the one pricing input
 * that cannot be derived, only maintained. */
@ApplicationScoped
class PublicHolidayRepository {

    @Inject
    lateinit var db: Db

    /**
     * The holiday that puts `date` in the top band: one falling on it, or one falling
     * the day *after* it — a cinema may price the eve of a holiday at the holiday rate, and
     * that clause is the whole reason this table exists.
     *
     * One query for both cases rather than two round trips, and ordered so the holiday
     * itself beats the eve when a date is both (the day before Idul Fitri's second day is
     * Idul Fitri's first). Same band either way, so this only decides which name the page
     * shows — but showing "the day before Idul Fitri" on Idul Fitri would read as a bug.
     *
     * `holiday_date IN (?, ?)` rather than a BETWEEN or any arithmetic on the
     * column: it is a plain index probe on the UNIQUE (tenant_id, holiday_date) btree, and
     * an expression over the column would not be.
     */
    fun findHolidayOrEve(c: Connection, tenantId: UUID?, date: LocalDate?): Optional<HolidayMatch> =
        db.queryOne(c,
            "SELECT " + COLUMNS + ", (holiday_date <> ?) AS is_eve FROM public_holiday " +
                    "WHERE tenant_id = ? AND holiday_date IN (?, ?) " +
                    "ORDER BY holiday_date LIMIT 1",
            { rs -> HolidayMatch(map(rs), rs.getBoolean("is_eve")) },
            Date.valueOf(date), tenantId, Date.valueOf(date), Date.valueOf(date!!.plusDays(1)))

    /** A holiday and whether `date` was its eve rather than the day itself.
     *
     * A `@JvmRecord`, as the nested Java record was, so `holiday()` and `eve()` keep their
     * record accessor names. */
    @JvmRecord
    data class HolidayMatch(val holiday: PublicHoliday?, val eve: Boolean) {
        /** How the seat map and the admin screen explain the band. */
        fun reason(): String? = if (eve) "the day before " + holiday!!.name else holiday!!.name
    }

    /**
     * The admin list: this year onward, oldest first.
     *
     * Bounded rather than "everything", because the list grows by ~20 rows a year forever
     * and a screen for maintaining *next* year's SKB should not open on 2026. Past
     * dates stay in the table — a reservation's price was fixed at hold time, but staff
     * still get asked in March why a February ticket cost what it did.
     */
    fun findFrom(c: Connection, tenantId: UUID?, from: LocalDate?): List<PublicHoliday> =
        db.query(c,
            "SELECT " + COLUMNS + " FROM public_holiday " +
                    "WHERE tenant_id = ? AND holiday_date >= ? ORDER BY holiday_date",
            ::map, tenantId, Date.valueOf(from))

    /**
     * Adds a day, or renames the one already on that date.
     *
     * An upsert rather than an insert that can fail: the UNIQUE is one row per date,
     * and an operator typing a date that is already listed means "this too" — for
     * which the answer is one row naming both, not a 23505 on an admin form. Postgres
     * settles the collision, so two staff adding the same date concurrently is fine.
     */
    fun upsert(c: Connection, tenantId: UUID?, date: LocalDate?, name: String?): PublicHoliday =
        db.queryOne(c,
            "INSERT INTO public_holiday (tenant_id, holiday_date, name) VALUES (?, ?, ?) " +
                    "ON CONFLICT (tenant_id, holiday_date) DO UPDATE SET name = EXCLUDED.name " +
                    "RETURNING " + COLUMNS,
            ::map, tenantId, Date.valueOf(date), name
        ).orElseThrow()

    /** Removes a day. A hard delete: this is a configuration list, and a soft-deleted holiday
     * would still have to be excluded from every band lookup for no benefit. Prices already
     * charged are unaffected — `reservation.price_amount` was fixed at hold time. */
    fun delete(c: Connection, tenantId: UUID?, publicHolidayId: UUID?): Int =
        db.execute(c,
            "DELETE FROM public_holiday WHERE tenant_id = ? AND public_holiday_id = ?",
            tenantId, publicHolidayId)

    private fun map(rs: ResultSet): PublicHoliday =
        PublicHoliday(
            rs.getObject("public_holiday_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getDate("holiday_date").toLocalDate(),
            rs.getString("name"),
            rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        const val COLUMNS =
            "public_holiday_id, tenant_id, holiday_date, name, created_at"
    }
}
