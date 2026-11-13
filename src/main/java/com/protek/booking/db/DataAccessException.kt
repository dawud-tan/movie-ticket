package com.protek.booking.db

import java.sql.SQLException

/**
 * Wraps a failing SQL statement plus the statement text, so a caller catching this
 * can inspect [sqlState] (e.g. `23P01` exclusion violation, `40001`
 * serialization failure) without re-parsing the message.
 */
open class DataAccessException : RuntimeException {

    private val sqlState: String?

    constructor(sql: String?, cause: SQLException) :
            super("Query failed [%s]: %s".format(sql, cause.message), cause) {
        this.sqlState = cause.getSQLState()
    }

    constructor(message: String?, cause: Throwable?) : super(message, cause) {
        this.sqlState = (cause as? SQLException)?.getSQLState()
    }

    fun sqlState(): String? = sqlState

    fun isExclusionViolation(): Boolean = "23P01" == sqlState

    /** `23505` — a unique index refused the row. Which index is not in the SQLSTATE, so a
     * caller that cares (the booking-code retry) matches the constraint name in the message;
     * a bare `isUniqueViolation()` is not enough to decide a retry is safe. */
    fun isUniqueViolation(): Boolean = "23505" == sqlState

    fun isSerializationFailure(): Boolean = "40001" == sqlState

    /**
     * `40P01` — PostgreSQL found a cycle of transactions waiting on each other and chose
     * this one as the victim.
     *
     * Categorically different from [isExclusionViolation], and the difference decides
     * whether a retry is allowed. A `23P01` is an *answer about the data* — the seat
     * is taken, and asking again returns the same answer forever, which is why it is never
     * retried. A `40P01` is not an answer at all: the transaction rolled back whole and
     * nothing was decided, so the same request run again gets a real one.
     */
    fun isDeadlock(): Boolean = "40P01" == sqlState
}
