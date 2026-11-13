package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/**
 * A version of the platform-tenant agreement — distinct from
 * [PolicyDocument] and `terms_version`, which govern the end customer's terms
 * for a booking. This governs the tenant's relationship with the platform: the
 * double-booking guarantee, the tenant's duty to disclose other sales channels for the
 * same inventory, and the limit of the guarantee to inventory this system can see.
 *
 * Platform-level, not tenant-scoped — one document, identical for every tenant, so it
 * carries no `tenantId` and no RLS policy. The per-tenant fact lives in
 * [TenantAgreementAcceptance].
 */
@JvmRecord
data class PlatformAgreementVersion(
    val platformAgreementVersionId: UUID?,
    val versionLabel: String?,
    val content: String?,
    val contentHash: String?,
    val effectiveFrom: Instant?,
    val createdAt: Instant?,
)
