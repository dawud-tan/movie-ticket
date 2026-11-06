package com.protek.booking.security

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.isJavaBlank
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.service.DefaultTenant
import io.quarkus.security.identity.AuthenticationRequestContext
import io.quarkus.security.identity.SecurityIdentity
import io.quarkus.security.identity.SecurityIdentityAugmentor
import io.quarkus.security.runtime.QuarkusSecurityIdentity
import io.smallrye.mutiny.Uni
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.jwt.JsonWebToken
import org.jboss.logging.Logger
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Optional

/**
 * Turns a verified Google ID token into one of three roles plus a tenant scope,
 * by looking the identity up in `app_user`.
 *
 * This class is where the highest-consequence tenancy rule is actually implemented: the
 * tenant a request operates on comes from *here* — a database row keyed on the
 * token's `sub`/email — and is published as the [TENANT_ID_ATTRIBUTE]
 * identity attribute. Nothing downstream reads a tenant id from a header, path parameter,
 * or form field, because nothing downstream has any other source to read from. The
 * property that makes this safe is that the lookup key is signature-verified by
 * `quarkus-oidc` before this augmentor ever runs; a caller can choose which Google
 * account to sign in as, and cannot choose what that account maps to.
 *
 * Roles from the database rather than from token claims, deliberately: Google issues
 * identity, not authorization. Whether an email address is cinema staff is this platform's
 * decision, recorded in a table it controls — putting it in a claim would mean either
 * trusting a claim Google has no basis to make, or running a custom claims mapper as a
 * second source of truth alongside the table.
 */
@ApplicationScoped
class AppUserAugmentor : SecurityIdentityAugmentor {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var appUserRepository: AppUserRepository

    /**
     * Whether a Google account with no `app_user` row is auto-enrolled as a
     * `customer` of the configured default tenant.
     *
     * Proportionate to what authentication is for here — a verified email
     * address is proportionate identity for buying a cinema seat — and the alternative is
     * a manual approval step between a customer signing in and being able to book, which
     * no cinema would run. Staff and admin roles are never auto-granted: those come from
     * provisioning, so the worst case for an unexpected sign-in is an identity that can
     * book a seat, not one that can administer a hall.
     */
    @ConfigProperty(name = "booking.auth.auto-provision-customers", defaultValue = "true")
    @JvmField
    final var autoProvisionCustomers: Boolean = true

    /** The tenant a newly-seen customer identity is enrolled into. Deliberately
     * [DefaultTenant] rather than `CurrentTenant`:
     * `CurrentTenant` reads the identity attribute this class sets, so depending on it
     * here would be a cycle. */
    @Inject
    lateinit var defaultTenant: DefaultTenant

    override fun augment(identity: SecurityIdentity?, context: AuthenticationRequestContext): Uni<SecurityIdentity> {
        if (identity == null || identity.isAnonymous) {
            // Anonymous browsing stays anonymous: the first two use cases (browse
            // showtimes, view a seat map) are public, and giving an anonymous request a
            // role here would be the quiet start of an authorization hole.
            return Uni.createFrom().item(identity)
        }
        // JDBC is blocking; augment() runs on the event loop. runBlocking moves the lookup
        // to a worker thread rather than blocking the loop — the same reason the rest of
        // this codebase's request handlers are plain blocking endpoints.
        return context.runBlocking { augmentBlocking(identity) }
    }

    private fun augmentBlocking(identity: SecurityIdentity): SecurityIdentity {
        val subject: String? = identity.principal.name
        val email = emailClaim(identity)

        val resolved: Optional<AppUser> = db.inTransaction { c ->
            val bySubject = appUserRepository.findBySubject(c, subject)
            if (bySubject.isPresent) {
                appUserRepository.touchLastLogin(c, bySubject.get().appUserId)
                return@inTransaction bySubject
            }
            // isJavaBlank, not isBlank: Kotlin's would call a lone U+00A0 blank (JavaStrings.kt).
            if (email == null || email.isJavaBlank()) {
                // No subject match and no email to fall back on: nothing to map this
                // identity to. Authenticated but unroleable — see below.
                return@inTransaction Optional.empty()
            }
            val byEmail = appUserRepository.findByEmail(c, email)
            if (byEmail.isPresent) {
                // First login for a pre-provisioned staff/admin row: bind the stable
                // subject now so later logins stop depending on the email address.
                appUserRepository.bindSubjectOnFirstLogin(c, byEmail.get().appUserId, subject)
                return@inTransaction byEmail
            }
            if (!autoProvisionCustomers) {
                return@inTransaction Optional.empty()
            }
            val tenantId = defaultTenant.id()
            val created = appUserRepository.insert(c, email, AppUser.ROLE_CUSTOMER, tenantId, null)
            LOG.infof("Auto-provisioned customer identity %s for tenant %s", email, tenantId)
            Optional.of(created)
        }

        if (resolved.isEmpty) {
            LOG.warnf("Authenticated identity %s has no app_user row and was not auto-provisioned; "
                    + "no roles granted", email ?: subject)
            return identity
        }
        val user = resolved.get()
        if (!user.active) {
            // Deactivated rather than deleted, so the audit trail keeps referring to a row
            // that still exists. Granting no roles is the enforcement: every protected
            // endpoint rejects them, while public browsing still works.
            LOG.warnf("Identity %s is deactivated; no roles granted", user.email)
            return identity
        }

        val builder: QuarkusSecurityIdentity.Builder = QuarkusSecurityIdentity.builder(identity)
            .addRole(user.role)
            .addAttribute(APP_USER_ID_ATTRIBUTE, user.appUserId)
            .addAttribute(EMAIL_ATTRIBUTE, user.email)
        // The app_user staff-management capability, granted IN ADDITION to tenant_staff rather than
        // instead of it — see Roles.STAFF_MANAGER for why it is not a fourth role. The
        // role() check is redundant against the CHECK constraint and kept anyway: this is the
        // line that turns a column into a privilege, and it should not depend on a constraint
        // in another file staying correct.
        if (Roles.TENANT_STAFF == user.role && user.canManageStaff) {
            builder.addRole(Roles.STAFF_MANAGER)
        }
        // Display-only claims, and neither is ever an authorization input: the email above is
        // the identity, and it comes from a row this platform controls rather than from the
        // token. These two are what the profile page shows back to the person who signed in.
        val name = claim(identity, "name")
        if (name != null && !name.toString().isJavaBlank()) {
            builder.addAttribute(NAME_ATTRIBUTE, name.toString())
        }
        val birthdate = birthdateClaim(identity)
        if (birthdate != null) {
            builder.addAttribute(BIRTHDATE_ATTRIBUTE, birthdate)
        }
        if (user.tenantId != null) {
            builder.addAttribute(TENANT_ID_ATTRIBUTE, user.tenantId)
        }
        if (user.customerId != null) {
            builder.addAttribute(CUSTOMER_ID_ATTRIBUTE, user.customerId)
        }
        // platform_admin deliberately gets NO tenant attribute: it has no home tenant, and
        // defaulting it to one would make a cross-tenant admin action silently operate on
        // the wrong tenant. Platform-admin endpoints name their tenant explicitly instead.
        return builder.build()
    }

    /**
     * Google puts the verified address in the `email` claim. `email_verified` is
     * checked because an unverified address is not identity — anyone can type someone
     * else's address into an account they control, and the whole basis for treating email as
     * proportionate identity here is that the provider verified it. An unverified
     * address returns null, which lands the caller in the "authenticated but unroleable"
     * branch above rather than being auto-provisioned under an address they may not own.
     */
    private fun emailClaim(identity: SecurityIdentity): String? {
        val verified = claim(identity, "email_verified")
        if (verified != null && !java.lang.Boolean.parseBoolean(verified.toString())) {
            return null
        }
        val email = claim(identity, "email")
        return email?.toString()
    }

    /**
     * The `birthdate` claim as a [LocalDate], or null.
     *
     * OpenID Connect specifies the value as `YYYY-MM-DD`, and explicitly permits
     * `YYYY` alone and `0000-MM-DD` for a year the subject would rather not give.
     * Only the full form is accepted here: a year on its own cannot produce an age that is
     * right on both sides of a birthday, and `0000` is a deliberate refusal to say. Both
     * of those are "not provided", which the profile page already renders honestly.
     *
     * Anything unparseable is also "not provided" rather than an exception. This runs inside
     * `augment()`, so throwing here would fail the whole sign-in over a display field.
     */
    private fun birthdateClaim(identity: SecurityIdentity): LocalDate? {
        val raw = claim(identity, "birthdate") ?: return null
        try {
            // Java's String.strip(): Character.isWhitespace at both ends, which is neither
            // Kotlin's trim() nor javaTrim(). No
            // supplementary code point is whitespace, so testing chars is the same as code points.
            val parsed = LocalDate.parse(raw.toString().trim { Character.isWhitespace(it) })
            return if (parsed.year <= 0) null else parsed
        } catch (e: DateTimeParseException) {
            LOG.debugf("Ignoring unparseable birthdate claim %s", raw)
            return null
        }
    }

    /**
     * Reads one claim, from whichever of the two places it actually lives.
     *
     * Under real `quarkus-oidc`, claims are on the JWT principal, not among the
     * identity's attributes — reading only attributes would silently find nothing in
     * production while working fine in tests. Under `@TestSecurity`, the reverse: the
     * principal is a plain principal and claims are supplied as attributes. Checking both
     * means the authorization tests exercise this same method rather than a path that only
     * exists for them.
     */
    private fun claim(identity: SecurityIdentity, name: String): Any? {
        val principal = identity.principal
        if (principal is JsonWebToken) {
            val value: Any? = principal.getClaim<Any?>(name)
            if (value != null) {
                return value
            }
        }
        return identity.getAttribute<Any?>(name)
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(AppUserAugmentor::class.java)

        /** Identity attribute carrying the resolved tenant. Read by `CurrentTenant`. */
        const val TENANT_ID_ATTRIBUTE = "booking.tenantId"
        /** Identity attribute carrying `app_user.app_user_id`, for `audit_log.actor_id`. */
        const val APP_USER_ID_ATTRIBUTE = "booking.appUserId"
        /** Identity attribute carrying `app_user.customer_id`, absent until this customer
         * has booked at least once. Used for the per-customer ownership checks RLS cannot make:
         * RLS isolates tenants, not customers within a tenant. */
        const val CUSTOMER_ID_ATTRIBUTE = "booking.customerId"
        /** Identity attribute carrying the verified email, for display and for linking a
         * customer row on first booking. */
        const val EMAIL_ATTRIBUTE = "booking.email"
        /** Identity attribute carrying the provider's `name` claim, for the profile page.
         * Absent when the provider does not send one — which is a real state and
         * not an error, so the page falls back to the email address rather than to a blank. */
        const val NAME_ATTRIBUTE = "booking.name"
        /**
         * Identity attribute carrying the provider's `birthdate` claim, when there is one.
         *
         * **Expected to be absent on this deployment, and the profile page says so.** The
         * claim is defined by OpenID Connect and Google does not put it in an ID token issued for
         * the `openid email profile` scopes this application asks for — it needs the separate
         * People API birthday scope and an app-verification round. Read anyway rather than ignored,
         * because the plumbing is two lines and reading it is what makes a provider that *does*
         * supply it work with no further change. Nothing infers an age from anything else.
         */
        const val BIRTHDATE_ATTRIBUTE = "booking.birthdate"
    }
}
