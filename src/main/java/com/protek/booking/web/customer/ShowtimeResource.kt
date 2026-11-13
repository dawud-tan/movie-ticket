package com.protek.booking.web.customer

import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.Site
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.isJavaBlank
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.seo.PageMeta
import com.protek.booking.seo.PublicUrls
import com.protek.booking.seo.SeoMarkup
import com.protek.booking.service.CatalogService
import com.protek.booking.web.TemplateExtensions
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.inject.Inject
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.util.UUID

/**
 * Customer-facing "browse showtimes for a venue", extended to the
 * city → branch → hall path a chain needs and then to the two menus the
 * market's mobile sites are built around: <b>Bioskop</b> ({@code /cinemas})
 * and <b>Film</b> ({@code /movies}).
 *
 * <p><b>The two menus are the same grid entered from opposite corners.</b> A visitor either
 * knows which cinema they will go to and wants to know what is on, or knows what they want to
 * see and wants to know where it is playing. Both end at a showtime, and both open the same
 * showtime strip from the same query ({@code CatalogService.listShowingsOfFilmAtSite}) — one
 * rule about what is on sale, in one place.
 *
 * <p><b>{@code /films} and {@code /sites} are gone from the customer's URL space</b> and 301
 * to the new names. A redirect rather than a second live path, because these two would have
 * diverged: {@code /cinemas/{ref}} lists the films playing at a branch, where {@code /sites/{ref}}
 * listed its studios, and a customer wants the first. The slug "both forms resolve" rule still
 * holds where it was aimed — a slug and a uuid both reach a branch — but that was about two
 * spellings of one page, not two pages.
 *
 * <p><b>The home page has two shapes, and that is deliberate.</b> A tenant with branches
 * configured gets the two-menu page above; a tenant with none keeps the original flat hall list.
 * Forcing a one-cinema operator through a city step to reach their single hall would be a
 * worse page, and every single-cinema tenant is exactly that case — so the branch hierarchy is
 * additive in the UI in the same way {@code resource_pool.site_id} is additive in the schema.
 *
 * <p><b>Every page here carries a {@link PageMeta}</b>: a canonical URL, Open
 * Graph tags, and on the two detail pages a JSON-LD document. These are the public pages, so
 * they are the only ones that get it — {@code /admin}, {@code /orders} and the seat map
 * are either behind a role or transactional, and {@code robots.txt} keeps crawlers off the
 * first two rather than this class doing it per page.
 *
 * <p><b>And every page here is bilingual, which shows up in this class as three things done the
 * same way each time.</b> The title and description come from {@code language.messages()} rather
 * than from a literal, because they are {@code og:} tags and a pasted link renders in whatever
 * language the page was; the canonical goes through {@code language.path} so that {@code /id/…}
 * canonicalises to {@code /id/…} and is not declared a duplicate of the English page; and the
 * {@code hreflang} set is added with {@code withAlternates} because the template behind it is
 * translated. The one deliberate exception is {@link #cinemas()} — it canonicalises elsewhere,
 * so it gets the first two and never the third. {@link PageMeta} has the argument.
 */
@Path("/")
@RunOnVirtualThread
class ShowtimeResource {

    @CheckedTemplate(basePath = "customer")
    object Templates {
        @JvmStatic
        external fun halls(halls: List<ResourcePool>?, meta: PageMeta?): TemplateInstance

        /** The two-menu home page. Takes no city list: the city step is the
         * select in base.html's header, not a listing on this page. */
        @JvmStatic
        external fun home(unassignedHalls: List<ResourcePool>?, meta: PageMeta?): TemplateInstance

        @JvmStatic
        external fun sites(city: String?, sites: List<Site>?, meta: PageMeta?): TemplateInstance

        @JvmStatic
        external fun cinemaDetail(site: Site?, films: List<Film>?, meta: PageMeta?): TemplateInstance

        @JvmStatic
        external fun showings(
            hall: ResourcePool?, showings: List<ResourcePoolSession>?, meta: PageMeta?,
        ): TemplateInstance

        @JvmStatic
        external fun movies(films: List<Film>?, meta: PageMeta?): TemplateInstance

        @JvmStatic
        external fun movieDetail(
            film: Film?, cinemas: List<Site>?,
            unassigned: List<ResourcePoolSessionRepository.FilmShowing>?,
            meta: PageMeta?,
        ): TemplateInstance

        /** The strip that opens under a title on a cinema page, and under a cinema name on a
         * film page. One template for both, because it is one question asked from two sides. */
        @JvmStatic
        external fun showtimesFragment(
            showings: List<ResourcePoolSessionRepository.FilmShowing>?,
        ): TemplateInstance
    }

    @Inject
    lateinit var catalogService: CatalogService

    @Inject
    lateinit var publicUrls: PublicUrls

    @Inject
    lateinit var seoMarkup: SeoMarkup

    @Inject
    lateinit var cityPreference: CityPreference

    @Inject
    lateinit var language: LanguagePreference

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun home(): TemplateInstance {
        // Both shapes of the home page canonicalise to this request's own language, because
        // both ARE "/" — in English at "/" and in Indonesian at "/id". Which SHAPE renders
        // depends on whether the tenant has configured a branch, not on the URL, and not on
        // the language.
        val msg = language.messages()
        val meta = PageMeta.page(absolute(language.path("/")), msg.homeTitle(),
            msg.homeDescription())
        // Still read, though nothing lists them here any more: an operator with no branch
        // configured has no city step to offer at all, and gets the flat hall list instead.
        val cities = catalogService.listCities()
        if (cities.isEmpty()) {
            // Both shapes carry the hreflang set now: halls.html is translated too, so "/id" of
            // this one is a genuine Indonesian rendering of "/" rather than an Indonesian header
            // over an English list. Before that was true it deliberately claimed no alternate —
            // the rule PageMeta's javadoc states, and the reason `alternates` is per page.
            return Templates.halls(catalogService.listHalls(),
                meta.withAlternates(publicUrls.alternates("/")))
        }
        // A hall with no branch still has to be reachable, or configuring the first branch
        // would make every other hall disappear from the public site.
        return Templates.home(catalogService.listHallsWithoutSite(),
            meta.withAlternates(publicUrls.alternates("/")))
    }

    @GET
    @Path("/cities/{city}")
    @Produces(MediaType.TEXT_HTML)
    fun sitesInCity(@PathParam("city") city: String?): TemplateInstance {
        // Encoded with the same extension the anchors use, so the canonical this page claims
        // and the link that reached it are byte-identical. They have to be: two spellings of
        // one URL is the thing a canonical exists to resolve, not to create.
        val path = "/cities/" + TemplateExtensions.urlEncoded(city)
        val msg = language.messages()
        // The canonical follows the language, like the home page's: /id/cities/X canonicalises
        // to itself, never back to /cities/X, or the Indonesian listing is declared a duplicate
        // and leaves the index — which is the whole thing the prefix exists to prevent.
        val meta = PageMeta.page(absolute(language.path(path)), msg.citiesTitle(city),
            msg.citiesDescription(city))
        return Templates.sites(city, catalogService.listSitesInCity(city),
            meta.withAlternates(publicUrls.alternates(path)))
    }

    // --- Cinema-first browse: the "Bioskop" menu ---------------------------

    /**
     * The branches in whichever city the visitor is browsing.
     *
     * <p><b>Canonicalises to {@code /cities/{city}}, not to itself.</b> This URL renders a
     * different page per visitor — the cookie decides which city — so claiming it as the
     * canonical would tell a crawler that one address is the authoritative home of forty
     * different city listings. {@code /cities/{city}} is the addressable form of what is
     * actually on the page, it is what the sitemap lists, and it is what a visitor can send
     * somebody. This page is the menu entry; that one is the page.
     */
    @GET
    @Path("/cinemas")
    @Produces(MediaType.TEXT_HTML)
    fun cinemas(): TemplateInstance {
        val city = cityPreference.selected()
        val msg = language.messages()
        // Language-aware even though the canonical points elsewhere: /id/cinemas must name the
        // Indonesian city page, not the English one, or the menu hands a crawler the wrong
        // language's address for what it is showing.
        val meta = PageMeta.page(
            absolute(language.path("/cities/" + TemplateExtensions.urlEncoded(city))),
            msg.citiesTitle(city), msg.citiesDescription(city))
        // AND NO ALTERNATES, deliberately — this is the one translated page in the tree that
        // still emits none. An hreflang set has to be self-referential, and the set for the page
        // this one canonicalises to does not contain /cinemas. Giving it one would publish a set
        // that search engines discard wholesale; giving it its OWN would claim a per-visitor URL
        // as an addressable page. The canonical above already says which URL is the page.
        return Templates.sites(city, catalogService.listSitesInCity(city), meta)
    }

    /**
     * A branch page: what is playing here.
     *
     * <p>Reached by slug or by the uuid that predates it. {@code String} rather than
     * {@code UUID}: both forms resolve, and the canonical this page emits is always the slug,
     * so a crawler that arrived either way indexes one URL.
     */
    @GET
    @Path("/cinemas/{siteRef}")
    @Produces(MediaType.TEXT_HTML)
    fun cinemaDetail(@PathParam("siteRef") siteRef: String?): TemplateInstance {
        val site = catalogService.getSiteByRefOrThrow(siteRef)
        val films = catalogService.listFilmsAtSite(site.siteId)
        val path = "/cinemas/" + site.slug
        val meta = PageMeta(
            absolute(language.path(path)),
            site.name,
            describeBranch(site),
            null,
            "website",
            seoMarkup.movieTheater(site, catalogService.listHallsAtSite(site.siteId)))
        // The alternates name the SLUG form even when this request arrived by uuid, exactly as
        // the canonical above does — an hreflang set belongs to the page the canonical names.
        return Templates.cinemaDetail(site, films,
            meta.withAlternates(publicUrls.alternates(path)))
    }

    /** Step 2b: the showtimes of one film at this branch, opened in place under its title. */
    @GET
    @Path("/cinemas/{siteRef}/films/{filmRef}")
    @Produces(MediaType.TEXT_HTML)
    fun showtimesAtCinema(
        @PathParam("siteRef") siteRef: String?,
        @PathParam("filmRef") filmRef: String?,
    ): TemplateInstance = showtimesFragment(siteRef, filmRef)

    // --- Film-first browse: the "Film" menu ----------------
    //
    // Alongside the venue-first path above, not replacing it. "Pick a title, then see where
    // and when it plays" is the market's primary structure and was unbuildable while a
    // showing's title was free text.

    @GET
    @Path("/movies")
    @Produces(MediaType.TEXT_HTML)
    fun movies(): TemplateInstance {
        val msg = language.messages()
        // og:title reuses the nav's key for movies.html's reason: the menu and the page it opens
        // are the same two words, and a second key is how they end up differing in one language.
        val meta = PageMeta.page(absolute(language.path("/movies")), msg.navNowPlaying(),
            msg.moviesDescription())
        return Templates.movies(catalogService.listNowPlaying(),
            meta.withAlternates(publicUrls.alternates("/movies")))
    }

    /** A film page: where it is playing. By slug or uuid — see
     * {@link #cinemaDetail}. */
    @GET
    @Path("/movies/{filmRef}")
    @Produces(MediaType.TEXT_HTML)
    fun movieDetail(@PathParam("filmRef") filmRef: String?): TemplateInstance {
        val film = catalogService.getFilmByRefOrThrow(filmRef)
        // Still read whole, because the JSON-LD below needs one ScreeningEvent per showing —
        // the page's own list is the branches, but the markup's is every screening.
        val showings = catalogService.listShowingsOfFilm(film.filmId)
        val path = "/movies/" + film.slug
        val meta = PageMeta(
            absolute(language.path(path)),
            film.title,
            summarise(film.synopsis),
            film.posterUrl,
            // The one page with a more specific og:type than "website". It is what makes a
            // pasted link render as a film rather than as a generic page.
            "video.movie",
            seoMarkup.movieWithScreenings(film, showings))
        // A hall with no branch (the site table keeps site_id nullable) has nothing to expand under, so it
        // keeps the direct seat-map link it has always had rather than dropping off the page.
        // Filtered from the rows already in hand rather than by a third query.
        // stream().toList() as the Java had it: unmodifiable.
        val unassigned = showings.stream()
            .filter { showing -> showing.siteId == null }.toList()
        return Templates.movieDetail(film, catalogService.listCinemasShowingFilm(film.filmId),
            unassigned, meta.withAlternates(publicUrls.alternates(path)))
    }

    /** Step 3b: the showtimes of this film at one branch. The same fragment
     * {@link #showtimesAtCinema} renders, reached from the other direction. */
    @GET
    @Path("/movies/{filmRef}/cinemas/{siteRef}")
    @Produces(MediaType.TEXT_HTML)
    fun showtimesOfMovie(
        @PathParam("filmRef") filmRef: String?,
        @PathParam("siteRef") siteRef: String?,
    ): TemplateInstance = showtimesFragment(siteRef, filmRef)

    // --- The names these two paths used to have ------------------------------------------
    //
    // 301 and not 302: these URLs are in the sitemap that has already been submitted, in
    // whatever a member of staff has bookmarked, and in links pasted into WhatsApp groups.
    // A permanent redirect is what moves a crawler's index and the accumulated signal onto
    // the new address; a temporary one asks it to keep both forever.
    //
    // Redirecting to the SLUG form rather than passing {siteRef} through: a uuid link now
    // costs one hop and lands on the canonical, instead of arriving at a second spelling of
    // an address that has only just been established.

    @GET
    @Path("/films")
    fun filmsMoved(): Response = movedTo("/movies")

    @GET
    @Path("/films/{filmRef}")
    fun filmMoved(@PathParam("filmRef") filmRef: String?): Response =
        movedTo("/movies/" + catalogService.getFilmByRefOrThrow(filmRef).slug)

    @GET
    @Path("/sites/{siteRef}")
    fun siteMoved(@PathParam("siteRef") siteRef: String?): Response =
        movedTo("/cinemas/" + catalogService.getSiteByRefOrThrow(siteRef).slug)

    /** Unchanged and still linked from every path above: the seat map and every existing
     * test reach a hall by this URL. Uuid-keyed on purpose — see the slug column in V1 for why "Studio 1" is
     * not worth a slug. */
    @GET
    @Path("/halls/{poolId}")
    @Produces(MediaType.TEXT_HTML)
    fun showings(@PathParam("poolId") poolId: UUID): TemplateInstance {
        val hall = catalogService.getHallOrThrow(poolId)
        val path = "/halls/$poolId"
        val meta = PageMeta.page(absolute(language.path(path)), hall.name,
            language.messages().hallDescription(hall.name))
        return Templates.showings(hall, catalogService.listUpcomingShowings(poolId),
            meta.withAlternates(publicUrls.alternates(path)))
    }

    /**
     * Resolves both refs before querying, so a mistyped branch or film is a 404 rather than an
     * empty strip. An empty strip means "nothing on today"; the two must not look alike.
     */
    private fun showtimesFragment(siteRef: String?, filmRef: String?): TemplateInstance {
        val site = catalogService.getSiteByRefOrThrow(siteRef)
        val film = catalogService.getFilmByRefOrThrow(filmRef)
        return Templates.showtimesFragment(
            catalogService.listShowingsOfFilmAtSite(film.filmId, site.siteId))
    }

    /** Null when no public origin is configured, which is what makes the page omit its
     * canonical rather than claim one on localhost. See {@link PublicUrls}. */
    private fun absolute(path: String?): String? = publicUrls.absolute(path).orElse(null)

    /**
     * A branch's {@code og:description}, in this request's language.
     *
     * <p>Two messages rather than one with an optional clause: the site table keeps {@code site.city}
     * nullable, and where the city sits in the sentence is the translator's business, not a
     * {@code StringBuilder}'s.
     */
    private fun describeBranch(site: Site): String {
        val msg = language.messages()
        val city = site.city
        return if (city == null || city.isJavaBlank()) {
            msg.cinemaDescription(site.name)
        } else {
            msg.cinemaDescriptionInCity(site.name, city)
        }
    }

    private companion object {
        fun movedTo(path: String): Response =
            Response.status(Response.Status.MOVED_PERMANENTLY).location(URI.create(path)).build()

        /**
         * A synopsis cut down to what a link preview actually shows.
         *
         * <p>Around 200 characters is where Facebook, WhatsApp and Google all stop; past that the
         * text is carried and discarded. Cut on a word boundary, because a preview ending
         * mid-word reads as a broken page rather than as a truncated one.
         */
        fun summarise(text: String?): String? {
            if (text == null || text.isJavaBlank()) {
                return null
            }
            // Java's strip() is trim { Character.isWhitespace(it) }; replaceAll is
            // the same java.util.regex pattern, and " " carries no `$` or `\`.
            val flat = text.trim { Character.isWhitespace(it) }.replace(WHITESPACE_RUN, " ")
            if (flat.length <= 200) {
                return flat
            }
            val cut = flat.substring(0, 200)
            val lastSpace = cut.lastIndexOf(' ')
            return (if (lastSpace > 0) cut.substring(0, lastSpace) else cut) + "…"
        }

        private val WHITESPACE_RUN = Regex("\\s+")
    }
}
