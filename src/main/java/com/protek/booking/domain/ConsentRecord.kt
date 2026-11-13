package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/**
 * One thing a customer asserted or agreed to, and when.
 *
 * The table had no Java at all until it got its first
 * writer: the age attestation a customer makes against a film's LSF rating. It is written once
 * per attestation rather than once per customer — the row carries the wording's hash and the
 * moment, so collapsing repeat attestations into one row would lose the only two facts it
 * exists to state.
 *
 * `ip_address` is on the table and deliberately absent here. Nothing in this
 * application records a caller's IP anywhere, and starting with this row would be a new
 * personal-data decision rather than a field to fill in because the column is
 * there.
 */
@JvmRecord
data class ConsentRecord(
    val consentId: UUID?,
    val tenantId: UUID?,
    val customerId: UUID?,
    /** What was asserted — `age_attestation:17+` for the age attestation's rows. */
    val purpose: String?,
    val legalBasis: String?,
    val consentGivenAt: Instant?,
    val consentWithdrawnAt: Instant?,
    /** SHA-256 of the exact sentence the customer was shown. See `AgeClassification`. */
    val policyTextHash: String?,
    val createdAt: Instant?,
)
