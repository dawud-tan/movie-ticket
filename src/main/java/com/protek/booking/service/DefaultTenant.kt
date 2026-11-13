package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.UUID

/**
 * The tenant named by this deployment's own configuration (`booking.tenant.slug`),
 * resolved once by slug and cached.
 *
 * Extracted as its own bean because two callers need it and they must not depend on each
 * other: [CurrentTenant] uses it as the fallback for requests with no authenticated
 * identity, and `AppUserAugmentor` uses it as the tenant a newly-seen customer
 * identity is enrolled into. Having the augmentor reach into `CurrentTenant` would be
 * a cycle — the augmentor is what supplies the identity attribute `CurrentTenant`
 * reads — and having each resolve the slug independently would mean two caches of the same
 * row that could disagree after a slug change.
 *
 * This is server-side configuration, which is exactly what makes it a legitimate source
 * of tenant context under the rule against caller-supplied tenancy: an operator sets it
 * at deploy time and a request cannot influence it.
 */
@ApplicationScoped
class DefaultTenant {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var tenantRepository: TenantRepository

    @ConfigProperty(name = "booking.tenant.slug")
    lateinit var slug: String

    @Volatile
    private var cached: Tenant? = null

    fun get(): Tenant {
        var result = cached
        if (result == null) {
            synchronized(this) {
                result = cached
                if (result == null) {
                    result = db.inTransaction { c -> tenantRepository.findBySlug(c, slug) }
                        .orElseThrow {
                            IllegalStateException(
                                "Configured tenant slug '" + slug + "' not found — has the seed migration run?")
                        }
                    cached = result
                }
            }
        }
        return result!!
    }

    fun id(): UUID = get().tenantId

    /** After a write to the configured tenant's row that a page shows (the PSE number), so the next
     * read sees it rather than the row as it stood at the first request after boot. */
    fun forget() {
        cached = null
    }
}
