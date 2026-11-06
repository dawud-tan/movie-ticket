package com.protek.booking.db

import java.sql.ResultSet
import java.sql.SQLException

/** `@Throws` because Java lambdas implement this and call `rs.getString(...)`, which throws a
 * checked `SQLException`; without the declaration javac rejects every one of them. */
fun interface RowMapper<T> {
    @Throws(SQLException::class)
    fun map(rs: ResultSet): T
}
