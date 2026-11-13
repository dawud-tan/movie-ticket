package com.protek.booking.db

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.jboss.logging.Logger

/**
 * Says at boot which database role the pool runs as, and warns when that role can rewrite
 * `audit_log`.
 *
 * `DATABASE_USER` defaults to the owner, `booking`, on purpose (`application.properties`: a
 * deployment that has not made the switch described in the README's database section behaves as
 * before), and nothing else tells the two apart: tenant isolation holds either way (FORCE ROW LEVEL
 * SECURITY binds the owner too), the pages work, the tests pass. What the owner's credential loses
 * is the property the audit trail rests on, that the application cannot edit its own audit chain.
 * An unconfigured deployment silently skips `app_runtime`; the default stays, and this line makes
 * that visible.
 *
 * Read after Flyway, which runs before StartupEvent, so the table exists. A failure here is logged,
 * never thrown: a missing log line must not stop the cinema.
 */
@ApplicationScoped
class RuntimeRoleStartupLog {

    @Inject
    lateinit var db: Db

    fun onStart(@Observes event: StartupEvent) {
        try {
            val (role, canRewrite) = db.inTransaction { c ->
                db.queryOne(c, "SELECT current_user::text AS role, " +
                        "has_table_privilege('audit_log', 'UPDATE') OR has_table_privilege('audit_log', 'DELETE') AS rewrite",
                    { rs -> rs.getString("role") to rs.getBoolean("rewrite") }).orElseThrow()
            }
            if (canRewrite) {
                LOG.warnf("Database role %s can UPDATE or DELETE audit_log: the audit chain is not " +
                        "append-only for this credential. The README's database section " +
                        "switches the pool to app_runtime.", role)
            } else {
                LOG.infof("Database role %s: audit_log is append-only for it", role)
            }
        } catch (e: RuntimeException) {
            LOG.warnf(e, "Could not read the database role's privileges on audit_log")
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(RuntimeRoleStartupLog::class.java)
    }
}
