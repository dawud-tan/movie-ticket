@file:UseSerializers(UuidSerializer::class, LocalDateSerializer::class, BigDecimalSerializer::class,
    InstantSerializer::class)

package com.protek.booking.domain

import com.protek.booking.json.BigDecimalSerializer
import com.protek.booking.json.InstantSerializer
import com.protek.booking.json.LocalDateSerializer
import com.protek.booking.json.UuidSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * One month of platform-to-tenant billing. Flat monthly tier, not per-ticket and
 * not metered — so `amountDue` comes from the tier, and nothing here counts
 * tickets. `planTier` is a placeholder by design: the tier names
 * exist, feature gating deliberately does not, because tier contents are what a first
 * real tenant conversation should inform rather than precede.
 *
 * `periodStart`/`periodEnd` are `date`, not `timestamptz` — a
 * billing month is a calendar concept in the tenant's own jurisdiction, and giving it an
 * instant would invite a timezone question that has no meaningful answer here.
 *
 * `@Serializable` because `GET /platform/tenants/{id}/billing` returns a list of these, in the
 * JSON Jackson wrote for the Java record this was until 2026-10-08; see [Tenant] for why it is a
 * `@JvmRecord` with every field nullable.
 */
@Serializable
@JvmRecord
data class TenantBillingPeriod(
    val tenantBillingPeriodId: UUID?,
    val tenantId: UUID?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val planTier: String?,
    val amountDue: BigDecimal?,
    val currency: String?,
    val status: String?,
    val invoicedAt: Instant?,
    val paidAt: Instant?,
    val paymentReference: String?,
)
