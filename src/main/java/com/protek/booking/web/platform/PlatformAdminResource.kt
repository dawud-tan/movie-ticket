@file:UseSerializers(UuidSerializer::class)

package com.protek.booking.web.platform

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.domain.TenantBillingPeriod
import com.protek.booking.json.UuidSerializer
import com.protek.booking.repository.TenantRepository
import com.protek.booking.security.Roles
import com.protek.booking.service.BillingService
import com.protek.booking.service.DefaultTenant
import com.protek.booking.service.TenantProvisioningService
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import java.util.UUID

/**
 * Platform-operator endpoints: tenant provisioning and the billing cycle.
 *
 * Separate from `/admin` on purpose, rather than a role variation on the same tree.
 * `tenant_staff` is scoped to one tenant and `platform_admin` is global, so
 * these two roles do not differ in permission level over the same resources — they operate on
 * different resources entirely. A platform admin has no home tenant, which is why
 * `CurrentTenant` refuses to invent one for them and why every endpoint here names its
 * tenant explicitly in the path.
 *
 * **On the explicit `tenantId` path parameter, given the rule that tenant context
 * must never come from caller-supplied input:** that rule exists to prevent privilege
 * escalation — a tenant-scoped caller reaching another tenant's data by editing a value. A
 * `platform_admin` is already authorized across all tenants, so there is no escalation
 * available: the parameter selects among things this caller may already reach, rather than
 * granting reach. That distinction is what makes an explicit selector correct here and
 * forbidden on every tenant-scoped endpoint.
 *
 * JSON rather than Qute here: these are operator actions driven from
 * a script and curl, not a browser UI, and building a console for a
 * one-person platform before there are tenants to manage would be premature.
 *
 * kotlinx.serialization since 2026-10-08, in Jackson's bytes (`RestJsonWriter`). Request bodies
 * keep Jackson's leniency — unknown keys ignored, a missing field null or `false` — so the
 * script and the curl lines in the runbooks go on working unchanged.
 */
@Path("/platform")
@RunOnVirtualThread
@RolesAllowed(Roles.PLATFORM_ADMIN)
@Produces(MediaType.APPLICATION_JSON)
class PlatformAdminResource {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var tenantRepository: TenantRepository

    @Inject
    lateinit var provisioningService: TenantProvisioningService

    @Inject
    lateinit var billingService: BillingService

    @Inject
    lateinit var defaultTenant: DefaultTenant

    @Serializable
    data class TenantSummary(
        val tenantId: UUID, val slug: String?, val displayName: String?, val status: String?,
        val computeMode: String?, val dataMode: String?, val domainMode: String?,
        val pseRegistrationNo: String?, val otherSalesChannelsDisclosed: Boolean,
    ) {
        companion object {
            internal fun of(t: Tenant): TenantSummary =
                TenantSummary(t.tenantId, t.slug, t.displayName, t.status,
                    t.computeMode, t.dataMode, t.domainMode, t.pseRegistrationNo,
                    t.otherSalesChannelsDisclosed)
        }
    }

    @GET
    @Path("/tenants")
    fun listTenants(): List<TenantSummary> =
        db.inTransaction(tenantRepository::findAll).map { TenantSummary.of(it) }

    @POST
    @Path("/tenants")
    @Consumes(MediaType.APPLICATION_JSON)
    fun provision(request: TenantProvisioningService.ProvisionRequest): Response {
        val result = provisioningService.provision(request)
        return Response.status(Response.Status.CREATED).entity(result).build()
    }

    /** Records whether this tenant sells the same inventory through a channel this system
     * cannot see — the fact a conflict investigation needs first, and the
     * boundary of the double-booking guarantee in the platform agreement. */
    @POST
    @Path("/tenants/{tenantId}/sales-channel-disclosure")
    @Consumes(MediaType.APPLICATION_JSON)
    fun discloseOtherSalesChannels(@PathParam("tenantId") tenantId: UUID,
                                   request: DisclosureRequest): Response {
        val updated = db.inTransaction { c ->
            tenantRepository.updateOtherSalesChannelDisclosure(c, tenantId, request.disclosed ?: false, request.detail)
        }
        if (updated == 0) {
            throw NotFoundException("Tenant not found: $tenantId")
        }
        return Response.noContent().build()
    }

    /** `disclosed` is nullable only so a missing or null value reads as `false`, which is what
     * Jackson did for the Java record's primitive `boolean`. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class DisclosureRequest(val disclosed: Boolean? = null, val detail: String? = null)

    @POST
    @Path("/tenants/{tenantId}/pse-registration")
    @Consumes(MediaType.APPLICATION_JSON)
    fun recordPseRegistration(@PathParam("tenantId") tenantId: UUID,
                              request: PseRegistrationRequest): Response {
        val updated = db.inTransaction { c ->
            tenantRepository.updatePseRegistrationNo(c, tenantId, request.registrationNo)
        }
        if (updated == 0) {
            throw NotFoundException("Tenant not found: $tenantId")
        }
        // base.html's footer reads the number through DefaultTenant's cache.
        defaultTenant.forget()
        return Response.noContent().build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class PseRegistrationRequest(val registrationNo: String? = null)

    @GET
    @Path("/tenants/{tenantId}/billing")
    fun billingHistory(@PathParam("tenantId") tenantId: UUID): List<TenantBillingPeriod> =
        billingService.historyFor(tenantId)

    @POST
    @Path("/tenants/{tenantId}/billing/{periodId}/invoiced")
    fun markInvoiced(@PathParam("tenantId") tenantId: UUID,
                     @PathParam("periodId") periodId: UUID): Response {
        if (!billingService.markInvoiced(tenantId, periodId)) {
            throw NotFoundException("No pending billing period $periodId for tenant $tenantId")
        }
        return Response.noContent().build()
    }

    @POST
    @Path("/tenants/{tenantId}/billing/{periodId}/paid")
    @Consumes(MediaType.APPLICATION_JSON)
    fun recordPayment(@PathParam("tenantId") tenantId: UUID,
                      @PathParam("periodId") periodId: UUID,
                      request: PaymentReceivedRequest): Response {
        if (!billingService.recordPayment(tenantId, periodId, request.paymentReference)) {
            throw NotFoundException(
                "No invoiced or overdue billing period $periodId for tenant $tenantId")
        }
        return Response.noContent().build()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class PaymentReceivedRequest(val paymentReference: String? = null)

    @POST
    @Path("/tenants/{tenantId}/suspend")
    fun suspend(@PathParam("tenantId") tenantId: UUID): Response {
        if (!billingService.suspendForNonPayment(tenantId)) {
            // Refusing to suspend a tenant with nothing overdue is the point, not a
            // limitation: suspension is disruptive and should require the overdue record to
            // exist, so it can be justified afterward.
            return Response.status(Response.Status.CONFLICT)
                .entity("{\"error\":\"tenant has no overdue billing periods\"}").build()
        }
        return Response.noContent().build()
    }

    @POST
    @Path("/tenants/{tenantId}/reactivate")
    fun reactivate(@PathParam("tenantId") tenantId: UUID): Response {
        billingService.reactivate(tenantId)
        return Response.noContent().build()
    }
}
