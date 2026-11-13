package com.protek.booking.security

import com.protek.booking.domain.Tenant
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The two conditions for pool-tier RLS to actually protect data are (1) a non-owning,
 * non-BYPASSRLS role and (2) tenant context set via `SET LOCAL` every transaction.
 * The application's own runtime datasource may stay single-role (the app_runtime switch is
 * deployment config), but the schema-level mechanism itself — `FORCE ROW LEVEL SECURITY`
 * plus the `tenant_isolation` policy — is always live and needs to actually be
 * verified, not just present in the DDL. This test creates one throwaway low-privilege
 * role scoped to itself (not the app's own pool) to prove it.
 */
@QuarkusTest
class RowLevelSecurityTest {

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    lateinit var jdbcUrl: String
    @ConfigProperty(name = "quarkus.datasource.username")
    lateinit var adminUser: String
    @ConfigProperty(name = "quarkus.datasource.password")
    lateinit var adminPassword: String

    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun forceRowLevelSecurity_blocksAllAccessWithoutContext_andScopesByTenantWithIt() {
        val tenantA = fixture.tenant()
        val tenantB = fixture.tenant()
        fixture.pool(tenantA)
        fixture.pool(tenantB)

        val roleName = "rls_test_" + Math.abs(UUID.randomUUID().hashCode())
        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
            admin.createStatement().use { st ->
                st.execute("CREATE ROLE " + roleName + " LOGIN PASSWORD 'x' NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT SELECT ON resource_pool TO " + roleName)
            }
        }

        try {
            DriverManager.getConnection(jdbcUrl, roleName, "x").use { low ->
                low.autoCommit = false
                low.createStatement().use { st ->
                    st.executeQuery("SELECT count(*) FROM resource_pool").use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1),
                            "FORCE ROW LEVEL SECURITY must hide every row when no tenant context is set")
                    }
                }
                low.rollback()

                low.autoCommit = false
                low.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
                    ps.setString(1, tenantA.tenantId.toString())
                    ps.execute()
                }
                low.createStatement().use { st ->
                    st.executeQuery("SELECT tenant_id FROM resource_pool").use { rs ->
                        var count = 0
                        while (rs.next()) {
                            assertEquals(tenantA.tenantId.toString(), rs.getString(1),
                                "only tenant A's row should be visible under tenant A's context")
                            count++
                        }
                        assertEquals(1, count)
                    }
                }
                low.rollback()
            }
        } finally {
            DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
                admin.createStatement().use { st ->
                    st.execute("REVOKE ALL ON resource_pool FROM " + roleName)
                    st.execute("DROP ROLE " + roleName)
                }
            }
        }
    }

    /** The branch table, `site`. */
    @Test
    fun site_isTenantScoped_andAnEmptyTenantContextYieldsNoRowsRatherThanAnError() {
        val tenantA = fixture.tenant()
        val tenantB = fixture.tenant()
        fixture.site(tenantA, "Branch A", "Rekaan")
        fixture.site(tenantB, "Branch B", "Kudus")
        fixture.site(tenantB, "Branch B2", "Pati")

        assertTenantIsolatedWithNullifGuard("site", tenantA, 1)
    }

    /** The `film` table. */
    @Test
    fun film_isTenantScoped_andAnEmptyTenantContextYieldsNoRowsRatherThanAnError() {
        val tenantA = fixture.tenant()
        val tenantB = fixture.tenant()
        fixture.film(tenantA, "Tenant A Film", "SU")
        fixture.film(tenantB, "Tenant B Film", "17+")

        assertTenantIsolatedWithNullifGuard("film", tenantA, 1)
    }

    /** The `public_holiday` table. */
    @Test
    fun publicHoliday_isTenantScoped_andAnEmptyTenantContextYieldsNoRowsRatherThanAnError() {
        val tenantA = fixture.tenant()
        val tenantB = fixture.tenant()
        fixture.holiday(tenantA, java.time.LocalDate.of(2099, 3, 20), "Nyepi")
        fixture.holiday(tenantB, java.time.LocalDate.of(2099, 3, 20), "Nyepi")
        fixture.holiday(tenantB, java.time.LocalDate.of(2099, 4, 25), "Idul Fitri")

        // Worth pinning per table rather than assumed, and worth pinning HERE in particular:
        // this list is a pricing input, so a leak across the policy would let one tenant's
        // closure days move another tenant's prices into the top band.
        assertTenantIsolatedWithNullifGuard("public_holiday", tenantA, 1)
    }

    /**
     * `consent_record` — a V1 table, pinned here because the age attestation gave it its first
     * writer.
     *
     * A table nothing wrote could leak nothing. Now it holds a per-customer record of what a
     * named person asserted about their own age, which is the kind of row a tenant must never
     * see another tenant's copy of. V1's policy already carries the `NULLIF` guard, so this
     * asserts what is there rather than adding it.
     */
    @Test
    fun consentRecord_isTenantScoped_andAnEmptyTenantContextYieldsNoRowsRatherThanAnError() {
        val tenantA = fixture.tenant()
        val tenantB = fixture.tenant()
        fixture.consent(tenantA, fixture.customer(tenantA), "age_attestation:17+")
        fixture.consent(tenantB, fixture.customer(tenantB), "age_attestation:21+")
        fixture.consent(tenantB, fixture.customer(tenantB), "age_attestation:13+")

        assertTenantIsolatedWithNullifGuard("consent_record", tenantA, 1)
    }

    /**
     * Asserts the whole policy contract for one table: invisible without a tenant context, scoped
     * to the tenant with one, and *empty* where the context is the empty string rather
     * than an error.
     *
     * That last case is why every policy in this tree uses the
     * `NULLIF(current_setting(...), '')` form. `set_config` has no null-valued
     * setting, so a blank tenant is stored as `''`, and a bare
     * `current_setting(...)::uuid` raises 22P02 on it — an error where "no rows" was meant,
     * which surfaces as a broken page rather than an empty one. Worth pinning per table rather
     * than assumed from the DDL reading correctly.
     *
     * **Every V1 policy carries the guard** — 15 policies, 18 `NULLIF` occurrences in that
     * file. Copying a V1 policy is fine; checking the guard is in what you copied is the
     * actual rule.
     */
    private fun assertTenantIsolatedWithNullifGuard(table: String, tenantA: Tenant, expectedRowsForTenantA: Int) {
        val roleName = "rls_" + table + "_" + Math.abs(UUID.randomUUID().hashCode())
        DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
            admin.createStatement().use { st ->
                st.execute("CREATE ROLE " + roleName + " LOGIN PASSWORD 'x' NOSUPERUSER NOBYPASSRLS")
                st.execute("GRANT SELECT ON " + table + " TO " + roleName)
            }
        }

        try {
            DriverManager.getConnection(jdbcUrl, roleName, "x").use { low ->
                assertRowCount(low, table, null, 0,
                    "no tenant context must hide every row of " + table)
                assertRowCount(low, table, tenantA.tenantId.toString(), expectedRowsForTenantA,
                    "only tenant A's rows may be visible in " + table)
                assertRowCount(low, table, "", 0,
                    "an empty tenant context must return no rows from " + table + ", not raise 22P02")
            }
        } finally {
            DriverManager.getConnection(jdbcUrl, adminUser, adminPassword).use { admin ->
                admin.createStatement().use { st ->
                    st.execute("REVOKE ALL ON " + table + " FROM " + roleName)
                    st.execute("DROP ROLE " + roleName)
                }
            }
        }
    }

    /** One transaction per check, rolled back — the tenant GUC is set `is_local`, so it
     * must not leak into the next assertion. */
    private fun assertRowCount(low: Connection, table: String, tenantContext: String?, expected: Int, message: String) {
        low.autoCommit = false
        try {
            if (tenantContext != null) {
                low.prepareStatement("SELECT set_config('app.current_tenant_id', ?, true)").use { ps ->
                    ps.setString(1, tenantContext)
                    ps.execute()
                }
            }
            low.createStatement().use { st ->
                st.executeQuery("SELECT count(*) FROM " + table).use { rs ->
                    rs.next()
                    assertEquals(expected, rs.getInt(1), message)
                }
            }
        } finally {
            low.rollback()
        }
    }
}
