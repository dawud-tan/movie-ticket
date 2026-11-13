package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.TenantRepository
import com.protek.booking.security.AppUserAugmentor
import com.protek.booking.security.Roles
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import java.util.Optional
import java.util.UUID

/**
 * Resolves which tenant the current request operates on — the single place
 * `app.current_tenant_id` originates.
 *
 * **The highest-consequence tenancy rule, implemented:** for an authenticated request the
 * answer comes from the [SecurityIdentity] attribute the augmentor set from a
 * database row, never from a header, path parameter, form field, or the `Host` header.
 * There is deliberately no method here that accepts a tenant id or a hostname — the class
 * offers no API through which caller-supplied input could become tenant context, which is a
 * stronger guarantee than remembering not to call one.
 *
 * **Why the Host header is not used, given the design also allows subdomain and
 * custom-domain routing:** the domain is how a request reaches the right *service*
 * (external HTTPS Load Balancer, Certificate Manager), not how the application decides
 * whose data to return. A raw `Host` header is caller-controlled — anyone can send
 * `Host: other-tenant.example` to the shared pool service — so treating it as
 * authoritative is precisely the trivial cross-tenant leak to design against. Making it
 * safe would require the load balancer to inject a verified header and strip any client
 * copy of it; that is infrastructure this codebase cannot assert, so it is not relied on.
 *
 * **The anonymous case** (the public browse and seat-map pages, which have no
 * identity to derive from) falls back to `booking.tenant.slug` — server-side
 * configuration, not request input. On the dedicated-compute tier that is exactly right:
 * one Cloud Run service per tenant, so the deployment's own configuration names the tenant.
 * On the shared pool tier it means anonymous browsing sees the configured tenant only;
 * serving multiple tenants' public pages from one shared service still needs the
 * verified-hostname mechanism above, and is explicitly not claimed to work here.
 */
@ApplicationScoped
class CurrentTenant @Inject constructor(
    private val db: Db,
    private val tenantRepository: TenantRepository,
    /**
     * Injected as [Instance] rather than directly, because this bean is
     * `@ApplicationScoped` and is also reached from places with no active request
     * context — the scheduled sweeps and startup provisioning. A direct injection would
     * resolve its proxy lazily and throw [ContextNotActiveException] from those call
     * sites; `Instance` lets that be handled as the ordinary "no identity" case.
     */
    private val identity: Instance<SecurityIdentity>,
    private val defaultTenant: DefaultTenant,
) {
    // Constructor injection, where every other converted bean uses `@Inject lateinit var`.
    // A lateinit property has a public setter, so
    // `setDb(Db)`, `setIdentity(…)` and two more would be public methods taking a parameter on
    // this class, the exact shape TenantResolutionSecurityTest's structural check refuses: no
    // API through which anything could hand this class its inputs. The Java's fields were
    // package-private, which Kotlin cannot say; a private constructor parameter is the nearest
    // thing that ArC injects without reflection.

    /**
     * The tenant this request may read and write. Authenticated identity first, configured
     * default second.
     *
     * @throws IllegalStateException for an authenticated `platform_admin`, which has
     *         no home tenant by design. Platform-admin operations name their tenant
     *         explicitly, and silently substituting the configured default here would make
     *         a cross-tenant admin action operate on the wrong tenant while looking correct.
     */
    fun id(): UUID = tenantIdFromIdentity().orElseGet { defaultTenant.id() }

    /**
     * The tenant whose *public catalogue* this request should be shown — the public use cases
     * (browse showtimes, view a seat map), which are public and have to render for anybody.
     *
     * Deliberately a different question from [id], and keeping them apart is the
     * whole point. `id()` answers "which tenant may this identity read and write", so it
     * throws for a `platform_admin` rather than guessing. This answers "whose catalogue is
     * this deployment publishing", which is settled by `booking.tenant.slug` — server-side
     * configuration an operator sets at deploy time — and not by who happens to be reading it.
     * Both still come from the identity or from configuration; neither can come from request
     * input, so the tenancy rule is untouched.
     *
     * Identical to `id()` for every identity that has a tenant, which is every caller
     * that could reach an admin path: customers and tenant staff see exactly what they saw
     * before. It differs only for a signed-in `platform_admin`, who until now got a 500 on
     * the public home page — a platform admin browsing the public site is a reader like anyone
     * else, not an operator acting on a tenant.
     *
     * **Reads, plus the one write that is scoped to what is being read rather than to a
     * tenant an operator administers: `PushResource.subscribe`/`unsubscribe`.**
     * Every other write goes through [id] and keeps failing closed — a platform-admin
     * write that silently landed on the configured default is exactly the cross-tenant accident
     * `id()` exists to prevent. A `resource_pool_watch` row does not carry that risk:
     * it is scoped to the catalogue a reader is currently viewing (the same catalogue this method
     * already resolves for the seat map that write accompanies), not to a tenant the platform
     * admin is administering, so silently landing on the configured default is the same "reader
     * like anyone else" behaviour this method gives every other public page. Do not widen this
     * exception to another write without the same argument.
     */
    fun publicCatalogueId(): UUID = tenantIdAttribute().orElseGet { defaultTenant.id() }

    fun get(): Tenant {
        val fromIdentity = tenantIdFromIdentity()
        if (fromIdentity.isEmpty) {
            return defaultTenant.get()
        }
        val tenantId = fromIdentity.get()
        val configured = defaultTenant.get()
        if (configured.tenantId == tenantId) {
            return configured
        }
        return db.inTransaction { c -> tenantRepository.findById(c, tenantId) }
            .orElseThrow {
                IllegalStateException(
                    "Authenticated identity references tenant " + tenantId + ", which does not exist")
            }
    }

    private fun tenantIdFromIdentity(): Optional<UUID> {
        val fromAttribute = tenantIdAttribute()
        if (fromAttribute.isPresent) {
            return fromAttribute
        }
        if (authenticatedIdentity()
                .filter { current -> current.hasRole(Roles.PLATFORM_ADMIN) }
                .isPresent) {
            throw IllegalStateException(
                "platform_admin has no implicit tenant context; this operation must name a tenant explicitly")
        }
        // Authenticated but unroleable (no app_user row, or deactivated). Treat as anonymous
        // for read purposes; every protected endpoint rejects them independently.
        return Optional.empty()
    }

    /**
     * The tenant attribute the augmentor set, if this identity has one. Never throws — the
     * platform-admin refusal lives in [tenantIdFromIdentity] instead, so that
     * [publicCatalogueId] can ask "does this reader belong to a tenant?" without
     * inheriting a rule that only makes sense for operations acting *on* a tenant.
     */
    private fun tenantIdAttribute(): Optional<UUID> {
        val attribute = authenticatedIdentity().orElse(null)
            ?.getAttribute<Any?>(AppUserAugmentor.TENANT_ID_ATTRIBUTE)
        return if (attribute is UUID) Optional.of(attribute) else Optional.empty()
    }

    /** The authenticated identity, or empty when there is none to read. */
    private fun authenticatedIdentity(): Optional<SecurityIdentity> {
        try {
            if (!identity.isResolvable) {
                return Optional.empty()
            }
            val current: SecurityIdentity? = identity.get()
            if (current == null || current.isAnonymous) {
                return Optional.empty()
            }
            return Optional.of(current)
        } catch (e: ContextNotActiveException) {
            // No request context at all: a @Scheduled sweep or startup code. Not an error —
            // those paths are tenant-agnostic and iterate tenants explicitly.
            return Optional.empty()
        }
    }
}
