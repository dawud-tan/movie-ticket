package com.protek.booking.web

import com.protek.booking.admission.Code128
import com.protek.booking.domain.Reservation
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.web.customer.ChatMarkup
import io.quarkus.arc.Arc
import io.quarkus.qute.RawString
import io.quarkus.qute.TemplateExtension
import java.io.IOException
import java.io.UncheckedIOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Qute value-resolver extensions so templates can format domain values (dates, money,
 * countdowns) without bespoke per-page view DTOs. Resolved and generated at build time —
 * no reflection needed for native-image.
 *
 * <p>An {@code object} whose extension methods are {@code @JvmStatic}, because Qute only
 * treats a class's <em>static</em> methods as extensions. A
 * named object's {@code @JvmStatic} member compiles to a static method and nothing else. They
 * are public where the Java was package-private: Kotlin has no package-private, and
 * {@code internal} would mangle the JVM name Qute's generated resolvers call. The private
 * helpers are {@code @JvmStatic} too, so that every method on the class is static, as it was. */
@TemplateExtension
object TemplateExtensions {

    private val JAKARTA: ZoneId = ZoneId.of("Asia/Jakarta")

    /**
         * The locale when there is no reader to ask — still pinned, for the reason it always was.
         *
         * <p>It was never pinned to keep dates English. It was pinned so the format could not come
         * from the server's {@code LANG}: the same build rendered "Mon 21 Apr" on a server (C
         * locale) and "Sen 21 Apr" on a developer machine set to Indonesian, with nothing in the
         * code saying so. An ambient environment variable deciding what a ticket says is the defect;
         * a reader choosing their own language is not the same thing, and the two were collapsed
         * only because there was one language.
         */
    private val PINNED: Locale = Locale.ENGLISH

    /**
         * The request's locale, for the dates a person reads on screen.
         *
         * <p>Looked up through Arc rather than injected because a {@code @TemplateExtension} method
         * has to be static. {@code LanguagePreference} is {@code @ApplicationScoped} and answers
         * {@link com.protek.booking.i18n.Language#DEFAULT} when there is no request, so this is safe
         * off a request thread and during a build-time render — the same degradation every other
         * caller of it already relies on.
         *
         * <p>Falls back to {@link #PINNED} if Arc is not running at all, which is a unit test
         * calling one of these methods directly rather than anything that can happen in the server.
         */
    @JvmStatic
    private fun readerLocale(): Locale {
        val container = Arc.container()
        if (container == null || !container.isRunning) {
            return PINNED
        }
        val handle = container.instance(LanguagePreference::class.java)
        return if (handle.isAvailable) handle.get().locale() else PINNED
    }

    /**
         * Formatters are built per call rather than held as constants, and the cost is the reason
         * this is stated rather than assumed: {@code DateTimeFormatter.ofPattern} parses a short
         * pattern and allocates, which is nothing beside the database round trip every page that
         * calls these already made. Caching them per locale would be two entries in a map and a
         * second thing to reason about; measured against the seat-map fragment's own render
         * time, it would not be visible.
         */
    @JvmStatic
    private fun fmt(pattern: String, locale: Locale, zoned: Boolean): DateTimeFormatter {
        val formatter = DateTimeFormatter.ofPattern(pattern, locale)
        return if (zoned) formatter.withZone(JAKARTA) else formatter
    }

    private const val DT_PATTERN = "EEE d MMM yyyy, HH:mm"
    private const val D_PATTERN = "EEE d MMM yyyy"
    private const val T_PATTERN = "HH:mm"

    /**
         * On-screen date and time, in the reader's language.
         *
         * <p>The weekday is the half that actually moves — Indonesian abbreviates Monday to "Sen"
         * and Tuesday to "Sel", where the month names differ for only four of twelve (Mei, Agu,
         * Okt, Des). A seat map that is Indonesian down to the seat legend and then says
         * "Tue 21 Apr" is the same kind of unfinished as an English error banner on it.
         */
    @JvmStatic
    fun fmtDateTime(instant: Instant?): String =
        if (instant == null) "" else fmt(DT_PATTERN, readerLocale(), true).format(instant) + " WIB"

    /**
         * A calendar day, with its weekday spelled out — for the public-holiday list.
         *
         * <p>The weekday is the point rather than decoration: pricing bands are Mon-Wed / Thu-Fri
         * / Sat-Sun, and the holiday band also covers the day <em>before</em> each of these dates.
         * Seeing "Fri" against a date is how staff check that the eve they meant to price is the
         * eve they entered.
         *
         * <p>No timezone and no " WIB" suffix, unlike {@link #fmtDateTime}: a holiday is a
         * calendar date rather than an instant, so there is nothing here to convert.
         */
    @JvmStatic
    fun fmtDate(date: LocalDate?): String =
        if (date == null) "" else fmt(D_PATTERN, readerLocale(), false).format(date)

    /**
         * The clock time alone — the showtime chips, where the date is already the heading
         * above them and repeating it on every button would be forty words of noise.
         *
         * <p>Pinned to Asia/Jakarta and suffixed " WIB" for the same reason {@link #fmtDateTime}
         * is: the branch's own timezone lives on {@code site.timezone} and the booking page reads
         * it there, but a Qute extension receives an {@code Instant} and nothing else. Every branch
         * on this deployment is WIB; a chain that opens in Makassar needs the zone threaded in,
         * which is a view-model change rather than a formatter change.
         */
    @JvmStatic
    fun fmtTime(instant: Instant?): String =
        if (instant == null) "" else fmt(T_PATTERN, readerLocale(), true).format(instant)

    /**
         * The calendar day of an {@code Instant} — the booking page's date line, where the showtime
         * cannot be changed and the two fields are stated rather than offered.
         *
         * <p>Distinct from {@link #fmtDate}, which takes a {@link LocalDate} and needs no zone
         * because a holiday is not an instant. This one converts, and pins the zone for the reason
         * {@link #fmtTime} gives.
         */
    @JvmStatic
    fun fmtDay(instant: Instant?): String =
        if (instant == null) "" else fmt(D_PATTERN, readerLocale(), true).format(instant)

    /** customer/chatTurn.html: the assistant's answer, escaped and then lightly formatted. */
    @JvmStatic
    fun chatMarkup(text: String): RawString = RawString(ChatMarkup.toHtml(text))

    /** `{#if rows.anyAccessible}`: the seat map's legend names the wheelchair mark only where a seat has it. */
    @JvmStatic
    fun anyAccessible(rows: List<com.protek.booking.web.dto.SeatRow>): Boolean =
        rows.any { row -> row.seats.orEmpty().any { it.accessible } }

    @JvmStatic
    fun fmtMoney(amount: BigDecimal?): String {
        if (amount == null) {
            return ""
        }
        val whole = amount.setScale(0, RoundingMode.HALF_UP).toPlainString()
        val grouped = StringBuilder()
        var count = 0
        for (i in whole.length - 1 downTo 0) {
            grouped.append(whole[i])
            count++
            if (count % 3 == 0 && i > 0) {
                grouped.append('.')
            }
        }
        return "Rp " + grouped.reverse()
    }

    @JvmStatic
    fun secondsUntil(instant: Instant?): Long =
        if (instant == null) 0 else Math.max(0, Duration.between(Instant.now(), instant).seconds)

    /**
         * Encodes a value for use as a URL <em>path segment</em> — the city browse path
         * {@code /cities/{city}}, where real names contain spaces
         * ("Jakarta Pusat", "Banjar Baru").
         *
         * <p>{@code URLEncoder} alone is wrong here: it targets form encoding, where a space
         * becomes {@code +}. In a path segment {@code +} is a literal plus, so "Jakarta Pusat"
         * would round-trip as "Jakarta+Pusat" and match no city.
         */
    /**
         * The admission barcode for one seat, as inline SVG.
         *
         * <p>Inline rather than an {@code <img>} pointing at an endpoint: a ticket page renders one
         * of these per seat, so a separate request each would turn one page load into several on
         * cinema wifi, and each would need its own authorization check over the same data the page
         * has already proven the caller may see. Inline also survives the thing a ticket most has to
         * survive — being printed at the counter, or saved as a page for a customer with no signal
         * at the door.
         *
         * <p>Returns empty rather than throwing for a missing token, because a template exception
         * here would take down the whole reservation page — including the seat list and the payment
         * card, which are what the customer needs most if the barcode is the thing that is broken.
         * The admission schema makes the column NOT NULL, so this is unreachable short of a schema change.
         */
    @JvmStatic
    fun admissionBarcode(reservation: Reservation?): String {
        if (reservation?.admissionToken == null) {
            return ""
        }
        return Code128.toSvg(reservation.admissionToken)
    }

    /* public, not package-private, because SitemapResource calls it too: the sitemap's <loc>
     * for a city and the anchor that links to that city have to be byte-identical, and two
     * spellings of one URL is what a canonical exists to resolve rather than to create. */
    @JvmStatic
    fun urlEncoded(value: String?): String =
        if (value == null) "" else URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

    private val ASSET_VERSIONS: MutableMap<String, String> = ConcurrentHashMap()

    /**
         * Cache-busting suffix for a static file under {@code META-INF/resources}, e.g.
         * {@code <script src="/js/htmx.min.js{asset:v('js/htmx.min.js')}">} renders
         * {@code src="/js/htmx.min.js?v=3a1f9c02"}.
         *
         * <p>Hashed from the file's own bytes, not a build timestamp or a random value per build:
         * a deploy that did not touch this file must not force every open tab to refetch it, and a
         * value derived from content is the only kind that is automatically correct with no bump
         * step for anyone to forget. Computed once per path and cached for the process lifetime —
         * these are packaged resources, so nothing can change them without a restart, which is the
         * only event that could invalidate this cache anyway.
         *
         * <p>A path with no matching resource fails loudly (500) on first render rather than
         * silently serving an un-versioned link: every caller here is a hardcoded literal the
         * developer controls, so a miss is a typo, not a state to degrade gracefully for.
         */
    @TemplateExtension(namespace = "asset")
    @JvmStatic
    fun v(path: String): String = ASSET_VERSIONS.computeIfAbsent(path) { hashResource(it) }

    @JvmStatic
    private fun hashResource(path: String): String {
        val resourcePath = "META-INF/resources/$path"
        try {
            (Thread.currentThread().contextClassLoader.getResourceAsStream(resourcePath)
                ?: throw IllegalStateException("Static asset not found on classpath: $resourcePath")).use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(input.readAllBytes())
                return "?v=" + HexFormat.of().formatHex(hash, 0, 4)
            }
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        } catch (e: NoSuchAlgorithmException) {
            // SHA-256 is a JDK-mandatory algorithm (JLS/JCA spec) — unreachable in practice.
            throw IllegalStateException(e)
        }
    }
}
