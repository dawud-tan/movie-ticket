package com.protek.booking.seo

/**
 * The head markup one public page contributes: canonical URL, Open Graph, and its JSON-LD
 * block. Rendered by {@code base.html}'s {@code {#insert head}} slot.
 *
 * <p><b>Why the canonical tag is the load-bearing one here, rather than a nicety.</b> This
 * branch serves HTTP on 8080 and HTTPS on 8443 at once, with no redirect and no HSTS between
 * them — a deliberate requirement, not an oversight. So every public page has always had at
 * least two working URLs, and the slug column gave the branch and film pages a third and fourth by keeping
 * the uuid form alive beside the slug. Four URLs, one page, and nothing telling a crawler
 * which one is the page. A canonical is exactly the mechanism for that, and it is what lets
 * the uuid URLs keep working instead of having to be redirected away.
 *
 * <p><b>Why Open Graph is here at all,</b> given it is not a search-engine feature: the way a
 * showtime link actually travels for this market is pasted into a WhatsApp group. Without
 * these four tags that paste renders as a bare URL; with them it renders as the poster, the
 * title and the synopsis. That is worth more per link than anything else in this file.
 *
 * <p><b>Why {@code alternates} is per page and not computed for every page.</b> An
 * {@code hreflang} is a claim that the other URL is this page in another language. During a
 * page-by-page translation that claim is false for most pages, and a wrong {@code hreflang} is
 * worse than none — it invites a crawler to index an Indonesian URL that serves English copy.
 * So a page joins the set by being given its alternates, which happens when its template is
 * actually translated, and until then {@code /id/…} of that page self-canonicalises to the
 * unprefixed URL and stays out of the index.
 *
 * <p><b>Every customer-facing page has now joined it, and one page still has not — for a
 * different and permanent reason.</b> {@code /cinemas} canonicalises to {@code /cities/{city}}
 * rather than to itself, because the city comes from a cookie and the URL therefore renders a
 * different page per visitor. A self-referential set for it cannot exist, and a set naming the
 * page it points at would omit the page carrying it, which search engines discard wholesale. So
 * it carries a canonical and no {@code hreflang}, and {@code LocalizedMarkupTest} pins that —
 * it is what keeps the "given, not computed" mechanism under test now that no untranslated
 * customer page is left to demonstrate it.
 *
 * <p>Every field is nullable and the template omits what is null. With no configured public
 * origin — local development, the whole test suite — {@code canonicalUrl} and {@code url} are
 * null and the page simply carries no canonical, which is the correct thing to say when the
 * deployment does not know its own address. {@code alternates} degrades the same way: no
 * origin, no absolute URLs, no {@code hreflang}.
 *
 * <p>A {@code @JvmRecord}, so Java's
 * {@code meta.canonicalUrl()} and Qute's {@code meta.canonicalUrl} are untouched.
 */
@JvmRecord
data class PageMeta(
    /** Absolute, and always the slug form for a page that has one. */
    val canonicalUrl: String?,
    val title: String?,
    val description: String?,
    /** Absolute URL of the poster, or null. Whatever the operator typed into the film's
     * poster_url — this markup does not host images and does not validate the value. */
    val imageUrl: String?,
    /** {@code og:type}. "video.movie" for a film, "website" for everything else. */
    val type: String?,
    /** The finished, script-safe JSON-LD document — see {@link JsonLd}. Empty for a page
     * that has no structured data worth emitting, which is most of the browse path. */
    val jsonLd: String?,
    /** This page's other-language URLs, including its own — see the class javadoc. Empty
     * for a page that has not been given any: an untranslated one, or {@code /cinemas},
     * which cannot have a self-referential set. */
    val alternates: List<Alternate>?,
) {

    /** One {@code <link rel="alternate" hreflang>}: a BCP 47 tag (or {@code x-default}) and the
     * absolute URL of this page in that language. */
    @JvmRecord
    data class Alternate(val hreflang: String?, val href: String?)

    /** The six-field form every page used before translation existed, so that a page with no
     * alternates does not have to say so. */
    constructor(
        canonicalUrl: String?, title: String?, description: String?, imageUrl: String?,
        type: String?, jsonLd: String?,
    ) : this(canonicalUrl, title, description, imageUrl, type, jsonLd, java.util.List.of())

    /** The same page, told which URLs are it in the other languages. Separate from the
     * constructors so that translating a page is a one-line addition at its call site rather
     * than a signature change everywhere. */
    fun withAlternates(alternates: List<Alternate>?): PageMeta =
        PageMeta(canonicalUrl, title, description, imageUrl, type, jsonLd, alternates)

    companion object {
        /** A page with a canonical URL and nothing else to say — the list pages. */
        @JvmStatic
        fun page(canonicalUrl: String?, title: String?, description: String?): PageMeta =
            PageMeta(canonicalUrl, title, description, null, "website", null)
    }
}
