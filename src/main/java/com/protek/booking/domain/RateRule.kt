package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/** `rule_definition` stays an opaque JSON string here — pricing is deliberately
 * jsonb, not rigid columns, and the flat-rate path only ever reads the shape
 * `{"type":"flat","amount":<number>}` out of it (see BookingService). */
@JvmRecord
data class RateRule(
    val rateRuleId: UUID?,
    val tenantId: UUID?,
    val resourcePoolId: UUID?,
    val ruleDefinitionJson: String?,
    val effectiveFrom: Instant?,
    val effectiveTo: Instant?,
)
