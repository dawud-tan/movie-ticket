package com.protek.booking.i18n

import com.protek.booking.web.SafePath
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DefaultValue
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.NewCookie
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.Duration

/**
 * Choosing a language — the switch at the top of every page.
 *
 * <p><b>A POST, for the same reason {@code CityResource} is one:</b> writing a cookie on a GET
 * would make the same URL render differently depending on the order pages were visited, and
 * would let a link somebody was sent change what their next twelve pages show. It is also what
 * keeps {@link LanguagePreference} a pure read, so a crawler sees the language the URL names
 * rather than a per-crawl value.
 *
 * <p><b>It does two things, and both are needed.</b> It writes the cookie, which is what carries
 * the choice onto the URLs that have no prefix — the OIDC round trip, a {@code seeOther} built
 * in Java, a link pasted into WhatsApp last month. And it redirects to the prefixed spelling of
 * the page the visitor was on, which is what puts them on a URL that says what language it is
 * in, so the next link they follow stays there and so the page they might share carries its
 * language with it.
 *
 * <p><b>One button, not a select plus a Go,</b> because there are two languages and the choice
 * is therefore a toggle. {@code lang} is read as a value rather than assumed, so the day there
 * is a third language the header's button becomes a select and nothing here changes.
 */
@Path("/language")
@RunOnVirtualThread
class LanguageResource {

    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun choose(@FormParam("lang") lang: String?,
               @FormParam("next") @DefaultValue("/") next: String?): Response {
        // An unknown tag falls back rather than 400ing: the field is a control this server
        // rendered, so a value that is not a language did not come from the page, and the
        // honest answer to that is the default language rather than an error page.
        val target = Language.ofTag(lang).orElse(Language.DEFAULT)
        // Confined first, THEN re-prefixed — in that order. Confining after would mean
        // Language.split had already been handed //evil.example to take a prefix off.
        val here = SafePath.next(next)
        return Response.seeOther(URI.create(retarget(here, target)))
                .cookie(cookieFor(target))
                .build()
    }

    private companion object {

        /** A year, matching {@code CityResource}'s. A display preference is worth remembering for
         * as long as somebody keeps going to the cinema, and it holds nothing that expiring sooner
         * would protect. */
        private val REMEMBER_FOR: Duration = Duration.ofDays(365)

        /** {@code here} moved from whatever language it was in to {@code target}, query intact. */
        fun retarget(here: String, target: Language): String {
            val queryAt = here.indexOf('?')
            val path = if (queryAt < 0) here else here.substring(0, queryAt)
            val query = if (queryAt < 0) "" else here.substring(queryAt)
            return target.urlFor(Language.split(path).path) + query
        }

        fun cookieFor(language: Language): NewCookie =
            NewCookie.Builder(LanguagePreference.COOKIE)
                .value(language.tag())
                .path("/")
                .maxAge(REMEMBER_FOR.toSeconds().toInt())
                // Lax rather than Strict, and not httpOnly: both for the reasons
                // CityResource.cookieFor spells out. The worst a forged POST here achieves is
                // showing somebody a page in the other language, which the same button undoes.
                .sameSite(NewCookie.SameSite.LAX)
                .secure(false)
                .build()
    }
}
