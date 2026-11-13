package com.protek.booking.domain

import java.util.UUID

@JvmRecord
data class ResourceUnit(
    val resourceUnitId: UUID?,
    val tenantId: UUID?,
    val resourcePoolId: UUID?,
    val label: String?,
    val groupKey: String?,
    val sequenceNo: Int?,
    val isActive: Boolean,
    /** A wheelchair space, marked only: anyone may book it. Stored as
     * `attributes.accessible`, no column, because V1 is the only migration. */
    val accessible: Boolean = false,
)
