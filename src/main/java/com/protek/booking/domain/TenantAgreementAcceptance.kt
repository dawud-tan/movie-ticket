package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/** One tenant's acceptance of one [PlatformAgreementVersion] — the gate on
 * onboarding. Tenant-scoped and under RLS, unlike the agreement text itself. */
@JvmRecord
data class TenantAgreementAcceptance(
    val tenantAgreementAcceptanceId: UUID?,
    val tenantId: UUID?,
    val platformAgreementVersionId: UUID?,
    val acceptedAt: Instant?,
    val acceptedByName: String?,
    val acceptedByEmail: String?,
    val ipAddress: String?,
)
