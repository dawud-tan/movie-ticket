package com.protek.booking.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@JvmRecord
data class Customer(
    val customerId: UUID?,
    val tenantId: UUID?,
    val fullName: String?,
    val phone: String?,
    val email: String?,
    val dateOfBirthAttested: Boolean,
    /**
     * When this customer was born, or null — which is what it is on this deployment.
     * Google's OIDC `birthdate` claim is not in the `openid email
     * profile` scopes this application requests, so the column is filled only where a
     * provider supplies it. The profile page states the age when it is known and says so
     * plainly when it is not; nothing infers one.
     *
     * Distinct from [dateOfBirthAttested], which records that somebody ticked
     * "I am old enough" and cannot produce a number.
     */
    val dateOfBirth: LocalDate?,
    /**
     * True for the single per-tenant "Walk-in" row that counter sales attach to.
     * A placeholder, not a person: it exists so `reservation_group.customer_id`
     * can stay `NOT NULL` for cash sales that leave no identity behind, instead of
     * pushing a null branch into every query and report that joins through customer.
     */
    val walkInPlaceholder: Boolean,
    val createdAt: Instant?,
)
