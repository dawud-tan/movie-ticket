package com.protek.booking.web

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.TenantRepository
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.DefaultTenant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.inject.Named

/**
 * The tenant's complaint phone and PSE registration number, as `base.html`'s footer and `/api/app`'s
 * seat map show them.
 * PP 80/2019 Pasal 27 makes a complaint service mandatory and PP 71/2019 Pasal 37(1) wants its
 * number disclosed **before** the transaction, which is why provisioning refuses a tenant without
 * one (`TenantProvisioningService`) and why it is on every page rather than only the order page.
 *
 * The catalogue's tenant, through [CurrentTenant.publicCatalogueId] and not `get()`: the footer is
 * on every page, and `get()` throws for a signed-in platform admin, which would turn every page 500.
 * Its own bean for the reason `StaffNav` gives.
 */
@ApplicationScoped
@Named("complaint")
class ComplaintContact {

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var defaultTenant: DefaultTenant

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var tenantRepository: TenantRepository

    /** As provisioned, for reading. The configured tenant is cached, so the usual page costs no query. */
    fun phone(): String? = tenant()?.complaintPhone

    /**
     * The tenant's PSE registration number (Tanda Daftar PSE), null until the platform admin records
     * it, and then shown beside the phone. Recorded after provisioning, which is why
     * `PlatformAdminResource.recordPseRegistration` drops [DefaultTenant]'s cache.
     */
    fun pseRegistrationNo(): String? = tenant()?.pseRegistrationNo

    private fun tenant(): Tenant? {
        val id = currentTenant.publicCatalogueId()
        val configured = defaultTenant.get()
        if (configured.tenantId == id) return configured
        return db.inTransaction { c -> tenantRepository.findById(c, id) }.orElse(null)
    }

    /** A given tenant's number, for a mail sent after commit, where there is no request to resolve
     * the catalogue from (`BuyerMail`). */
    fun phoneOf(tenantId: java.util.UUID): String? =
        db.inTransaction { c -> tenantRepository.findById(c, tenantId) }.orElse(null)?.complaintPhone

    /** The same number as a `tel:` target: `+62-21-5550100` dials as `+62215550100`. */
    fun tel(): String? = phone()?.filter { it == '+' || it.isDigit() }
}
