package com.protek.booking.support

import com.protek.booking.security.CurrentActor
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * A [CurrentActor] that answers "who is acting" directly, for tests about authorisation
 * rules that turn on the acting identity rather than on a role annotation.
 *
 * Why this exists rather than another `@TestSecurity` annotation: the ownership rules
 * in `BookingService` read `CurrentActor.customerId()`, which is a **UUID**
 * attribute the `SecurityIdentityAugmentor` sets from a database row.
 * `@TestSecurity` bypasses the augmentor entirely and its
 * `@SecurityAttribute` values are Strings, so there is no way to express "a signed-in
 * customer who owns customer row X" through the annotation at all.
 *
 * **Extends the real bean rather than implementing an interface**, because
 * `QuarkusMock.installMockForType` resolves by bean class — the same constraint
 * `StubMidtransGateway` and `TestSignatureVerifier` are shaped by.
 *
 * `open`, because `ProfilePageTest` subclasses it; all-open does not reach a class with no
 * bean annotation. The factories are `@JvmStatic` so the Java tests' `ActingAs.customer(id)`
 * still compiles (pattern 3).
 */
open class ActingAs
/**
 * Protected rather than private so a test can subclass this double to supply the identity
 * claims it deliberately answers empty for — the profile page is about exactly those, and
 * `@TestSecurity`'s `@SecurityAttribute` values are Strings where `birthDate()` is a
 * [java.time.LocalDate].
 */
protected constructor(
    private val authenticated: Boolean,
    private val staff: Boolean,
    private val customerId: UUID?,
    private val appUserId: UUID?,
) : CurrentActor() {

    companion object {
        /** A signed-in customer who owns `customerId`. */
        @JvmStatic
        fun customer(customerId: UUID?): ActingAs = ActingAs(true, false, customerId, null)

        /**
         * A signed-in customer with a known `app_user` row — for the booking drafts, which
         * are keyed on the sign-in rather than on a `customer` row.
         *
         * `customerId` may be null, and usually is here: a customer who has just signed in
         * and never booked has no `customer` row at all, and that is exactly the person
         * opening their first draft. `appUserId` must be a real row, since
         * `booking_draft.app_user_id` carries a foreign key.
         */
        @JvmStatic
        fun customer(customerId: UUID?, appUserId: UUID?): ActingAs = ActingAs(true, false, customerId, appUserId)

        /** Cinema staff, who own no customer row of their own. */
        @JvmStatic
        fun staff(): ActingAs = ActingAs(true, true, null, null)

        /**
         * Nobody at all — the scheduled sweeps, provisioning, a payment webhook.
         *
         * Needed because "no identity" cannot be expressed by simply installing nothing once a
         * test has already installed something: mocks are undone between test methods, not within
         * one, and the audit attribution rules are about what happens when the actor *changes*
         * mid-scenario. `new CurrentActor()` is not an alternative — its
         * `Instance<SecurityIdentity>` is injected, so a hand-built one throws.
         *
         * `createdByActor()` resolves to `'system'` for this, from the real bean's
         * own logic rather than from an override here.
         */
        @JvmStatic
        fun nobody(): ActingAs = ActingAs(false, false, null, null)

        /**
         * Cinema staff with a known `app_user` row — for the writes that record *which*
         * staff member acted, rather than merely that staff did.
         *
         * Added for the admission scan, which stamps `reservation.admitted_by_app_user_id`
         * from this attribute. `@TestSecurity` cannot express it for the same reason it cannot
         * express [customer]: the augmentor that sets the attribute is bypassed, and the
         * annotation's values are Strings where this is a UUID. `appUserId` must be a real row,
         * since the column carries a foreign key.
         */
        @JvmStatic
        fun staff(appUserId: UUID?): ActingAs = ActingAs(true, true, null, appUserId)
    }

    override fun isAuthenticated(): Boolean = authenticated

    override fun isStaff(): Boolean = staff

    override fun isPlatformAdmin(): Boolean = false

    override fun isCustomer(): Boolean = authenticated && !staff

    override fun customerId(): Optional<UUID> = Optional.ofNullable(customerId)

    override fun appUserId(): Optional<UUID> = Optional.ofNullable(appUserId)

    /**
     * No email address, and overridden rather than inherited because the inherited version reads
     * the injected `SecurityIdentity` — which a hand-built double does not have.
     *
     * Not a detail: `base.html` renders `inject:actor.displayName` on every page,
     * and `displayName` is built on `email()`. Without this, any test that installs
     * this double and then renders a page gets a 500 from the header rather than the assertion it
     * was making. Callers who need an identity-bearing email use `@TestSecurity`'s
     * `booking.email` attribute instead; the fallback here is the same
     * "signed in" the real bean shows for a login with no email claim.
     */
    override fun email(): Optional<String> = Optional.empty()

    /**
     * No `name` claim either, and overridden for exactly the reason above — which this
     * one proved by breaking. The profile page made `displayName()` prefer the name claim over the
     * email, so the inherited `fullName()` became the first thing every rendered page
     * called, and it reads the injected `SecurityIdentity` that this double does not have:
     * three order-history tests turned into 500s from the header alone.
     *
     * The rule that follows, for whoever adds the next accessor to `CurrentActor`: any
     * method that reads the identity and is reachable from `base.html` needs an override
     * here, or every test using this double fails in the layout rather than in its assertion.
     */
    override fun fullName(): Optional<String> = Optional.empty()

    /** Same rule as [fullName]. Not reachable from `base.html` today, and
     * overridden anyway: the profile page reads it, and the cost of being wrong about "no page
     * renders this" is a 500 in a test about something else. */
    override fun birthDate(): Optional<LocalDate> = Optional.empty()
}
