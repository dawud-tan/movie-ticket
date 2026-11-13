package com.protek.booking.i18n

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.inject.Named

/**
 * The word a customer reads on a status badge, for a status the database spells in English.
 *
 * <p><b>Why this exists at all.</b> Every badge in {@code customer/} is two uses of one column
 * value: {@code <span class="badge {status}">{status}</span>}. The first is a CSS hook and must
 * stay the literal {@code held}/{@code confirmed}/{@code pending} the schema writes — the
 * stylesheet selects on it, and translating it would silently un-style every badge on the page.
 * The second is copy. Splitting them is the whole job here, and it is why a template says
 * {@code class="badge {line.reservation.status}"} beside {@code {inject:status.of(…)}} rather
 * than the same expression twice.
 *
 * <p><b>Covers four CHECK constraints and one value that is in none of them.</b> V1 fixes the
 * vocabularies for {@code resource_pool_session} ({@code scheduled}, {@code on_sale},
 * {@code closed}, {@code cancelled}), {@code reservation_group} ({@code pending},
 * {@code confirmed}, {@code failed}, {@code cancelled}), {@code reservation} ({@code held},
 * {@code confirmed}, {@code cancelled}, {@code expired}, {@code completed}) and {@code payment}
 * ({@code pending}, {@code paid}, {@code expired}, {@code failed}, {@code refunded}). The
 * outlier is {@code available}, which is not a column at all: the seat map computes it per seat,
 * and the legend names it beside the other two.
 *
 * <p><b>An unknown value is returned as it stands, not rejected.</b> The constraints above make
 * that unreachable today, and the failure direction is the reason it is handled anyway: a status
 * added by a later migration should show up as a slightly wrong word on a badge, not as a
 * rendering exception on a customer's order page. It reads as untranslated, which is exactly
 * what it is, and is the visible prompt to add the key.
 *
 * <p><b>{@code @ApplicationScoped} and stateless</b>, like {@link LanguagePreference}, whose
 * {@code messages()} it defers to for the current request's language — so the label follows the
 * page the same way every other {@code {msg:…}} does, including on a template rendered outside
 * a request.
 */
@Named("status")
@ApplicationScoped
class StatusLabels {

    @Inject
    lateinit var language: LanguagePreference

    /**
     * The label for one status value.
     *
     * @param status a value from one of the CHECK lists above, or {@code null} — which a
     *        template can produce for a payment that does not exist yet, and which renders as
     *        nothing rather than as "null".
     */
    fun of(status: String?): String = of(status, language.messages())

    /** The same label in a language chosen elsewhere: an `/api/app/live` socket's, read from its
     * handshake, since the frames it sends leave long after that request ended. */
    fun of(status: String?, msg: Msg): String {
        if (status == null) {
            return ""
        }
        // null is answered above, so `when` sending it to else cannot arise.
        return when (status) {
            "available" -> msg.statusAvailable()
            "held" -> msg.statusHeld()
            "confirmed" -> msg.statusConfirmed()
            "pending" -> msg.statusPending()
            "cancelled" -> msg.statusCancelled()
            "failed" -> msg.statusFailed()
            "expired" -> msg.statusExpired()
            "completed" -> msg.statusCompleted()
            "paid" -> msg.statusPaid()
            "refunded" -> msg.statusRefunded()
            "scheduled" -> msg.statusScheduled()
            "on_sale" -> msg.statusOnSale()
            "closed" -> msg.statusClosed()
            else -> status
        }
    }
}
