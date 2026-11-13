package com.protek.booking.service

import com.protek.booking.i18n.Language
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.i18n.Msg
import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.LocalDate
import java.time.Period
import java.util.HexFormat

/**
 * LSF age classification, and the attestation a customer makes against it.
 *
 * **What this closes.** `customer.date_of_birth_attested` has existed since
 * `V1__init_schema.sql` and no Java read or wrote it — a column promising a control the
 * application did not implement. `film.classification` was the second half of the
 * same shape: the rating was recorded and displayed, and nothing anywhere acted on it. This is
 * the acting.
 *
 * **What it deliberately is not.** Identity verification. It is the same control the
 * incumbent applies online — the customer asserts, in one tick, that they meet the rating — and
 * Enforcement at the door stays a staff function. The seat map says so out
 * loud rather than implying the tick admits anybody.
 *
 * **The trap this design turns on, and the reason the answer is never cached.**
 * `date_of_birth_attested` is one boolean, and there are three thresholds. A customer who
 * attested to being 13 has said nothing about being 21, so the flag can record that an
 * attestation has happened but can *never* be read to skip asking for a different film.
 * Every restricted hold asks again, and the row that carries the evidence is the
 * `consent_record` — which names the classification and hashes the exact wording shown.
 * Anyone "optimising" the second prompt away by reading the boolean reintroduces exactly the
 * defect the age attestation exists to fix, and `AgeAttestationTest` has the guard.
 *
 * Static rather than a bean, like `Slug` and `SeatMapSupport`: it has no
 * *injected* dependencies, and both readers — the template that shows the sentence and the
 * service that hashes it — must be looking at the same string. That invariant is what
 * [Gate] carries the resolved statement for, and it survives translation unchanged: the
 * sentence is produced once, here, and both readers take it off the same record.
 *
 * **Translated 2026-09-17, and the decision behind it is the reason this class takes a
 * [Language] rather than a [Msg].** An Indonesian attestation hashes to a different
 * value than an English one, so one classification stopped having one hash — which is why this
 * was held open as a legal question rather than done with the rest of `customer/`. It is nevertheless
 * correct, and the previous behaviour was not: the column exists to record what the person
 * actually read, and hashing a sentence the customer never saw records an agreement that did not
 * happen. The old behaviour only looked right because there was one language.
 *
 * **What that costs, and how it is paid for.** A hash on its own is no longer resolvable
 * back to a sentence, because two different sentences now sit behind one classification. So
 * [Gate.consentPurpose] carries the language tag beside the classification —
 * `age_attestation:17+:id` — and the language is passed in as a `Language` rather
 * than as a bundle precisely so the tag and the sentence cannot disagree. Handing in a
 * `Msg` would let a caller pair the Indonesian bundle with `en` and write a row that
 * lies about what was read.
 *
 * **No migration, and rows written before this change stay valid.** `purpose` is
 * `text` and was already structured. Historic rows carry the two-segment form
 * `age_attestation:17+` with no tag, which is self-describing: there was one language when
 * they were written and it was English. A query reads
 * `NULLIF(split_part(purpose, ':', 3), '')` and treats NULL as English; grouping by
 * `split_part(purpose, ':', 1)` is unaffected, and so is reading the classification out of
 * segment 2.
 */
// An object with @JvmStatic members: Java and the tests still call
// AgeClassification.gateFor(...) and read AgeClassification.LEGAL_BASIS statically.
object AgeClassification {

    /**
     * The `consent_record.purpose` prefix. The classification and the language are
     * appended, so a row reads `age_attestation:17+:id` rather than needing the hash to be
     * reversed to find out what was asserted, or which of two sentences was read. Grouping still
     * works: `split_part(purpose, ':', 1)`.
     */
    private const val CONSENT_PURPOSE = "age_attestation"

    /**
     * `consent_record.legal_basis` for these rows, from V1's CHECK list.
     *
     * Not `'consent'`: the customer is not consenting to processing, they are asserting
     * a fact, and the reason we hold that assertion is the operator's obligation to enforce a
     * classification the state sets. `'contract'` would be the basis for the booking
     * itself, which is a different row's business.
     */
    const val LEGAL_BASIS = "legal_obligation"

    /** `SU` — semua umur. The one classification that gates nothing. */
    private const val ALL_AGES = 0

    /** A rating the CHECK constraint should not permit. See [gateFor]. */
    private const val UNKNOWN_AGE = -1

    /**
     * What the customer is being asked, for one film's rating.
     *
     * @param statement the exact sentence shown beside the tick box, **in the language it was
     *        shown in**, or null when the rating gates nothing. Null is what
     *        [restricted] is derived from, so a gate cannot claim to be restricted while
     *        having nothing to show.
     * @param languageTag the language `statement` is written in. Carried on the record
     *        rather than looked up again at hashing time: these two must describe each other, and
     *        the only way to guarantee that is for one call to produce both.
     */
    @JvmRecord
    data class Gate(val classification: String?, val minimumAge: Int, val statement: String?,
                    val languageTag: String?) {

        fun restricted(): Boolean = statement != null

        /**
         * What `consent_record.purpose` records for an attestation against this rating.
         *
         * Three segments since 2026-09-17: `age_attestation:17+:id`. The language is
         * what makes the hash beside it resolvable — two sentences now sit behind one
         * classification, and a hash alone cannot say which. Rows written before that carry two
         * segments and are English by construction; see this class's javadoc for the query.
         */
        fun consentPurpose(): String = CONSENT_PURPOSE + ":" + classification + ":" + languageTag
    }

    /**
     * What a known date of birth says about one film's gate — a warning, and never a decision.
     *
     * **Additive to the tick, not a replacement for it.** `attestAgeOrRefuse` still
     * refuses a restricted hold that arrives without the attestation, whether or not an advisory
     * was shown. This exists because an age that contradicts the rating is worth saying out loud
     * before the customer pays for a seat they may be turned away from — the door is where the
     * rating is enforced, and finding out there is the expensive way to find out.
     *
     * **It cannot block, for the same reason `date_of_birth_attested` cannot permit:
     * the date is self-declared.** Nobody checked it. Refusing a sale over an unverified number
     * the customer typed about themselves would claim an identity control this application does
     * not have, and would strand a customer who mistyped their birth year. Warning costs a
     * sentence and takes nothing off sale.
     */
    /**
     * @param message the sentence the seat map shows, resolved at construction for the language
     *        the page is in. A field rather than a method for the reason `ScanResult` gives
     *        for its headline: a template renders `{ageAdvisory.message}` and cannot hand a
     *        bundle in. **Unlike the attestation, this one is hashed into nothing** — it warns
     *        and records nothing — so it is ordinary copy and editing it costs nothing.
     */
    @JvmRecord
    data class Advisory(val classification: String?, val minimumAge: Int, val ageAtShowtime: Int,
                        val message: String?)

    /** How a hold reports what the customer did with the tick box — see [Attestation]. */
    enum class Attestation {
        /** The box was ticked. */
        TICKED,
        /** The self-service form arrived without it. Refused for a restricted film. */
        NOT_TICKED,
        /**
         * The counter, which does not ask. Stated by the caller rather than inferred, for the
         * same reason `PurchaseCap.Limits` is passed in: a fourth booking entry point must
         * not inherit an exemption by saying nothing.
         */
        NOT_ASKED
    }

    /**
     * The gate for a film's rating, or null when the film has none.
     *
     * Null is the ordinary case, not a defect: `film.classification` is nullable on
     * purpose (the film table says why — a NOT NULL column would have claimed this control before it
     * existed), and a `label`-only showing has no film at all. Both render and sell
     * exactly as they did before the age attestation.
     *
     * **An unrecognised rating gates rather than waves through.** The CHECK constraint
     * permits only the four LSF values, so this branch should be unreachable — but the failure
     * direction matters more than the likelihood: treating an unknown rating as unrestricted
     * would sell a restricted film with no attestation, silently, while asking about a rating we
     * cannot name the age for costs one tick and takes nothing off sale.
     */
    @JvmStatic
    fun gateFor(classification: String?, language: Language?): Gate? {
        // isJavaBlank and javaTrim, not Kotlin's isBlank and trim: the rating is
        // what is hashed into consent_record.purpose, so it must be trimmed exactly as before.
        if (classification == null || classification.isJavaBlank()) {
            return null
        }
        val value = classification.javaTrim()
        val minimumAge = when (value) {
            "SU" -> ALL_AGES
            "13+" -> 13
            "17+" -> 17
            "21+" -> 21
            else -> UNKNOWN_AGE
        }
        val shownIn = language ?: Language.DEFAULT
        return Gate(value, minimumAge,
            statementFor(value, minimumAge, LanguagePreference.messagesIn(shownIn)),
            shownIn.tag())
    }

    /**
     * The advisory for one gate and one known date of birth, or null when there is nothing to say.
     *
     * Null is overwhelmingly the ordinary case — no gate, an unrestricted rating, no date of
     * birth on file, or an age that meets the rating — and a null renders nothing at all. Every
     * showing that sold before this existed sells identically after it.
     *
     * **The age is taken on the showing's date, not today's.** The door decides on the day,
     * so a customer who is 16 now and 17 by the showing meets the rating and must not be told
     * otherwise. A warning that is wrong on a date the customer can check themselves is worse
     * than no warning: it teaches them to dismiss the next one. [Period] rather than
     * dividing days by 365.25, for the reason `CustomerProfileService.Profile.age` records
     * — only a calendar knows whether a birthday has happened yet.
     *
     * **An unrecognised rating warns about nothing, though [gateFor] still gates on
     * it.** Not an inconsistency: gating on a rating we cannot name costs one tick, whereas
     * warning against it would have to invent the threshold it compares to.
     *
     * @param showDate the showing's date in the branch's own zone. A caller passing today's date
     *        gets a defensible answer for a showing today and a wrong one for a booking made
     *        months ahead, which is exactly the booking a threshold birthday falls inside.
     */
    @JvmStatic
    fun advisoryFor(gate: Gate?, dateOfBirth: LocalDate?, showDate: LocalDate?,
                    language: Language?): Advisory? {
        if (gate == null || !gate.restricted() || dateOfBirth == null || showDate == null) {
            return null
        }
        if (gate.minimumAge <= ALL_AGES) {
            return null
        }
        val age = Period.between(dateOfBirth, showDate).years
        // A negative age means a date of birth after the showing, which the profile form refuses
        // to store. Guarded anyway rather than rendered: "you will be -3" is the kind of sentence
        // that makes a customer distrust everything else on the page.
        if (age < 0 || age >= gate.minimumAge) {
            return null
        }
        // The gate's own language, not the caller's: the advisory sits directly above the tick
        // box, and the one thing worse than an untranslated warning is a warning and an
        // attestation in two different languages on the same line of the page.
        val msg = LanguagePreference.messagesIn(
            Language.ofTag(gate.languageTag).orElse(Language.DEFAULT))
        return Advisory(gate.classification, gate.minimumAge, age,
            msg.ageAdvisoryMessage(age, gate.classification))
    }

    private fun statementFor(classification: String, minimumAge: Int, msg: Msg): String? {
        if (minimumAge == ALL_AGES) {
            return null
        }
        if (minimumAge == UNKNOWN_AGE) {
            return msg.ageStatementUnknownRating(classification)
        }
        return msg.ageStatementMinimum(minimumAge)
    }

    /**
     * `consent_record.policy_text_hash` for a statement — SHA-256, lower-case hex.
     *
     * The column is NOT NULL because a consent row that cannot say *what* was agreed
     * to is not evidence of anything. Hashing rather than storing the sentence keeps the record
     * fixed against a later edit of the wording: change the sentence and old rows stop matching,
     * which is the property that makes them worth keeping.
     *
     * **A hash is not self-describing and since 2026-09-17 it is not even unique per
     * classification.** Two languages means two sentences behind one rating, so resolving a row
     * back to what was read takes the hash *and* the language tag in
     * [Gate.consentPurpose]. Always hash [Gate.statement] rather than a sentence
     * assembled at the call site — the gate is the one place that knows which language it made.
     */
    // Nullable as the Java accepted it; a null still fails with an NPE, as
    // statement.getBytes did.
    @JvmStatic
    fun hashOf(statement: String?): String {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            return HexFormat.of().formatHex(digest.digest(statement!!.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 is required of every JRE by the specification; if it is missing, the
            // deployment is broken in a way no fallback here could honestly paper over.
            throw IllegalStateException("SHA-256 unavailable", e)
        }
    }
}
