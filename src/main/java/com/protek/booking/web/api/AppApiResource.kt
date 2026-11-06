@file:UseSerializers(UuidSerializer::class, InstantSerializer::class, BigDecimalSerializer::class)

package com.protek.booking.web.api

import com.protek.booking.agent.AssistantChat
import com.protek.booking.domain.Film
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.Payment
import com.protek.booking.domain.Site
import com.protek.booking.i18n.LanguagePreference
import com.protek.booking.i18n.StatusLabels
import com.protek.booking.isJavaBlank
import com.protek.booking.json.BigDecimalSerializer
import com.protek.booking.json.InstantSerializer
import com.protek.booking.json.UuidSerializer
import com.protek.booking.payment.PaymentService
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.security.CurrentActor
import com.protek.booking.security.Roles
import com.protek.booking.service.AgeAdvisoryService
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.BookingDraftService
import com.protek.booking.service.BookingService
import com.protek.booking.service.CalendarPricing
import com.protek.booking.service.CatalogService
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.CustomerProfileService
import com.protek.booking.service.NearestCity
import com.protek.booking.service.PurchaseCap
import com.protek.booking.service.SeatAvailabilityNotifier
import com.protek.booking.service.ShowtimeViability
import com.protek.booking.wallet.GoogleWalletLinks
import com.protek.booking.web.ComplaintContact
import com.protek.booking.web.SeatMapSupport
import com.protek.booking.web.customer.PushResource
import com.protek.booking.web.dto.ReservationGroupDetail
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.ForbiddenException
import jakarta.ws.rs.GET
import jakarta.ws.rs.NotFoundException
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.WebApplicationException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.jboss.resteasy.reactive.server.ServerExceptionMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.UUID

/**
 * The JSON half of the customer pages — what a ticket-buyer app calls instead of rendering
 * `customer/`'s templates: the anonymous browse path; the signed-in path up to the hold ([me],
 * the booking draft and [hold], each `@RolesAllowed` as its web endpoint is); the order page,
 * payment, cancel, the ticket and the profile; `/chat` ([chatPage], [chat]); and the halls no
 * branch reaches ([unassignedHalls], [hall]). The sockets in `AppLiveSocket.kt` stand in for the
 * seat map's and the order page's 3 s polls while they hold; the GETs here are still the first
 * read and the fallback.
 *
 * **No behaviour of its own**: each method is one call into the [CatalogService] method the Qute
 * page uses, and names that page, so a client holding this API is never a second booking engine. The tenant
 * is `CurrentTenant.publicCatalogueId()` inside the service, never anything in the request.
 *
 * **Browse is anonymous on purpose.** It is already public HTML, so no `@RolesAllowed`;
 * [nearest] changes nothing on the server (unlike the web's `POST /city/nearest` it sets no
 * cookie — the app remembers the city itself), so a duplicate or retried call is harmless.
 *
 * **Signed in, the identity is a Google ID token sent as a bearer token** and verified by the
 * `customer-app` OIDC tenant (`application.properties`), whose audience is the web's own
 * client id: the app's Credential Manager passes that id as the server client id, and Google
 * puts it in `aud`. `AppUserAugmentor` then maps it exactly as it maps a browser sign-in, so the
 * app's customer is the web's customer, and the tenant still comes from that row, never the
 * request. Every POST consumes JSON, but a content type is not the CSRF
 * defence: the browser's session cookie never authenticates this path, because `tenant-paths`
 * hands it to `customer-app` even while that tenant is off (`SessionCookieOffApiTest`).
 *
 * **Every POST's KDoc says what a resent copy does**, because a phone's network stack can send a
 * request again after its connection dropped before the answer arrived. Do not add one without.
 *
 * **Copy** follows [LanguagePreference]: no `/id` path on this API, so the cookie and then
 * `Accept-Language`. The app sends the language its reader picked.
 *
 * **What a seat map deliberately leaves out:** the reservation id and hold expiry a
 * [com.protek.booking.domain.SeatMapEntry] carries, except on the caller's own held seats
 * ([SeatMap.mine]). The web page never shows a stranger's reservation id either; status is all
 * a buyer needs.
 */
@Path("/api/app")
@RunOnVirtualThread
@Produces(MediaType.APPLICATION_JSON)
class AppApiResource {

    @Inject
    lateinit var catalogService: CatalogService

    @Inject
    lateinit var nearestCity: NearestCity

    @Inject
    lateinit var calendarPricing: CalendarPricing

    @Inject
    lateinit var language: LanguagePreference
    @Inject
    lateinit var orderPush: com.protek.booking.service.OrderPush

    @Inject
    lateinit var statusLabels: StatusLabels

    @Inject
    lateinit var bookingService: BookingService

    @Inject
    lateinit var bookingDrafts: BookingDraftService

    @Inject
    lateinit var ageAdvisories: AgeAdvisoryService

    @Inject
    lateinit var currentActor: CurrentActor

    @Inject
    lateinit var paymentService: PaymentService

    @Inject
    lateinit var walletLinks: GoogleWalletLinks

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @Inject
    lateinit var profiles: CustomerProfileService

    @Inject
    lateinit var assistantChat: AssistantChat

    @Inject
    lateinit var purchaseCap: PurchaseCap

    @Inject
    lateinit var showtimeViability: ShowtimeViability

    @Inject
    lateinit var complaint: ComplaintContact

    // --- The wire contract ----------------------------------------------------------------------

    @Serializable
    data class City(val city: String?, val province: String?, val branchCount: Int)

    @Serializable
    data class Cinema(
        val siteId: UUID?, val slug: String?, val name: String?, val city: String?,
        val province: String?, val address: String?, val timezone: String?,
        val latitude: BigDecimal?, val longitude: BigDecimal?,
    )

    /** @param releaseDate `YYYY-MM-DD`, the date `movieDetail.html` prints as it is. */
    @Serializable
    data class Movie(
        val filmId: UUID?, val slug: String?, val title: String?, val durationMinutes: Int?,
        val classification: String?, val synopsis: String?, val posterUrl: String?,
        val trailerUrl: String?, val releaseDate: String?,
    )

    @Serializable
    data class CinemaDetail(val cinema: Cinema, val movies: List<Movie>)

    /** @param unassignedShowings showings in a hall with no branch, which the web film page
     *        lists on their own because no cinema link reaches them. */
    @Serializable
    data class MovieDetail(val movie: Movie, val cinemas: List<Cinema>, val unassignedShowings: List<Showing>)

    /** `hallId` + `sessionId` are the seat map's address, exactly as in the web's
     * `/halls/{poolId}/showings/{sessionId}`. */
    @Serializable
    data class Showing(
        val sessionId: UUID?, val hallId: UUID?, val hallName: String?, val siteName: String?,
        val startsAt: Instant?, val endsAt: Instant?, val status: String?, val zone: String?,
    )

    /** A hall as `home.html`'s unassigned card links to it. */
    @Serializable
    data class Hall(val hallId: UUID?, val name: String?)

    /** One card of `showings.html`. */
    @Serializable
    data class HallShowing(
        val sessionId: UUID?, val label: String?, val startsAt: Instant?, val status: String?, val statusLabel: String?,
    )

    /** `/halls/{poolId}` (`showings.html`): the hall and its upcoming showings, in [zone]. */
    @Serializable
    data class HallPage(
        val hallId: UUID?, val name: String?, val address: String?, val zone: String?, val showings: List<HallShowing>,
    )

    @Serializable
    data class Seat(val seatId: UUID?, val label: String?, val status: String?, val statusLabel: String?,
                    /** A wheelchair space: a mark, anyone may book it (the web's ♿). */
                    val accessible: Boolean = false)

    @Serializable
    data class SeatRowView(val rowLabel: String?, val seats: List<Seat>)

    @Serializable
    data class Price(val amount: BigDecimal?, val bandLabel: String?, val inclusions: List<String>?)

    /** The statement is the one the reader will be asked to tick. The app shows
     * it and never keeps its own copy — it is hashed into the consent record. */
    @Serializable
    data class AgeGate(val classification: String?, val minimumAge: Int, val restricted: Boolean, val statement: String?)

    /** One of the caller's own held seats, with the two fields every other seat leaves out. */
    @Serializable
    data class OwnHold(val seatId: UUID?, val reservationId: UUID?, val holdExpiresAt: Instant?)

    /** The showtime viability rule, as `seatmapFragment.html`'s `seatViability` line states it. */
    @Serializable
    data class Viability(val minimumSeats: Int, val graceMinutes: Long)

    /**
     * @param mine the caller's own held seats on this showing; empty when signed out.
     * @param ageAdvisory the age attestation's warning that this caller's date of birth falls short of the rating,
     *        the banner the web prints above the tick; null in every other case, signed out included.
     * @param seatCapPerBooking the per-booking cap the hold enforces, null while it is off, as the web then
     *        prints no line; [seatCapPerDay] likewise, and [viability] null while that rule is off.
     * @param complaintPhone the tenant's complaint phone, `base.html`'s footer on the web: here because
     *        the seat map is the app's last screen before the hold, and the number must come first.
     * @param pseRegistrationNo the tenant's Tanda Daftar PSE, beside the phone in the same footer; null
     *        until the platform admin records it, as the web then prints nothing.
     */
    @Serializable
    data class SeatMap(
        val sessionId: UUID?, val hallId: UUID?, val hallName: String?, val movieTitle: String?,
        val classification: String?, val startsAt: Instant?, val salesCutoffAt: Instant?,
        val status: String?, val zone: String?, val price: Price?, val ageGate: AgeGate?,
        val rows: List<SeatRowView>, val mine: List<OwnHold>, val ageAdvisory: String?,
        val seatCapPerBooking: Int?, val seatCapPerDay: Int?, val viability: Viability?,
        val complaintPhone: String?, val pseRegistrationNo: String?,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class Position(val latitude: Double? = null, val longitude: Double? = null)

    @Serializable
    data class Chosen(val city: String?)

    @Serializable
    data class Refusal(val error: String?, val message: String?)

    /** `POST /push/subscribe`'s answer, as the web gets it. */
    @Serializable
    data class Watch(val resourcePoolWatchId: UUID?)

    /** What `base.html` shows a signed-in reader: the name in the header, and the page's language. */
    @Serializable
    data class Me(val email: String?, val name: String?, val language: String?)

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class OpenRequest(val sessionId: UUID? = null)

    @Serializable
    data class Opened(val bookingRef: UUID?)

    @Serializable
    data class ShowTime(val sessionId: UUID?, val hallId: UUID?, val label: String?, val hallName: String?, val selected: Boolean)

    /** @param date `YYYY-MM-DD`, what [chooseDate] takes back; `label` is the page's own wording. */
    @Serializable
    data class ShowDate(val date: String?, val label: String?, val selected: Boolean, val times: List<ShowTime>)

    /** `/booking?booking_ref=…`: the frame, the date and time fields, and the seat map below them,
     * from [BookingDraftService]'s one transaction. `dates` is empty where the web renders the two
     * fields as plain text — a label-only session, or a hall with no branch. */
    @Serializable
    data class Booking(
        val bookingRef: UUID?, val title: String?, val theatreName: String?, val studio: String?,
        val dates: List<ShowDate>, val seatMap: SeatMap,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class DateRequest(val date: String? = null)

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class ShowtimeRequest(val sessionId: UUID? = null)

    /** The web form's four fields; `ageAttested` is the age attestation's tick, false when absent as there. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class HoldRequest(
        val seatIds: List<UUID>? = null, val customerName: String? = null,
        val customerPhone: String? = null, val ageAttested: Boolean = false,
    )

    @Serializable
    data class HoldResponse(val groupId: UUID?, val bookingCode: String?)

    /** @param failedSeatId the seat that refused, when one did (taken, or not in this hall). */
    @Serializable
    data class HoldRefusal(val error: String?, val message: String?, val failedSeatId: UUID?)

    /** One card of `customer/orders.html` (`orderCard.html`). */
    @Serializable
    data class OrderSummary(
        val groupId: UUID?, val bookingCode: String?, val status: String?, val statusLabel: String?,
        val hallName: String?, val showingLabel: String?, val startsAt: Instant?, val createdAt: Instant?,
        val seatCount: Int, val total: BigDecimal?, val paidAt: Instant?,
    )

    /** The orders page's two sections, split by [BookingService.listOrders] as on the web.
     * [staff] picks `orders.html`'s empty state: staff have no orders of their own, and the web
     * points them at the booking lookup instead. */
    @Serializable
    data class Orders(val active: List<OrderSummary>, val past: List<OrderSummary>, val staff: Boolean)

    /**
     * One seat of an order, with its ticket.
     *
     * @param cancellable whether the web table offers this seat's cancel button. As there, it is
     *        what the page offers and not the boundary: [cancelItem] refuses a paid seat either way.
     * @param admissionToken what the door scans, present only on a confirmed seat (the filter
     *        [ReservationGroupDetail.admissionLines] applies before the web draws a barcode). The
     *        app draws the Code 128 itself, so the ticket works with no network.
     * @param walletUrl "Add to Google Wallet", absent whenever the web hides the button: no
     *        `booking.wallet.issuer-id`, or a seat whose link is too long to carry.
     */
    @Serializable
    data class OrderLine(
        val reservationId: UUID?, val seatLabel: String?, val status: String?, val statusLabel: String?,
        val price: BigDecimal?, val holdExpiresAt: Instant?, val cancellable: Boolean,
        val admissionToken: String?, val admittedAt: Instant?, val walletUrl: String?,
    )

    /** @param qrisContent the QR image URL Midtrans hosts, which the web puts in an `<img>` as it is.
     *  @param refundDue what is owed back (`payment.refund_due_amount` in V1__init_schema.sql), null when nothing is: the web's "Refund owed" card.
     *  @param refundReturned / refundReturnedAt what the gateway has given back and when, null
     *         when nothing: the web's "returned to your payment app" line. */
    @Serializable
    data class PaymentView(
        val paymentId: UUID?, val status: String?, val statusLabel: String?, val channel: String?,
        val amount: BigDecimal?, val qrisContent: String?, val expiresAt: Instant?, val paidAt: Instant?,
        val refundDue: BigDecimal? = null,
        val refundReturned: BigDecimal? = null, val refundReturnedAt: Instant? = null,
    )

    /**
     * `/orders/{groupId}`: the booking, its seats and tickets, and the payment card.
     *
     * `canPay`, `canConfirm` and `poll` are the payment card's state as the web template decides
     * it (`reservationFragment.html`), so the two cannot offer different buttons: `poll` is the
     * fragment's `every 3s` (a QR is waiting for the webhook), `canPay` its pay or try-again
     * button, `canConfirm` its pay-at-counter button.
     *
     * @param total the live seats' prices, as the counter totals them: a cancelled seat's price is
     *        a refund question, not part of what is owed now.
     */
    @Serializable
    data class Order(
        val groupId: UUID?, val bookingCode: String?, val status: String?, val statusLabel: String?,
        val hallName: String?, val showingLabel: String?, val startsAt: Instant?, val zone: String?,
        val lines: List<OrderLine>, val total: BigDecimal?, val inclusions: List<String>?,
        val payment: PaymentView?, val paymentEnabled: Boolean,
        val canPay: Boolean, val canConfirm: Boolean, val poll: Boolean,
        /** The fragment's "Showing cancelled" card, which replaces the payment card. */
        val showingCancelled: Boolean = false,
    )

    /**
     * `/profile`. `dateOfBirth` is `YYYY-MM-DD`; `age` is null with it.
     * `ageAttested` is the age attestation's one boolean, never an age (see [CustomerProfileService.Profile]).
     */
    @Serializable
    data class Profile(
        val name: String?, val email: String?, val dateOfBirth: String?, val age: Int?,
        val ageAttested: Boolean, val hasBooked: Boolean,
    )

    /** The form's one field; null or blank clears it, as an empty date field does on the web. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class DateOfBirthRequest(val dateOfBirth: String? = null)

    /** No body is expected; the type exists so the POSTs that take nothing still require JSON. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    class Empty

    /** `GET /chat`'s page as data: the thread to send back, and the notice chat.html puts
     * above the text input — where the text goes, not to type personal data, and the daily cap. */
    @Serializable
    data class ChatPage(val thread: UUID?, val notice: String?, val dailyCap: Int)

    /** The web form's two fields. `thread` stays a string: it is parsed as `/chat` parses it. */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class ChatRequest(val message: String? = null, val thread: String? = null)

    /** What `propose_seats` recorded, as the web builds its `?seats=` link from it: never the
     * model's words. The app opens its seat map's deep link with these. */
    @Serializable
    data class ChatProposal(val poolId: UUID?, val sessionId: UUID?, val seatIds: List<UUID>, val seatLabels: List<String>)

    /** `chatTurn.html` as data. `answer` is the model's text, to be shown as plain text. */
    @Serializable
    data class ChatReply(val answer: String?, val proposal: ChatProposal?, val notice: String?)

    // --- Endpoints ------------------------------------------------------------------------------

    /** Mirrors the city select on `GET /` (`base.html`). */
    @GET
    @Path("/cities")
    fun cities(): List<City> =
        catalogService.listCities().map { City(it.city, it.province, it.branchCount) }

    /** Mirrors `POST /city/nearest`, minus the cookie. A position near nothing answers
     * `{"city":null}`, as the web does. */
    @POST
    @Path("/cities/nearest")
    @Consumes(MediaType.APPLICATION_JSON)
    fun nearest(position: Position?): Response {
        val latitude = position?.latitude
        val longitude = position?.longitude
        if (latitude == null || longitude == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Refusal("bad_request", "latitude and longitude are required")).build()
        }
        val found = nearestCity.nearest(catalogService.listCities(), latitude, longitude)
        return Response.ok(Chosen(found.map { it.city }.orElse(null))).build()
    }

    /** Mirrors `GET /cities/{city}`. */
    @GET
    @Path("/cities/{city}/cinemas")
    fun cinemasInCity(@PathParam("city") city: String?): List<Cinema> =
        catalogService.listSitesInCity(city).map { cinema(it) }

    /** Mirrors `GET /cinemas/{siteRef}`; the ref is a slug or an id, as on the web. */
    @GET
    @Path("/cinemas/{siteRef}")
    fun cinemaDetail(@PathParam("siteRef") siteRef: String?): CinemaDetail {
        val site = catalogService.getSiteByRefOrThrow(siteRef)
        return CinemaDetail(cinema(site), catalogService.listFilmsAtSite(site.siteId).map { movie(it) })
    }

    /** Mirrors `GET /cinemas/{siteRef}/films/{filmRef}` (the showtimes fragment). `date`
     * (YYYY-MM-DD, optional) keeps one day of it, counted in each showing's own [Showing.zone]:
     * the booking agent's tool asks for one day, and the whole list is about twelve. */
    @GET
    @Path("/cinemas/{siteRef}/movies/{filmRef}/showings")
    fun showingsAtCinema(
        @PathParam("siteRef") siteRef: String?,
        @PathParam("filmRef") filmRef: String?,
        @QueryParam("date") date: String?,
    ): List<Showing> {
        val day = try {
            date?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            throw BadRequestException("date must be YYYY-MM-DD")
        }
        val site = catalogService.getSiteByRefOrThrow(siteRef)
        val film = catalogService.getFilmByRefOrThrow(filmRef)
        // SQL takes the day ±18 h, every offset a zone can have, because the zone is resolved
        // per hall with a fallback CalendarPricing owns; the exact day is cut here, from that rule.
        val midnightUtc = day?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
        return catalogService.listShowingsOfFilmAtSite(film.filmId, site.siteId,
            midnightUtc?.minus(Duration.ofHours(18)), midnightUtc?.plus(Duration.ofHours(24 + 18)))
            .map { showing(it) }
            .filter { day == null || it.startsAt?.atZone(ZoneId.of(it.zone))?.toLocalDate() == day }
    }

    /** Mirrors `GET /movies`. */
    @GET
    @Path("/movies")
    fun movies(): List<Movie> = catalogService.listNowPlaying().map { movie(it) }

    /** Mirrors `GET /movies/{filmRef}`. */
    @GET
    @Path("/movies/{filmRef}")
    fun movieDetail(@PathParam("filmRef") filmRef: String?): MovieDetail {
        val film = catalogService.getFilmByRefOrThrow(filmRef)
        val unassigned = catalogService.listShowingsOfFilm(film.filmId)
            .filter { it.siteId == null }.map { showing(it) }
        return MovieDetail(movie(film),
            catalogService.listCinemasShowingFilm(film.filmId).map { cinema(it) }, unassigned)
    }

    /** Mirrors `home.html`'s "Not yet assigned to a branch" card: the halls no cinema link
     * reaches. Every hall, while the tenant has no branch at all. */
    @GET
    @Path("/halls/unassigned")
    fun unassignedHalls(): List<Hall> = catalogService.listHallsWithoutSite().map { Hall(it.resourcePoolId, it.name) }

    /** Mirrors `GET /halls/{poolId}` (`showings.html`), where that card's links go. */
    @GET
    @Path("/halls/{poolId}")
    fun hall(@PathParam("poolId") poolId: UUID): HallPage {
        val hall = catalogService.getHallOrThrow(poolId)
        return HallPage(hall.resourcePoolId, hall.name, hall.address, zoneOf(hall).id,
            catalogService.listUpcomingShowings(poolId).map {
                HallShowing(it.resourcePoolSessionId, it.label, it.startsAt, it.status, statusLabels.of(it.status))
            })
    }

    /** Mirrors `GET /halls/{poolId}/showings/{sessionId}` and its `/fragment` poll: the same
     * one-transaction read, so the app and the web never show two pictures of availability. */
    @GET
    @Path("/halls/{poolId}/showings/{sessionId}")
    fun seatMap(@PathParam("poolId") poolId: UUID, @PathParam("sessionId") sessionId: UUID): SeatMap {
        val page = catalogService.getSeatMapPage(poolId, sessionId)
        return seatMap(page, if (page.hall.siteId == null) null else siteOrNull(page.hall.siteId))
    }

    // --- Signed in. Same roles as each web endpoint, and the same services. ---------------

    /** Mirrors the signed-in header in `base.html`. Also how the app learns that a Google account
     * is not one that can book here: a deactivated row has no role, and this answers 403. */
    @GET
    @Path("/me")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    fun me(): Me = Me(currentActor.email().orElse(null), currentActor.displayName(), language.tag())

    /**
     * Mirrors `POST /booking`, a showtime pressed. The web answers an anonymous caller with the
     * sign-in sheet; the app signs in first, so here that case is the 401 every signed-in method
     * gives. Kept rather than skipped because the draft is what the date and time fields move.
     * **Resent**, it answers with the same `bookingRef`: the draft is upserted per account and
     * showing.
     */
    @POST
    @Path("/bookings")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun open(request: OpenRequest?): Opened =
        Opened(bookingDrafts.openDraft(request?.sessionId)
            .orElseThrow { ForbiddenException("This sign-in has no account to open a booking for.") })

    /** Mirrors `GET /booking?booking_ref=…`, ownership check included: someone else's ref is a 403. */
    @GET
    @Path("/bookings/{bookingRef}")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    fun booking(@PathParam("bookingRef") bookingRef: UUID): Booking = booking(bookingDrafts.load(bookingRef))

    /** Mirrors `POST /booking/{ref}/date`: the earliest showtime that day, or the booking as it
     * was when the day has none. The answer's `bookingRef` is the one to use from then on: it is
     * another of the account's bookings when one was already open on that showtime, as on the web.
     * **Resent**, it lands where the first copy did. */
    @POST
    @Path("/bookings/{bookingRef}/date")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun chooseDate(@PathParam("bookingRef") bookingRef: UUID, request: DateRequest?): Booking {
        // A 400 where the web ignores the field: the page re-renders itself, an app needs telling.
        val date = try {
            request?.date?.let(LocalDate::parse)
        } catch (e: DateTimeParseException) {
            null
        } ?: throw BadRequestException("date must be YYYY-MM-DD")
        return booking(bookingDrafts.moveToDate(bookingRef, date))
    }

    /** Mirrors `POST /booking/{ref}/showtime`, with [chooseDate]'s `bookingRef` rule. **Resent**,
     * it lands where the first copy did. */
    @POST
    @Path("/bookings/{bookingRef}/showtime")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun chooseShowtime(@PathParam("bookingRef") bookingRef: UUID, request: ShowtimeRequest?): Booking =
        booking(bookingDrafts.moveToShowing(bookingRef, request?.sessionId))

    /**
     * Mirrors `POST /halls/{poolId}/showings/{sessionId}/hold`: the same [BookingService.hold],
     * with the age attestation's tick as the form's `ageAttested`. Success is the order to go to, where the web
     * sends `HX-Redirect: /orders/{groupId}`. A refusal is a 409 carrying the sentence the seat
     * map's banner shows, plus the seat that refused when one did, so the app redraws the map on
     * one and shows the sentence on either.
     *
     * **Resent** — OkHttp can send it again after a dropped connection — it answers 200 with the
     * first copy's group: [BookingService.hold] recognises the caller's own live hold on exactly
     * these seats before anything else is decided.
     *
     * The age attestation's sentence is hashed in this request's language, so the app sends the `Accept-Language`
     * it read the seat map in; nothing in the body can name a language.
     */
    @POST
    @Path("/halls/{poolId}/showings/{sessionId}/hold")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun hold(
        @PathParam("poolId") poolId: UUID, @PathParam("sessionId") sessionId: UUID, request: HoldRequest?,
    ): Response {
        val result = bookingService.hold(poolId, sessionId, request?.seatIds,
            request?.customerName, request?.customerPhone,
            if (request?.ageAttested == true) AgeClassification.Attestation.TICKED
            else AgeClassification.Attestation.NOT_TICKED)
        if (result.success) {
            val group = result.group!!
            return Response.ok(HoldResponse(group.reservationGroupId, group.bookingCode)).build()
        }
        return Response.status(Response.Status.CONFLICT)
            .entity(HoldRefusal("hold_refused", result.failureReason, result.failedResourceUnitId))
            .build()
    }

    // --- The order, payment, ticket and profile --------------------------------------------------

    /** Mirrors `GET /orders`: the caller's own orders, and nobody else's — it takes no parameter. */
    @GET
    @Path("/orders")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    fun orders(): Orders {
        val orders = bookingService.listOrders()
        return Orders(orders.active.map { orderSummary(it) }, orders.past.map { orderSummary(it) }, currentActor.isStaff())
    }

    /** Mirrors `GET /orders/{groupId}` and its `/fragment` poll, which render the same data; the
     * app reads this on arrival, when the order's socket says it moved, and every 3 s while
     * [Order.poll] is true and that socket is down. Someone else's order is a 403. */
    @GET
    @Path("/orders/{groupId}")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    fun order(@PathParam("groupId") groupId: UUID): Order = orderView(groupId)

    /**
     * Mirrors `POST /orders/{groupId}/pay`: the QRIS charge, then the order, where the web
     * redirects to the order page. Paid already, or Midtrans unconfigured, it answers with the
     * order as it stands, as the web's redirect does.
     *
     * **Resent** before the order is paid, it creates a second charge, as the web's double submit
     * does, and the answer carries that newest one. The first copy's QR was never shown, because
     * its answer is the one that was lost, and it expires at Midtrans unpaid. Resent after the
     * payment settled, it answers with the paid order ([PaymentService.OrderAlreadyPaidException]).
     */
    @POST
    @Path("/orders/{groupId}/pay")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun pay(@PathParam("groupId") groupId: UUID, ignored: Empty?): Order {
        // The ownership check first, as on the web: a charge is never made on someone else's order.
        bookingService.getGroupDetail(groupId)
        try {
            paymentService.createQrisCharge(groupId, false)
        } catch (alreadyPaid: PaymentService.OrderAlreadyPaidException) {
            // The order shows the payment, as the web page does after its redirect.
        }
        return orderView(groupId)
    }

    /** Mirrors `POST /orders/{groupId}/confirm`, pay at the counter. **Resent**, it answers with
     * the confirmed order: [BookingService.confirmGroup]'s false means "not all held any more",
     * which the web ignores, and so does this — the order's status says what happened. */
    @POST
    @Path("/orders/{groupId}/confirm")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun confirm(@PathParam("groupId") groupId: UUID, ignored: Empty?): Order {
        bookingService.confirmGroup(groupId)
        return orderView(groupId)
    }

    /**
     * Mirrors `POST /orders/{groupId}/items/{reservationId}/cancel`, with the web's actor and
     * reason. A seat of a paid order is a 403 carrying the paid-cancellation sentence, the one
     * `BookingService.assertMayCancelSettled` refuses the web with. **Resent**, it answers with
     * the order and the seat already cancelled: the second cancel changes no row.
     */
    @POST
    @Path("/orders/{groupId}/items/{reservationId}/cancel")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun cancelItem(
        @PathParam("groupId") groupId: UUID, @PathParam("reservationId") reservationId: UUID,
        ignored: Empty?,
    ): Order {
        bookingService.cancelReservation(reservationId, "customer", "Cancelled by customer")
        return orderView(groupId)
    }

    /**
     * Mirrors `POST /orders/{groupId}/push` (`OrderResource.watchOrder`): "Notify me about this
     * order", with the phone's FID, through the same [OrderPush.watch]. Only the order's own
     * customer, a 403 for anyone else; the language is this request's (`Accept-Language`).
     * **Resent**, the same upsert.
     */
    @POST
    @Path("/orders/{groupId}/push")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun watchOrder(@PathParam("groupId") groupId: UUID, request: PushResource.SubscribeRequest?): Response {
        val token = request?.token?.takeUnless { it.isJavaBlank() }
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity(Refusal("bad_request", "token is required")).build()
        orderPush.watch(groupId, token, language.current())
        return Response.noContent().build()
    }

    /** Mirrors `DELETE /orders/{groupId}/push`: 204 whether or not the watch was still there. */
    @DELETE
    @Path("/orders/{groupId}/push")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun unwatchOrder(@PathParam("groupId") groupId: UUID, request: PushResource.SubscribeRequest?): Response {
        val token = request?.token
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity(Refusal("bad_request", "token is required")).build()
        orderPush.unwatch(groupId, token)
        return Response.noContent().build()
    }

    /** Mirrors `GET /profile`. */
    @GET
    @Path("/profile")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    fun profile(): Profile = profile(profiles.current())

    /**
     * Mirrors `POST /profile`: 204, and the app re-reads [profile], as the web redirects to the
     * page. Not the profile itself, because this request's identity was resolved before the write
     * and, for an account with no customer row until now, would answer with the old blank. A date
     * the web refuses is a 400 carrying the banner's sentence. **Resent**, it writes the same value
     * again and answers the same: it sets the column, it does not add to anything.
     */
    @POST
    @Path("/profile")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun updateDateOfBirth(request: DateOfBirthRequest?): Response {
        val raw = request?.dateOfBirth?.trim()
        val parsed = try {
            if (raw.isNullOrEmpty()) null else LocalDate.parse(raw)
        } catch (e: DateTimeParseException) {
            return refuse(language.messages().profileDateFormat())
        }
        val error = profiles.updateDateOfBirth(parsed) ?: return Response.noContent().build()
        return refuse(error)
    }

    /**
     * Mirrors `POST /push/subscribe` (`PushResource.subscribe`): the seat map's "notify me when
     * seats free up" watch, through [SeatAvailabilityNotifier.watch]. [PushResource.SubscribeRequest]'s
     * `token` is the phone's FCM registration token, which `PushNotificationService` sends as
     * `token`, as it sends the browser's. Anonymous, as the web's is: the tenant is
     * `publicCatalogueId()`, never the body, and no customer is recorded on the row.
     * **Resent**, it is the same upsert: the same row with its 20 minutes restarted, and the same id.
     */
    @POST
    @Path("/push/subscribe")
    @Consumes(MediaType.APPLICATION_JSON)
    fun watch(request: PushResource.SubscribeRequest?): Response {
        val poolId = request?.poolId
        val token = request?.token
        if (poolId == null || token == null || token.isJavaBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Refusal("bad_request", "poolId and token are required")).build()
        }
        val saved = notifier.watch(currentTenant.publicCatalogueId(), poolId, request.sessionId, token)
        return Response.ok(Watch(saved.resourcePoolWatchId)).build()
    }

    /** Mirrors `DELETE /push/subscribe` (`PushResource.unsubscribe`): 204 whether or not a watch
     * was there, so a resent or late copy (the watch already expired) answers the same. */
    @DELETE
    @Path("/push/subscribe")
    @Consumes(MediaType.APPLICATION_JSON)
    fun unwatch(request: PushResource.SubscribeRequest?): Response {
        val poolId = request?.poolId
        val token = request?.token
        if (poolId == null || token == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(Refusal("bad_request", "poolId and token are required")).build()
        }
        notifier.unwatch(currentTenant.publicCatalogueId(), poolId, request.sessionId, token)
        return Response.noContent().build()
    }

    // --- The booking assistant -----------------------------------------------------------------

    /** Mirrors `GET /chat` (`ChatResource.page`): a 404 while the assistant is off, which is how
     * the app knows to hide its entry point, as `AssistantNav` hides the web's link. */
    @GET
    @Path("/chat")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    fun chatPage(): ChatPage {
        if (!assistantChat.configured()) throw NotFoundException()
        return ChatPage(UUID.randomUUID(), chatNotice(), assistantChat.dailyCap)
    }

    /**
     * Mirrors `POST /chat` (`ChatResource.ask`) through the same [AssistantChat.ask]: the same
     * assistant, the memory id `{appUserId}:{thread}`, and one daily cap per app user shared with
     * the web. 200 is the answer and, when `propose_seats` ran, the proposal; the hold stays the
     * customer's own tap on the seat map. An empty message is a 400, the cap a 429 and a failed
     * model host a 503, each carrying the sentence `/chat` would show.
     *
     * **Resent**, it is a second message: one more of the day's cap and one more answer, with the
     * first copy already in the thread's memory. Nothing is held or written, so nothing doubles
     * but the cost.
     */
    @POST
    @Path("/chat")
    @RolesAllowed(Roles.CUSTOMER, Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    fun chat(request: ChatRequest?): Response {
        if (!assistantChat.configured()) throw NotFoundException()
        val user = currentActor.appUserId().orElseThrow { NotFoundException() }
        val msg = language.messages()
        return when (val reply = assistantChat.ask(user, request?.thread, request?.message, language.tag())) {
            AssistantChat.Reply.Empty -> refuse(msg.chatEmpty())
            AssistantChat.Reply.CapReached -> Response.status(429)
                .entity(Refusal("cap_reached", msg.chatCapReached(assistantChat.dailyCap))).build()
            AssistantChat.Reply.Unavailable -> Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity(Refusal("unavailable", msg.chatUnavailable())).build()
            is AssistantChat.Reply.Answer -> Response.ok(ChatReply(reply.text,
                reply.proposal?.let { ChatProposal(it.hallId, it.sessionId, it.seatIds, it.labels) },
                chatNotice())).build()
        }
    }

    private fun chatNotice(): String =
        language.messages().let { it.chatPrivacy(assistantChat.host) + " " + it.chatDailyCap(assistantChat.dailyCap) }

    private fun refuse(message: String): Response =
        Response.status(Response.Status.BAD_REQUEST).entity(Refusal("bad_request", message)).build()

    /** JSON for the services' `NotFoundException` and friends:
     * otherwise a 404 reaches the app as an empty body in prod and an HTML page in dev. */
    @ServerExceptionMapper
    fun mapWebApplicationException(e: WebApplicationException): Response {
        val original = e.response
        val reason: String? = original.statusInfo.reasonPhrase
        return Response.status(original.status)
            .type(MediaType.APPLICATION_JSON)
            .entity(Refusal(reason?.lowercase(Locale.ROOT)?.replace(' ', '_') ?: "error", e.message))
            .build()
    }

    // --- Mapping --------------------------------------------------------------------------------

    /** The seat map as this caller sees it. [SeatMap.mine] keeps only seats the map itself shows
     * taken by one of the caller's rows, so the two reads behind it cannot disagree on the screen. */
    private fun seatMap(page: CatalogService.SeatMapPage, site: Site?): SeatMap {
        val hall = page.hall
        val zone = calendarPricing.zoneFor(site?.timezone, hall.timezone, "hall " + hall.resourcePoolId)
        val price = page.price
        val gate = page.ageGate(language.current())
        val s = page.showing
        val own = bookingService.heldByCaller(s.resourcePoolSessionId).mapNotNull { it.reservationId }.toSet()
        return SeatMap(s.resourcePoolSessionId, hall.resourcePoolId, hall.name,
            page.film?.title ?: s.label, page.film?.classification,
            s.startsAt, s.salesCutoffAt, s.status, zone.id,
            if (price == null) null else Price(price.amount, price.bandLabel(), price.inclusions),
            if (gate == null) null else AgeGate(gate.classification, gate.minimumAge, gate.restricted(), gate.statement),
            SeatMapSupport.groupByRow(page.seats).map { row ->
                SeatRowView(row.rowLabel, row.seats!!.map {
                    Seat(it.resourceUnitId, it.label, it.seatStatus, statusLabels.of(it.seatStatus), it.accessible)
                })
            },
            page.seats.filter { it.reservationId in own }
                .map { OwnHold(it.resourceUnitId, it.reservationId, it.holdExpiresAt) },
            ageAdvisories.forSeatMap(page)?.message,
            if (purchaseCap.enforced()) purchaseCap.maxSeatsPerHold() else null,
            if (purchaseCap.dailyEnforced()) purchaseCap.maxSeatsPerDay() else null,
            if (showtimeViability.enforced()) Viability(showtimeViability.minimumSeats(), showtimeViability.graceMinutes()) else null,
            complaint.phone(), complaint.pseRegistrationNo())
    }

    private fun orderSummary(e: ReservationGroupRepository.HistoryEntry): OrderSummary =
        OrderSummary(e.reservationGroupId, e.bookingCode, e.status, statusLabels.of(e.status),
            e.hallName, e.showingLabel, e.showingStartsAt, e.createdAt, e.seatCount, e.totalAmount, e.paidAt)

    /** What `OrderResource.detail` hands the template, as JSON, with the template's decisions. */
    private fun orderView(groupId: UUID): Order {
        // getGroupDetail runs the ownership check, so it comes first, as in OrderResource.
        val detail = bookingService.getGroupDetail(groupId)
        val payment: Payment? = paymentService.paymentsFor(groupId).firstOrNull()
        val group = detail.group!!
        val enabled = paymentService.isEnabled()
        val wallet = if (walletLinks.isConfigured()) walletLinks.saveUrlsFor(detail, currentTenant.get().displayName)
            else emptyMap()

        // reservationFragment.html's card, branch for branch.
        val status = payment?.status
        val cancelledShowing = detail.showing?.status == "cancelled"
        val poll = !cancelledShowing && status == Payment.STATUS_PENDING && !payment.qrisContent.isNullOrEmpty()
        val retry = !cancelledShowing && !poll && status != Payment.STATUS_PAID &&
            (status == Payment.STATUS_EXPIRED || status == Payment.STATUS_FAILED)
        val fresh = !cancelledShowing && !poll && status != Payment.STATUS_PAID && !retry && group.status == "pending"
        val cancelAllowed = currentActor.isStaff() || status != Payment.STATUS_PAID

        val lines = detail.lines!!.map { line ->
            val r = line.reservation!!
            val live = r.status == "held" || r.status == "confirmed"
            OrderLine(r.reservationId, line.seat?.label, r.status, statusLabels.of(r.status),
                r.priceAmount, if (r.status == "held") r.holdExpiresAt else null, live && cancelAllowed,
                if (r.status == "confirmed") r.admissionToken else null, r.admittedAt, wallet[r.reservationId])
        }
        val total = detail.lines.map { it.reservation!! }
            .filter { it.status == "held" || it.status == "confirmed" }
            .mapNotNull { it.priceAmount }
            .fold(BigDecimal.ZERO, BigDecimal::add)
        return Order(groupId, group.bookingCode, group.status, statusLabels.of(group.status),
            detail.hall?.name, detail.showing?.label, detail.showing?.startsAt, detail.zone?.id,
            lines, total, detail.inclusions,
            payment?.let {
                PaymentView(it.paymentId, it.status, statusLabels.of(it.status), it.paymentChannel,
                    it.amount, it.qrisContent, it.expiresAt, it.paidAt,
                    it.refundDueAmount?.takeIf { _ -> it.hasOutstandingRefund() },
                    it.returnedAmount(), it.returnedAt())
            },
            enabled, retry || (fresh && enabled), fresh, poll, cancelledShowing)
    }

    private fun profile(p: CustomerProfileService.Profile): Profile =
        Profile(p.name, p.email, p.dateOfBirth?.toString(), if (p.hasAge()) p.age() else null,
            p.ageAttested, p.hasBooked)

    private fun booking(p: BookingDraftService.BookingPage): Booking =
        Booking(p.draft.bookingRef, p.title(), p.theatreName(), p.studioNumber(),
            p.dates.map { d ->
                ShowDate(d.value(), d.label, d.selected,
                    d.times.map { ShowTime(it.sessionId, it.resourcePoolId, it.label, it.hallName, it.selected) })
            },
            seatMap(p.seatMap, p.site))

    private fun cinema(s: Site): Cinema =
        Cinema(s.siteId, s.slug, s.name, s.city, s.province, s.address, s.timezone, s.latitude, s.longitude)

    private fun movie(f: Film): Movie =
        Movie(f.filmId, f.slug, f.title, f.durationMinutes, f.classification, f.synopsis, f.posterUrl, f.trailerUrl,
            f.releaseDate?.toString())

    private fun showing(f: ResourcePoolSessionRepository.FilmShowing): Showing {
        val zone = calendarPricing.zoneFor(f.siteTimezone, f.hallTimezone, "hall " + f.resourcePoolId)
        return Showing(f.resourcePoolSessionId, f.resourcePoolId, f.hallName, f.siteName,
            f.startsAt, f.endsAt, f.status, zone.id)
    }

    /** A hall's zone as [seatMap] resolves it: its branch's, else its own. */
    private fun zoneOf(hall: ResourcePool): ZoneId =
        calendarPricing.zoneFor(hall.siteId?.let { siteOrNull(it) }?.timezone, hall.timezone, "hall " + hall.resourcePoolId)

    private fun siteOrNull(siteId: UUID): Site? =
        try {
            catalogService.getSiteOrThrow(siteId)
        } catch (e: NotFoundException) {
            null
        }
}
