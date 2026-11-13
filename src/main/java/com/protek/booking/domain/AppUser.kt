package com.protek.booking.domain

import java.time.Instant
import java.util.UUID

/**
 * An authenticated identity mapped to one of three roles. `tenantId` is
 * non-null for `customer` and `tenant_staff` and null for
 * `platform_admin` — enforced by a CHECK constraint, not by convention, since a
 * `tenant_staff` with no tenant scope would be global staff, which must never exist.
 *
 * This is the record whose `tenantId` becomes `app.current_tenant_id` for
 * an authenticated request. That is the only sanctioned source of tenant context — never
 * a header, path parameter, or form field.
 *
 * `canManageStaff` is a capability rather than a fourth role: a manager is
 * staff plus permission to run `/admin/staff`, not a different kind of identity. The
 * CHECK constraint allows it only alongside `tenant_staff`.
 *
 * A `@JvmRecord` so Java's `user.tenantId()` and Qute's `user.email` are untouched. Every
 * reference-typed field is nullable, because the Java record's were and a non-null Kotlin
 * parameter would turn a null from a Java caller into an exception here (the same reasoning
 * as [Tenant]).
 */
@JvmRecord
data class AppUser(
    val appUserId: UUID?,
    val subject: String?,
    val email: String?,
    val role: String?,
    val canManageStaff: Boolean,
    val tenantId: UUID?,
    val customerId: UUID?,
    val active: Boolean,
    val createdAt: Instant?,
    val lastLoginAt: Instant?,
) {
    companion object {
        const val ROLE_CUSTOMER = "customer"
        const val ROLE_TENANT_STAFF = "tenant_staff"
        const val ROLE_PLATFORM_ADMIN = "platform_admin"
    }
}
