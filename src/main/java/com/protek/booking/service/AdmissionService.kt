package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.i18n.Msg
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourceUnitRepository
import com.protek.booking.security.CurrentActor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.jboss.logging.Logger
import java.sql.Connection
import java.util.Locale
import java.util.UUID

/**
 * The door: one scanned admission token in, one decision out.
 *
 * **Scoped to a showing, not to the whole cinema.** An usher stands at the door of one
 * auditorium for one screening, and the ticket a customer holds is for a specific seat at a
 * specific time. Answering "is this a valid ticket" without asking "for *this* showing" would
 * admit tonight's 21:00 audience into the 19:00 screening, which is not a security failure but
 * is a full house and an argument. So a token for another showing is refused with a message
 * naming the showing it is actually for — the usher's job at that point is to redirect the
 * customer, and they need the fact to do it.
 *
 * **What makes double entry impossible** is `ReservationRepository#admit`'s WHERE
 * clause, not anything here. This class never reads-then-decides: it issues the conditional
 * UPDATE and asks why afterwards only when nothing moved. That ordering is the whole design —
 * see the repository method — and it is the same principle as the `EXCLUDE` constraint
 * being the anti-double-booking mechanism rather than a pre-check in front of it.
 */
@ApplicationScoped
class AdmissionService {

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var currentActor: CurrentActor

    @Inject
    lateinit var reservationRepo: ReservationRepository

    @Inject
    lateinit var unitRepo: ResourceUnitRepository

    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository

    /** The door's copy follows the page the usher is looking at, the same way every
     * `{msg:…}` does. Resolving to English outside a request is deliberate and safe —
     * see [LanguagePreference.current]. */
    @Inject
    lateinit var language: LanguagePreference

    /**
     * What the usher is told. Deliberately more than a boolean: every refusal has a different
     * next action, and "no" alone sends them to the wrong one.
     *
     * Each constant carries its own headline and severity so the template reads them as plain
     * property access. That is the house rule for Qute expressions — build-time-resolvable
     * accessors rather than logic in the markup — and it also means adding an outcome cannot
     * leave a branch of the template silently unstyled, the way a chain of `#if`s could.
     *
     * `severity` keeps the JVM accessor name `severity()` (not `getSeverity()`), so
     * callers and the Qute expression `result.outcome.severity` read it exactly as
     * they did when this was a Java enum.
     */
    enum class Outcome(
        /** The CSS class carrying the colour — `ok`, `warn` or `bad`. */
        @get:JvmName("severity") val severity: String,
    ) {
        /** Walked in just now, by this scan. */
        ADMITTED("ok"),

        /** A valid ticket for this showing that has already been used. The one an usher must be
         * able to tell apart from every other refusal, because it is the only one where the
         * likely explanation is a forwarded screenshot rather than a mistake. Amber, not red:
         * the overwhelmingly common cause is a party of four scanning one phone twice, and
         * colouring an honest mistake as a fraud attempt is how staff learn to ignore the
         * colour. */
        ALREADY_ADMITTED("warn"),

        /** A real ticket, for a different screening. */
        WRONG_SHOWING("warn"),

        /** This showing, but not in a state that admits anyone — unpaid, cancelled, expired, or
         * a screening that has already finished. */
        NOT_ADMISSIBLE("bad"),

        /** No such token in this cinema. Also what a token belonging to another tenant looks
         * like, since RLS returns zero rows rather than someone else's booking. */
        UNKNOWN_TOKEN("bad");

        /**
         * What the usher reads first, from arm's length, while the queue moves.
         *
         * **The headline left this enum when the door was translated, and the severity above
         * did not.** That is the same split `StatusLabels` makes for a status badge and
         * it is worth stating once more here, because the two fields used to sit side by side
         * and look like one kind of thing: `severity` is a CSS hook the stylesheet selects
         * on and must stay the literal `ok`/`warn`/`bad` in every language,
         * the headline is copy. A constant cannot hold copy once there are two languages of it.
         */
        fun headline(msg: Msg): String = when (this) {
            ADMITTED -> msg.doorAdmitted()
            ALREADY_ADMITTED -> msg.doorAlreadyAdmitted()
            WRONG_SHOWING -> msg.doorWrongShowing()
            NOT_ADMISSIBLE -> msg.doorNotAdmissible()
            UNKNOWN_TOKEN -> msg.doorUnknownToken()
        }
    }

    /**
     * `@JvmRecord`, so the JVM sees the same record the Java version declared: callers that are
     * still Java (the tests) keep calling `result.outcome()`, and Qute keeps
     * resolving `result.headline` against a record accessor.
     *
     * @property seat     the seat to let them sit in, when there is one to name — null for
     *                    [Outcome.UNKNOWN_TOKEN], where there is no row at all.
     * @property showing  the showing the ticket is actually for; the interesting field precisely
     *                    when it is not the one being scanned at.
     * @property headline what the usher reads first, already resolved for this request's language.
     *                    A field rather than `outcome.headline()` called from the template:
     *                    Qute expressions resolve against plain accessors, and a template cannot
     *                    hand the bundle in.
     * @property message  what to put in front of the usher, in their language of the door rather
     *                    than of the schema — and, since the door was translated, in their language
     *                    full stop.
     */
    @JvmRecord
    data class ScanResult(
        val outcome: Outcome,
        val reservation: Reservation?,
        val seat: ResourceUnit?,
        val showing: ResourcePoolSession?,
        val headline: String,
        val message: String,
    ) {
        fun isAdmitted(): Boolean = outcome == Outcome.ADMITTED

        /** Asked by the panel, which shows *when* the ticket was used only for this outcome.
         * A named accessor rather than an enum-to-string comparison in the markup: Qute
         * expressions are validated at build time and resolve best against plain accessors,
         * which is the same rule `ReservationRepository#mapSeatMapEntry` follows when it
         * lowercases a status in Java rather than in the template. */
        fun isAlreadyAdmitted(): Boolean = outcome == Outcome.ALREADY_ADMITTED
    }

    /**
     * Scans one ticket at the door of `sessionId`.
     *
     * `rawToken` arrives from a text input — a barcode scanner types into it like a
     * keyboard, and a human types into it when a phone screen is too cracked or too dark to
     * read. Both are normalised the same way, which is why manual entry is a supported fallback
     * rather than a happy accident: it is the only path that works when the customer's battery
     * is flat, and the admission schema sizes the token so it is short enough to read aloud.
     */
    fun scan(sessionId: UUID, rawToken: String?): ScanResult {
        // Resolved once, outside the transaction lambda: the language is a property of the
        // request, not of anything the transaction reads, and looking it up per branch would
        // make the lambda's capture depend on which refusal fired.
        val msg = language.messages()
        val token = normalise(rawToken)
        if (token.isEmpty()) {
            return result(msg, Outcome.UNKNOWN_TOKEN, null, null, null, msg.doorNoCodeScanned())
        }
        val tenantId = currentTenant.id()
        val scannedBy: UUID? = currentActor.appUserId().orElse(null)

        val scanResult = db.inTenantTransaction(tenantId) { c ->
            val reservation = reservationRepo.findByAdmissionToken(c, tenantId, token).orElse(null)
                ?: return@inTenantTransaction result(
                    msg, Outcome.UNKNOWN_TOKEN, null, null, null, msg.doorNotThisCinema())

            // Checked before the UPDATE rather than folded into its WHERE clause, deliberately: a
            // ticket for another showing is a fact about the ticket, it cannot change underneath
            // this read, and folding it in would make a wrong-showing scan indistinguishable from
            // an already-used one — the two refusals an usher most needs to tell apart.
            val ticketShowing: UUID? = reservation.resourcePoolSessionId
            if (sessionId != ticketShowing) {
                // Null is reachable, not defensive padding: resource_pool_session_id is nullable
                // for the verticals this schema stays neutral about, which have no discrete
                // occasion to scan at. Such a booking simply has no door, and refusing it here is
                // the right answer rather than a lookup on a null id.
                val itsShowing = if (ticketShowing == null) null
                else sessionRepo.findById(c, tenantId, ticketShowing).orElse(null)
                return@inTenantTransaction result(
                    msg, Outcome.WRONG_SHOWING, reservation, seatOf(c, tenantId, reservation),
                    itsShowing, wrongShowingMessage(msg, itsShowing))
            }

            // A SCAN DOES NOT COMPLETE THE RESERVATION, and that is a decision rather than an
            // omission. The status stays 'confirmed' and only admitted_at moves.
            //
            // 'completed' is outside the EXCLUDE constraint's predicate — WHERE status IN
            // ('held','confirmed') — in exactly the way 'admitted' would have been, so writing it
            // here would free the seat at the moment its holder sat down in it. The sweep can
            // write it safely only because it waits for the period to END, after which no new
            // booking can overlap the range anyway (ReservationLifecycleService). The door is the
            // one place in the system where the showing is, by definition, not over.
            //
            // What the door was really blocked on was the audit lie underneath that transition, and
            // that is now fixed: an audit row is attributed to whoever is acting rather than to
            // the row's creator, so the sweep's completion is logged as 'system' and this scan as
            // 'tenant_staff'. admitted_by_app_user_id stays regardless — it answers "who let this
            // person in" on the row itself, which no audit query should have to reconstruct.
            val admitted = reservationRepo.admit(c, tenantId, token, scannedBy).orElse(null)
            if (admitted != null) {
                return@inTenantTransaction result(
                    msg, Outcome.ADMITTED, admitted, seatOf(c, tenantId, admitted), null,
                    msg.doorAdmittedMessage())
            }

            // Nothing moved, so re-read to find out why. This is the only read that has to happen
            // *after* the UPDATE: the reason may have been created by the transaction that beat
            // us to the row a moment ago, and the pre-read above would report the state before it.
            val now = reservationRepo.findByAdmissionToken(c, tenantId, token).orElse(reservation)
            val seat = seatOf(c, tenantId, now)
            if (now.isAdmitted()) {
                return@inTenantTransaction result(
                    msg, Outcome.ALREADY_ADMITTED, now, seat, null, msg.doorUsedAlready())
            }
            result(msg, Outcome.NOT_ADMISSIBLE, now, seat, null, notAdmissibleMessage(msg, now))
        }

        // Logged at the door because this is the one action in the system with no screen of its
        // own to look back at: an usher who has waved someone through cannot re-scan to see what
        // happened, and a disputed entry is settled from the record or not at all. The token is
        // logged in full on purpose — it is single-use, tied to one seat at one screening, and
        // useless once admitted_at is set, so a log line is not a credential leak the way a
        // password would be. Who scanned it is on the row (admitted_by_app_user_id).
        LOG.infof("Admission scan at showing %s: %s (token %s)", sessionId, scanResult.outcome, token)
        return scanResult
    }

    /** Uppercased and trimmed, because a barcode scanner and a person do not type the same
     * thing. The admission schema mints uppercase hex, so normalising here means a hand-typed lowercase code
     * finds its row instead of reading as an unknown ticket — and the lookup stays an exact
     * match, rather than an `ILIKE` that could not use the unique index.
     *
     * `trim { it <= ' ' }` rather than Kotlin's `trim()`, deliberately: this is Java's
     * `String.trim()` rule (strip every code point up to U+0020), which the Java version used.
     * Kotlin's `trim()` strips Unicode whitespace instead — it would start removing a
     * non-breaking space and stop removing control characters, quietly changing which scanned
     * strings match a token. */
    private fun normalise(rawToken: String?): String =
        rawToken?.trim { it <= ' ' }?.uppercase(Locale.ROOT) ?: ""

    private fun seatOf(c: Connection, tenantId: UUID, reservation: Reservation): ResourceUnit? =
        unitRepo.findById(c, tenantId, reservation.resourceUnitId).orElse(null)

    /** Assembles the ScanResult with its headline already resolved, so no call site can add an
     * outcome and forget the copy that goes with it. */
    private fun result(
        msg: Msg, outcome: Outcome, reservation: Reservation?,
        seat: ResourceUnit?, showing: ResourcePoolSession?, message: String,
    ): ScanResult = ScanResult(outcome, reservation, seat, showing, outcome.headline(msg), message)

    /** The showing's own label is tenant data and is interpolated, not translated — a film title
     * is the same string in both languages. Only the sentence around it comes from the bundle. */
    private fun wrongShowingMessage(msg: Msg, itsShowing: ResourcePoolSession?): String =
        if (itsShowing == null) msg.doorDifferentShowing()
        else msg.doorDifferentShowingNamed(itsShowing.label)

    /**
     * Why this ticket does not open the door, in the usher's terms.
     *
     * Each status sends the customer somewhere different, which is why they do not collapse
     * into one message: an unpaid hold goes to the counter to pay, a cancelled booking goes to
     * the counter to argue, and a finished screening goes home.
     */
    private fun notAdmissibleMessage(msg: Msg, reservation: Reservation): String =
        when (reservation.status) {
            "held" -> msg.doorNotPaid()
            "cancelled" -> msg.doorCancelled()
            "expired" -> msg.doorExpired()
            "completed" -> msg.doorFinished()
            // Every value of the status CHECK constraint is named above, so this is unreachable
            // until someone adds a sixth. Saying so beats a confident wrong answer at a door.
            // The raw status is interpolated rather than translated for the same reason it is
            // shown at all: the untranslated word is the prompt to add the case.
            else -> msg.doorUnknownStatus(reservation.status)
        }

    companion object {
        private val LOG: Logger = Logger.getLogger(AdmissionService::class.java)
    }
}
