package com.protek.booking.web.customer

import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.isJavaBlank
import com.protek.booking.security.Roles
import com.protek.booking.service.CustomerProfileService
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.FormParam
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * {@code /profile} — the account page.
 *
 * <p><b>One writable field, and it is the only one the identity provider does not assert.</b>
 * Name and email came from the Google sign-in, so the place to change either is the Google
 * account: a form for them here would be a second, divergent copy of something the provider
 * re-asserts on every request, or an edit the next sign-in silently reverts. A date of birth is
 * the opposite case — V1's comment on customer.date_of_birth records that Google sends no birthdate for the scopes this application
 * requests, so nothing else will ever fill it and nothing will revert it. It feeds the age attestation's age
 * advisory on the seat map, and it is self-declared: it warns, and never permits.
 *
 * <p>{@code tenant_staff} is allowed alongside {@code customer} for the reason
 * {@code OrderResource} gives: a staff account is a real identity with a real email, and
 * showing them what they are signed in as is not a customer-only concern. What they see is
 * their own row and nobody else's — the service takes no parameter that could widen it.
 */
@Path("/profile")
@RunOnVirtualThread
@RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
class ProfileResource {

    @CheckedTemplate(basePath = "customer")
    object Templates {
        @JvmStatic
        external fun profile(profile: CustomerProfileService.Profile?, errorMessage: String?): TemplateInstance
    }

    @Inject
    lateinit var profileService: CustomerProfileService

    @Inject
    lateinit var language: LanguagePreference

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun profile(): TemplateInstance = Templates.profile(profileService.current(), null)

    /**
     * Records the date of birth, then redirects rather than rendering.
     *
     * <p>Post/redirect/get, unlike the seat map's hold — which answers in the same response
     * because htmx swaps the fragment it returns. This is an ordinary form on an ordinary page,
     * and rendering the result of the POST directly would leave a reload re-submitting it. The
     * failure path does render in place, because a redirect would have to carry the message in a
     * query string and a validation message is not something to put in a URL the customer can
     * bookmark or share.
     */
    @POST
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    fun updateDateOfBirth(@FormParam("dateOfBirth") dateOfBirth: String?): Response {
        val parsed: LocalDate? = try {
            // Empty is a clear, not a mistake: the field is optional and a customer who submits
            // it blank is withdrawing a fact they previously volunteered.
            // Java's strip() is trim { Character.isWhitespace(it) }.
            if (dateOfBirth == null || dateOfBirth.isJavaBlank()) null
            else LocalDate.parse(dateOfBirth.trim { Character.isWhitespace(it) })
        } catch (e: DateTimeParseException) {
            // Reachable only from a caller that is not a browser — <input type="date"> submits
            // ISO-8601 or nothing — which is exactly why it is handled rather than assumed away.
            return render(language.messages().profileDateFormat())
        }
        val error = profileService.updateDateOfBirth(parsed)
        return if (error == null) {
            Response.seeOther(URI.create("/profile")).build()
        } else {
            render(error)
        }
    }

    private fun render(errorMessage: String?): Response =
        Response.ok(Templates.profile(profileService.current(), errorMessage)).build()
}
