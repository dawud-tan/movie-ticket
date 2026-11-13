package com.protek.booking.db

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * A throwaway `LOGIN` role holding `app_runtime`, together with the
 * [UnprivilegedDb] that connects as it.
 *
 * **Why a role rather than a set of grants.** `app_runtime` is the RLS hardening role —
 * `NOSUPERUSER NOBYPASSRLS`, full DML except `UPDATE`/`DELETE`/`TRUNCATE`
 * on `audit_log` — so a test that borrows it exercises the exact privilege shape the
 * application is meant to deploy under, including the parts nobody remembers to reproduce by
 * hand. It is granted to a per-test role rather than used directly because the migration
 * deliberately creates it `NOLOGIN` with no password (a password in a committed migration is
 * not a secret).
 *
 * Every connection this hands out — the [UnprivilegedDb] and [openConnection]
 * alike — belongs to that role, so an assertion cannot accidentally be made through a privilege
 * the code under test did not have.
 *
 * `@Throws` keeps every method declaring the `SQLException` the Java did, so the Java tests'
 * try-with-resources and `throws` clauses still compile.
 */
class RlsBoundRole private constructor(
    private val jdbcUrl: String,
    private val adminUser: String,
    private val adminPassword: String,
    private val roleName: String,
    actorContext: ActorContext,
) : AutoCloseable {

    private val db: UnprivilegedDb = UnprivilegedDb(jdbcUrl, roleName, PASSWORD, actorContext)

    companion object {
        /** Only ever reachable over loopback from the test's own Testcontainers instance, and the
         * role is dropped when the test ends. */
        private const val PASSWORD = "x"

        /**
         * @param namePrefix distinguishes concurrently-existing roles in `pg_roles` when a
         *                   failure leaves one behind, so the test that created it is identifiable.
         */
        @JvmStatic
        @Throws(SQLException::class)
        fun create(namePrefix: String, jdbcUrl: String, adminUser: String,
                   adminPassword: String, actorContext: ActorContext): RlsBoundRole {
            val roleName = namePrefix + "_" + Math.abs(UUID.randomUUID().hashCode())
            DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
                admin.createStatement().use { st ->
                    st.executeQuery("SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime'").use { rs ->
                        if (!rs.next()) {
                            throw IllegalStateException(
                                "V1's app_runtime role is missing, so this test cannot run under the privilege "
                                        + "shape it exists to check — the migration warns and continues when the "
                                        + "migrating role lacks CREATEROLE, which is not the case under Dev Services")
                        }
                    }
                    st.execute("CREATE ROLE " + roleName + " LOGIN PASSWORD '" + PASSWORD
                            + "' NOSUPERUSER NOBYPASSRLS INHERIT")
                    st.execute("GRANT app_runtime TO " + roleName)
                }
            }
            return RlsBoundRole(jdbcUrl, adminUser, adminPassword, roleName, actorContext)
        }
    }

    /** Install over the `Db` bean with `QuarkusMock.installMockForType` to make the
     * production code under test run its own queries, unchanged, as this role. */
    fun db(): UnprivilegedDb = db

    fun roleName(): String = roleName

    /** A raw connection as this role, for probes that compare it against the same query run as the
     * owner. Caller closes it; `autoCommit` is left at the driver default. */
    @Throws(SQLException::class)
    fun openConnection(): Connection = DriverManager.getConnection(jdbcUrl, roleName, PASSWORD)

    /** A raw connection as the migrating/owner role — the superuser half of a differential. */
    @Throws(SQLException::class)
    fun openOwnerConnection(): Connection = DriverManager.getConnection(jdbcUrl, adminUser, adminPassword)

    @Throws(SQLException::class)
    override fun close() {
        db.close()
        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
            admin.createStatement().use { st ->
                // DROP OWNED first: the GRANT above is a dependency that DROP ROLE refuses to break.
                st.execute("DROP OWNED BY " + roleName)
                st.execute("DROP ROLE " + roleName)
            }
        }
    }
}
