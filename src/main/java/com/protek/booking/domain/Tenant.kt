@file:UseSerializers(UuidSerializer::class)

package com.protek.booking.domain

import com.protek.booking.json.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.util.UUID

/**
 * The control-plane row — the one table deliberately not tenant-isolated, because
 * it is what implements isolation for everything else.
 *
 * This record carries the three independent tenancy axes
 * (`computeMode`, `dataMode`, `domainMode`), which were in the DDL
 * from the start but had no Java-side reader while the application was single-tenant, plus the
 * parallel-sales-channel disclosure fields. The axes are sold as named packages
 * (Starter / Business / Enterprise) but stay orthogonal here on purpose, so repackaging
 * is a pricing-page change rather than a schema change.
 *
 * **`@Serializable` because `POST /platform/tenants` answers with one**, inside
 * `TenantProvisioningService.ProvisionResult`; the JSON is what Jackson wrote for the Java record
 * this was until 2026-10-08. A `@JvmRecord` so every Java caller's `tenant.tenantId()` and every
 * template's `tenant.displayName` are untouched. Every field but the primary key is nullable,
 * because the Java record's were and a non-null Kotlin parameter would turn a null from a Java
 * caller into an exception here; `TenantRepository` is the one constructor, and `tenant_id` is the
 * one column it reads that cannot be null.
 */
@Serializable
@JvmRecord
data class Tenant(
    val tenantId: UUID,
    val slug: String?,
    val displayName: String?,
    val vertical: String?,
    val computeMode: String?,
    val dataMode: String?,
    val domainMode: String?,
    val customDomain: String?,
    val dedicatedDbHost: String?,
    val dedicatedDbName: String?,
    val jurisdiction: String?,
    val complaintPhone: String?,
    val pseRegistrationNo: String?,
    val status: String?,
    val otherSalesChannelsDisclosed: Boolean,
    val otherSalesChannelsDetail: String?,
) {

    /** True when this tenant's data lives on its own Cloud SQL instance (the isolated
     * tier) — the signal that migrations and provisioning must reach a second database,
     * not just this one. */
    fun hasDedicatedDatabase(): Boolean = MODE_DEDICATED == dataMode

    /** True when this tenant gets its own Cloud Run service rather than sharing the pool
     * service. Real request-load and autoscaling isolation — deliberately not a
     * dedicated VM, which is a different product answering a different concern. */
    fun hasDedicatedCompute(): Boolean = MODE_DEDICATED == computeMode

    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_OFFBOARDING = "offboarding"

        const val MODE_SHARED = "shared"
        const val MODE_DEDICATED = "dedicated"
    }
}
