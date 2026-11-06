package com.protek.booking.seo

import com.protek.booking.i18n.Language
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * The absolute origin this deployment's public pages are published under.
 *
 * <p><b>Config, never the {@code Host} header — and this is the same rule as tenancy's, applied
 * one step further out.</b> {@code CurrentTenant} refuses a caller-supplied hostname because
 * it would decide <em>whose data</em> is returned. Nothing here decides that; these URLs go
 * into a {@code <link rel="canonical">}, an {@code og:url} and a sitemap. But a canonical
 * built from the {@code Host} header is a crawler being told, by us, that the authoritative
 * copy of this page lives on whatever hostname a request happened to arrive with — which is
 * a stranger's hostname as easily as ours. {@code CanonicalAuthorityFilter} narrows which
 * authorities reach the booking app at all, and it is explicit that it is routing hygiene
 * rather than a guarantee. So the origin comes from the same place the sign-in link's does.
 *
 * <p><b>Reuses {@code booking.public.https-base-url}</b> rather than introducing a second
 * property. It is already documented as "must include the port, must match the origin of the
 * redirect URI registered with Google", it is already set wherever Google sign-in works
 * ({@code PUBLIC_HTTPS_BASE_URL=https://cinema.example:8443}), and a deployment with two
 * properties naming the same origin would eventually have them disagree. Its documented
 * purpose in application.properties has been widened to say so.
 *
 * <p><b>Unset is a real state, not an error.</b> Local development and the whole test suite
 * run with it empty. Every method here returns {@link Optional}, and the callers degrade:
 * pages emit no canonical and no {@code og:url}, and the sitemap refuses to serve rather than
 * publish a list of relative URLs no crawler can use.
 */
@ApplicationScoped
class PublicUrls {

    /* Optional<String>, not String — see SignInLink's comment. The property is declared as
     * ${PUBLIC_HTTPS_BASE_URL:}, so when the env var is unset the property IS defined and
     * empty, SmallRye converts empty to null, and a plain String injection point fails the
     * whole deployment at startup. */
    @ConfigProperty(name = "booking.public.https-base-url")
    lateinit var httpsBaseUrl: Optional<String>

    /** The configured origin with any trailing slash removed, or empty when unconfigured. */
    fun origin(): Optional<String> {
        // Java's strip(), which is neither Kotlin's trim() nor javaTrim().
        var base = httpsBaseUrl.orElse("").trim { Character.isWhitespace(it) }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length - 1)
        }
        return if (base.isEmpty()) Optional.empty() else Optional.of(base)
    }

    fun isConfigured(): Boolean = origin().isPresent

    /**
     * {@code path} made absolute against the configured origin.
     *
     * @param path a rooted path such as {@code /movies/demo-film}. Passed through as given —
     *        callers build it from {@code Slug} output and uuids, both of which are already
     *        URL-safe, and the one caller with free text ({@code /cities/{city}}) encodes it
     *        with the same {@code urlEncoded} extension the template links use, so the
     *        sitemap and the anchor cannot disagree about what the URL is.
     */
    fun absolute(path: String?): Optional<String> = origin().map { base -> base + path }

    /**
     * The full set of {@code hreflang} links for one page, given its <em>unprefixed</em> path —
     * every language's URL for it, plus {@code x-default}.
     *
     * <p><b>Every language including the current one.</b> An {@code hreflang} set has to be
     * self-referential or search engines discard it: the English page must list itself as the
     * English alternate, not only point at the Indonesian one. That is the mistake this method
     * exists to make impossible, since neither caller gets to pick which languages go in.
     *
     * <p>{@code x-default} names the unprefixed URL — the page a visitor gets when nothing is
     * known about them, which is exactly what {@link Language#DEFAULT} means here.
     *
     * <p>Empty when the origin is unconfigured, like everything else in this class: an
     * {@code hreflang} must be absolute, and local development and the test suite have no
     * absolute URL to give it.
     */
    fun alternates(unprefixedPath: String?): List<PageMeta.Alternate> =
        origin().map { base ->
            val links = ArrayList<PageMeta.Alternate>(Language.all().size + 1)
            for (language in Language.all()) {
                links.add(PageMeta.Alternate(language.tag(), base + language.urlFor(unprefixedPath)))
            }
            links.add(PageMeta.Alternate("x-default", base + Language.DEFAULT.urlFor(unprefixedPath)))
            java.util.List.copyOf(links)
        }.orElseGet { java.util.List.of() }
}
