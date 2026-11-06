package com.protek.booking.i18n

import com.protek.booking.isJavaBlank
import io.quarkus.qute.i18n.Localized
import io.quarkus.qute.i18n.MessageBundles
import io.quarkus.vertx.http.runtime.CurrentVertxRequest
import io.vertx.ext.web.RoutingContext
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Named
import java.util.Locale
import java.util.Optional

/**
 * Which language this request is being answered in — the switch at the top of every page, and
 * the {@code /id} prefix in front of every public URL.
 *
 * <p><b>Precedence: path, then cookie, then {@code Accept-Language}, then English.</b> The
 * first of those is the one that matters and it is the one that inverts the obvious ordering,
 * so it is worth being explicit about why.
 *
 * <ol>
 * <li><b>The path wins whenever it says anything.</b> {@code /id/movies} is Indonesian for
 * everyone, including a crawler, including a visitor whose cookie says English. Without that
 * rule the same URL would answer in different languages depending on who asked, which is
 * exactly what makes a translated page invisible to a search engine — Googlebot holds no
 * cookie, so whatever the cookie decides is a version it can never see.</li>
 * <li><b>The cookie decides unprefixed URLs.</b> Not every entry point carries a prefix: an
 * OIDC round trip lands back on {@code /}, a {@code Response.seeOther} in Java names a path
 * without one, and a link pasted into WhatsApp last month has none. The cookie is what keeps
 * a visitor who chose Indonesian in Indonesian across those, and it is written by a POST only
 * — {@link LanguageResource}, same rule as {@code CityResource}: nothing on a GET writes it.</li>
 * <li><b>{@code Accept-Language} seeds the first visit</b> and nothing more. It is deliberately
 * the weakest input rather than the strongest: Chrome on Android in this market very commonly
 * sends {@code en-US} regardless of who is holding the phone, so a browser-only rule would
 * show English to a large share of exactly the audience this is for — and the header cannot
 * be corrected from the stand, where a button can.</li>
 * </ol>
 *
 * <p><b>Consequence worth knowing:</b> an unprefixed URL can render Indonesian for a
 * cookie-holder while claiming an English canonical. Only a cookie-holder ever sees that, and
 * only a crawler acts on a canonical, so the two never meet. The alternative — redirecting an
 * unprefixed GET to the prefixed URL — would make a shared link land somewhere other than
 * where it points, which Google's own guidance warns against.
 *
 * <p><b>{@code @ApplicationScoped}, not {@code @RequestScoped}</b>, and that is not a style
 * choice. {@link TemplateLocale} calls this from a Qute template-instance initializer, which
 * also runs for templates rendered off a request — background work, a test that renders
 * directly. A normal-scoped bean's proxy throws {@code ContextNotActiveException} there; this
 * one holds no state and reads the request through the same {@code Instance} guard
 * {@code CityPreference} and {@code StaffNav} use, so "no request" is an answer rather than a
 * failure.
 *
 * <p><b>Not a security input.</b> It decides which strings a page renders and nothing else. It
 * is caller-supplied — path, cookie and header all are — so the tenancy rule applies: it must never
 * reach tenancy, and it cannot, because nothing here touches {@code CurrentTenant}.
 */
@Named("lang")
@ApplicationScoped
class LanguagePreference {

    @Inject
    lateinit var request: Instance<CurrentVertxRequest>

    /** The language this request is answered in. Never null. */
    fun current(): Language {
        val routing = routing() ?: return Language.DEFAULT
        val split = Language.split(routing.request().path())
        if (split.wasPrefixed()) {
            return split.language!!
        }
        return cookieLanguage(routing)
                .orElseGet { Language.fromAcceptLanguage(
                        routing.request().getHeader("Accept-Language")) }
    }

    /** {@code <html lang>}, and the tag a {@code hreflang} names. */
    fun tag(): String = current().tag()

    /** The URL prefix for this request's language: {@code ""} or {@code "/id"}. */
    fun prefix(): String = current().prefix()

    /**
     * A rooted, unprefixed path rendered as a link for this request's language — what every
     * public {@code href} in a template goes through, so that following a link inside the
     * Indonesian site stays inside it.
     *
     * <p>Only the public paths do. {@code /orders}, {@code /admin} and the OIDC hops keep their
     * bare form on purpose: {@code robots.txt} disallows them by exactly those prefixes, and
     * {@code /id/orders} would be a second spelling that list does not cover. Those pages are
     * behind a role and never indexed, so the cookie is what keeps them in the chosen language.
     */
    fun path(rootedPath: String?): String = current().urlFor(rootedPath)

    /** The locale handed to Qute, which is how {@code {msg:…}} knows which file to read. */
    fun locale(): Locale = current().locale()

    /** The copy, for Java that has to produce a user-visible string — page titles and
     * {@code og:description}, which live in {@code PageMeta} rather than in the template. */
    fun messages(): Msg = messagesIn(current())

    // --- What the header's switch needs ---------------------------------------------------

    /** The language the switch offers, named in itself — see {@link Language}'s javadoc for
     * why that name is not a translated string. */
    fun otherEndonym(): String = current().other().endonym()

    /** The tag the switch posts, and the {@code lang}/{@code hreflang} of its own label. */
    fun otherTag(): String = current().other().tag()

    /** The switch's accessible name, in the language of the page being left. */
    fun switchLabel(): String = messages().languageSwitch(otherEndonym())

    /**
     * Where the visitor is, prefix and query intact, so the switch can bring them back to the
     * same page in the other language.
     *
     * <p>Path and query only — never the authority, and {@link LanguageResource} confines it
     * again on the way in, because by then it has been round-tripped through a form field and
     * is caller-supplied whatever this method produced. Same contract as
     * {@code CityPreference.currentPath()}.
     */
    fun currentPath(): String {
        val routing = routing() ?: return "/"
        val uri: String? = routing.request().uri()
        // isJavaBlank, not isBlank(): Kotlin's also counts U+00A0.
        return if (uri == null || uri.isJavaBlank() || uri[0] != '/') "/" else uri
    }

    /** This request's path with any language prefix removed — what a canonical and a set of
     * {@code hreflang} alternates are both built from. Query string dropped: neither of those
     * wants it. */
    fun unprefixedPath(): String {
        val routing = routing()
        return if (routing == null) "/" else Language.split(routing.request().path()).path
    }

    private fun cookieLanguage(routing: RoutingContext): Optional<Language> {
        val cookie = routing.request().getCookie(COOKIE)
        return if (cookie == null) Optional.empty() else Language.ofTag(cookie.value)
    }

    private fun routing(): RoutingContext? {
        try {
            if (!request.isResolvable) {
                return null
            }
            val current: CurrentVertxRequest? = request.get()
            return current?.current
        } catch (e: ContextNotActiveException) {
            return null
        }
    }

    companion object {

        /** Prefixed like the rest of this application's own state, so it is distinguishable from
         * any other application's cookies on the same origin — same reasoning as
         * {@code CityPreference.COOKIE}. */
        const val COOKIE = "booking_lang"

        /**
         * The bundle for one language.
         *
         * <p>The default language goes through the no-argument lookup rather than
         * {@code Localized.Literal.of("en")}: the default bundle is registered under Qute's
         * {@code <<default>>} key, and asking for it by tag is a "Bean not found for localized
         * interface" away from working.
         */
        @JvmStatic
        fun messagesIn(language: Language): Msg =
            if (language.isDefault())
                MessageBundles.get(Msg::class.java)
            else
                MessageBundles.get(Msg::class.java, Localized.Literal.of(language.tag()))
    }
}
