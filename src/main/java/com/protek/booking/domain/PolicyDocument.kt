package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

@JvmRecord
data class PolicyDocument(
    val policyDocumentId: UUID?,
    val tenantId: UUID?,
    val docType: String?,
    val version: Int,
    val content: String?,
    val effectiveFrom: Instant?,
)
