package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Customer
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.security.CurrentActor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.time.LocalDate
import java.time.Period
import java.util.Optional
import java.util.UUID

/**
 * What `/profile` shows: the name, email and age this account signed up with.
 *
 * **Almost everything here comes from the sign-in, not from a form.** A verified email is proportionate identity for buying a cinema seat; collecting and
 * maintaining a separate profile on top of that would be collecting more than the purpose
 * needs, which is what data minimisation is about. Name and email are therefore
 * read-only, and the place to change either is the Google account that asserts it.
 *
 * **The date of birth is the one exception, and it is one precisely because it is the one
 * field the provider never asserts.** A form for a name would either diverge from the token
 * or be reverted by the next sign-in; V1's comment on customer.date_of_birth records that Google sends no birthdate for the scopes
 * this application requests, so there is nothing here for a form to diverge from and nothing to
 * revert it. Asking is the only way that column is ever filled on this deployment, and the age attestation's
 * age advisory is what reads it — see [updateDateOfBirth].
 *
 * **Three sources, and the page is honest about the gap between them.** The name and the
 * email arrive on every request in the ID token. The date of birth comes from the customer's own
 * entry, or from a provider claim on a deployment whose provider sends one — Google does not,
 * for the scopes this application requests (see `AppUserAugmentor`). When none of them has
 * supplied it the age is genuinely unknown, and the page says so rather than deriving one from
 * the age attestation's boolean — which records that somebody ticked "I am old enough" and cannot
 * distinguish 17 from 70.
 */
@ApplicationScoped
class CustomerProfileService {

    /** The two refusals below land in customer/profile.html's error banner, on a page that is
     * otherwise fully translated. */
    @Inject
    lateinit var language: LanguagePreference

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var currentTenant: CurrentTenant
    @Inject
    lateinit var currentActor: CurrentActor
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var appUserRepo: AppUserRepository

    /**
     * The signed-in customer's profile.
     *
     * Takes no parameter, for the reason `BookingService.listBookingHistory` gives:
     * whose profile to show is the acting identity's to know and not the caller's to ask for.
     * A method that accepted a customer id would be one URL away from being a directory of
     * everybody who has ever booked.
     */
    fun current(): Profile {
        val customerId = currentActor.customerId()
        // flatMap over the repository's Optional where the Java mapped to findById(...).orElse(null):
        // the same statements, and the same empty result for a missing row.
        val customer: Optional<Customer> = customerId.flatMap { id ->
            val tenantId = currentTenant.id()
            db.inTenantTransaction(tenantId) { c ->
                // Fill the row's date of birth from the token, if the token has one and the row
                // does not. Inside the same transaction as the read, so the page cannot show a
                // blank it has just filled in.
                currentActor.birthDate().ifPresent { birthDate ->
                    customerRepo.fillDateOfBirth(c, tenantId, id, birthDate)
                }
                customerRepo.findById(c, tenantId, id)
            }
        }

        // The stored value first, the claim second. The row is what an operator can correct and
        // what survives a provider that stops sending the claim; the claim is what fills it.
        val dateOfBirth: LocalDate? = customer.map { it.dateOfBirth }
            .or { currentActor.birthDate() }
            .orElse(null)

        return Profile(
            currentActor.fullName()
                .or { customer.map { it.fullName } }
                .or { currentActor.email() }
                .orElse("Signed in"),
            currentActor.email().orElse(null),
            dateOfBirth,
            customer.map { it.dateOfBirthAttested }.orElse(false),
            customerId.isPresent)
    }

    /**
     * The date of birth this application knows for the signed-in customer — the stored row
     * first, a provider claim second.
     *
     * Exists so that the seat map's age advisory and this page cannot disagree about what we
     * know. [current] applies the same precedence for display, and a second copy of
     * "which source wins" would eventually drift into a page showing one age beside a warning
     * computed from another.
     *
     * **Costs no query for an account with no customer row** — every account that has not
     * booked yet, and every anonymous viewer of a seat map. Those get the claim if there is one
     * and an empty Optional otherwise, rather than a transaction opened to learn the same thing.
     */
    fun knownDateOfBirth(): Optional<LocalDate> {
        val customerId = currentActor.customerId()
        if (customerId.isEmpty) {
            return currentActor.birthDate()
        }
        val tenantId = currentTenant.id()
        return db.inTenantTransaction(tenantId) { c ->
            // flatMap with ofNullable is what the Java's map(Customer::dateOfBirth) did, typed
            // so Kotlin keeps Optional<LocalDate> rather than Optional<LocalDate?>.
            customerRepo.findById(c, tenantId, customerId.get())
                .flatMap { Optional.ofNullable(it.dateOfBirth) }
        }
            .or { currentActor.birthDate() }
    }

    /**
     * Records what the customer typed into the date field on `/profile`.
     *
     * **Self-declared, and read as such everywhere it is read.** Nobody checks it, so
     * `AgeClassification.advisoryFor` only ever warns on it and the age attestation's tick still gates the
     * sale. Nothing downstream may promote it into an identity control this application does not
     * have.
     *
     * The bounds are deliberately loose. A date is refused only when it cannot describe a
     * living cinema-goer — in the future, or further back than [MAX_AGE_YEARS] — because
     * a wrong refusal stops a customer recording a true fact, while a wrong acceptance costs one
     * warning that does not fire.
     *
     * @param dateOfBirth null to clear it.
     * @return a message to show the customer, or null when the write happened.
     */
    fun updateDateOfBirth(dateOfBirth: LocalDate?): String? {
        val today = LocalDate.now()
        if (dateOfBirth != null && dateOfBirth.isAfter(today)) {
            return language.messages().profileDateInFuture()
        }
        if (dateOfBirth != null && dateOfBirth.isBefore(today.minusYears(MAX_AGE_YEARS.toLong()))) {
            return language.messages().profileDateTooOld(MAX_AGE_YEARS)
        }
        val tenantId = currentTenant.id()
        val existing = currentActor.customerId()
        db.inTenantTransaction(tenantId) { c ->
            val customerId = existing.orElseGet { createCustomerRow(c, tenantId) }
            customerRepo.setDateOfBirth(c, tenantId, customerId, dateOfBirth)
        }
        return null
    }

    /**
     * A `customer` row for an account that has not booked yet, so that a date of birth can
     * be recorded before the first booking rather than only after it.
     *
     * Without this the advisory would be silent on exactly the booking it is most worth
     * showing on — a new customer's first — because `customer` rows are created by the
     * hold, and there would be nothing to write to until one had happened.
     *
     * **Deliberately the same shape as `BookingService.resolveOrCreateByEmail`, and
     * deliberately not shared with it.** That one resolves from a hold form carrying a name and
     * a phone number; this has only the email the token verified, so a shared method would take
     * parameters that are null at one of its two call sites. What the two must agree on is the
     * key they resolve by — the email — and they do.
     *
     * The `app_user` link is written here for the reason the hold writes it: without it
     * `currentActor.customerId()` stays empty and the next visit creates a second row for
     * the same person.
     */
    private fun createCustomerRow(c: Connection, tenantId: UUID): UUID {
        val email = currentActor.email().orElse(null)
        val customer = (if (email == null) Optional.empty<Customer>()
                else customerRepo.findByEmail(c, tenantId, email))
            .orElseGet {
                customerRepo.insert(c, tenantId,
                    currentActor.fullName().orElse(email ?: "Customer"),
                    null, email, false)
            }
        currentActor.appUserId().ifPresent { appUserId ->
            appUserRepo.linkCustomer(c, appUserId, customer.customerId)
        }
        return customer.customerId!!
    }

    /**
     * @param dateOfBirth what the customer entered on this page, or a provider claim where one
     *        is sent — null until either happens, which is the state every account starts in.
     * @param ageAttested the age attestation's boolean: this customer has at some point asserted they meet a
     *        rating. Shown as what it is and never as an age, because one boolean cannot tell
     *        13+ from 21+ and nothing may read it as if it could.
     * @param hasBooked   whether a `customer` row exists at all. False until the first
     *        hold, which is why the page can be reached by somebody the booking side has never
     *        heard of — a real state, not an error.
     */
    @JvmRecord
    data class Profile(val name: String?, val email: String?, val dateOfBirth: LocalDate?,
                       val ageAttested: Boolean, val hasBooked: Boolean) {

        fun hasAge(): Boolean = dateOfBirth != null

        /**
         * Completed years, on today's date.
         *
         * [Period] rather than dividing days by 365.25: the arithmetic that matters
         * here is "has this person had their birthday yet this year", and only a calendar
         * knows. The two disagree for a few days around a birthday, which is precisely the
         * window an age gate is decided in.
         */
        fun age(): Int = if (dateOfBirth == null) 0 else Period.between(dateOfBirth, LocalDate.now()).years
    }

    private companion object {
        /**
         * The oldest date of birth this form will store, in years. 120 rather than something tighter
         * because the only job of this bound is to catch a typo'd century — a customer who really is
         * 111 must not be told their own date of birth is invalid.
         */
        const val MAX_AGE_YEARS = 120
    }
}
