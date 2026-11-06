package com.protek.booking.security

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.isJavaBlank
import com.protek.booking.repository.AppUserRepository
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger

/**
 * Creates the first `platform_admin` identity from configuration at startup.
 *
 * Configuration rather than a seed migration, for two reasons. First, a real person's
 * email address in a committed migration is both a privacy leak into git history and wrong
 * for anyone else who clones the repo. Second, and more importantly: the first admin is a
 * credential-shaped fact about a deployment, and deployments differ — the demo environment,
 * a staging environment, and production should not share an administrator because a migration
 * said so.
 *
 * Unset is the default and produces no admin at all, which keeps the app's verified
 * property of booting with zero environment variables set. The consequence is explicit: with
 * no admin configured, `/platform` is reachable by nobody, which is the correct failure
 * direction.
 */
@ApplicationScoped
class PlatformAdminBootstrap {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var appUserRepository: AppUserRepository

    @ConfigProperty(name = "booking.bootstrap.platform-admin-email")
    @JvmField
    final var platformAdminEmail: String? = null

    fun onStart(@Observes event: StartupEvent) {
        val email = platformAdminEmail
        // isJavaBlank, not isBlank: Kotlin's would call a lone U+00A0 blank (JavaStrings.kt).
        if (email == null || email.isJavaBlank() || UNSET == email) {
            return
        }
        try {
            db.inTransaction { c ->
                val existing = appUserRepository.findByEmail(c, email)
                if (existing.isPresent) {
                    val user = existing.get()
                    if (AppUser.ROLE_PLATFORM_ADMIN != user.role) {
                        // Deliberately NOT auto-promoted. Silently upgrading an existing
                        // customer or staff row to platform_admin because a config value
                        // matched would make a role escalation invisible in the audit trail
                        // and reversible only by someone who noticed. A human should do this
                        // one deliberately.
                        LOG.warnf("booking.bootstrap.platform-admin-email=%s already exists with role '%s'; "
                                + "NOT promoting automatically — change the role deliberately if intended",
                            email, user.role)
                    }
                    return@inTransaction 0
                }
                appUserRepository.insert(c, email, AppUser.ROLE_PLATFORM_ADMIN, null, null)
                LOG.infof("Bootstrapped platform_admin identity %s", email)
                1
            }
        } catch (e: RuntimeException) {
            // Never fail startup over this: an unreachable database at boot is already
            // surfaced by Flyway, and a booting app that can serve public pages is better
            // than one that refuses to start because an optional admin could not be created.
            LOG.errorf(e, "Failed to bootstrap platform_admin identity %s", email)
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(PlatformAdminBootstrap::class.java)
        private const val UNSET = "__unset__"
    }
}
