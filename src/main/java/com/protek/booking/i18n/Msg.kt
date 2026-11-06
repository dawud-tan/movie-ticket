package com.protek.booking.i18n

import io.quarkus.qute.i18n.Message
import io.quarkus.qute.i18n.MessageBundle

/**
 * The interface copy, in one place, in the default language — the English text here <em>is</em>
 * the English translation, and {@code messages/msg_id.properties} is the Indonesian one.
 *
 * <p>Reached from a template as {@code {msg:navCinemas}} ({@code msg} is the bundle's default
 * namespace) and from Java as {@code language.messages().navCinemas()}.
 *
 * <p><b>Why a message bundle rather than two copies of each template.</b> The seat map's revert
 * is markup — a taken seat renders with neither an {@code id} nor {@code hx-preserve} — and the
 * hold path's invariants are spelled in the same files. Duplicating 57 templates would mean
 * holding those invariants in two places and finding out from a customer which copy drifted.
 * One markup, two strings.
 *
 * <p><b>Both halves are checked at build time,</b> which is the property worth having here:
 * a key in {@code msg_id.properties} with no method on this interface fails the build
 * ("Default bundle method not found"), and a method with no entry in the localized file falls
 * back to the English text rather than rendering a blank. Nothing can drift silently the way
 * a hand-pasted value can.
 *
 * <p><b>This covers {@code base.html}, {@code templates/customer/} and {@code templates/admin/}.</b>
 * Add keys here as pages are converted; the ordering below follows the page, not the alphabet, because that is
 * how a translator reads it.
 *
 * <p><b>Three kinds of user-visible string are deliberately <em>not</em> here,</b> so that nobody
 * reads this interface as "all the copy":
 * <ul>
 * <li>Copy generated in Java — {@code BookingService}'s hold refusals and
 * {@code AgeClassification}'s two sentences. The second is the interesting one: its wording is
 * hashed into {@code consent_record.policy_text_hash}, so translating it changes what a stored
 * consent row pins, which is a decision about legal evidence rather than a copy edit.</li>
 * <li>Copy inside the hand-written JavaScript, which a bundle cannot reach at all: it reads
 * {@code data-msg-*} attributes the template renders.</li>
 * <li>The brand. {@code Demo Cinema} is a name, not a string to translate, and a film title, a
 * branch name and a seat label are tenant data.</li>
 * </ul>
 *
 * <p><b>A message is HTML-escaped like any other expression, and that is worth knowing before
 * writing one.</b> A bundle method returns an ordinary {@code String}, so Qute's
 * {@code HtmlEscaper} maps the result: {@code Browse what's on} reaches the browser as
 * {@code Browse what&#39;s on}. Correct, and invisible to a reader — but a test that asserts the
 * literal apostrophe fails, and a message that carries a tag has it printed as text. The two
 * messages here that must emit markup say so and are rendered with {@code .raw} at their call
 * sites; everything else stays plain text and escapes harmlessly. Measured, not assumed:
 * {@code OrderHistoryTest} caught it, and {@code LocalizedCopyTest} now pins the {@code .raw}
 * half so the next page converted does not have to rediscover either.
 *
 * <p>Parameters are referenced by name ({@code {language}}), which works because the build sets
 * {@code <javaParameters>true</javaParameters>} on {@code kotlin-maven-plugin} in {@code pom.xml}
 * (this file was Java, with javac's {@code <parameters>true</parameters>}, until 2026-10-09).
 * Without it these would have to be {@code {0}}.
 */
@MessageBundle
interface Msg {

    // --- base.html: the header nav ---------------------------------------------------------

    @Message("Cinemas")
    fun navCinemas(): String

    @Message("Now playing")
    fun navNowPlaying(): String

    @Message("Your orders")
    fun navYourOrders(): String

    @Message("Tenant admin")
    fun navTenantAdmin(): String

    @Message("Staff")
    fun navStaff(): String

    @Message("Assistant")
    fun navAssistant(): String

    @Message("Platform")
    fun navPlatform(): String

    // --- base.html: the city select ---------------------------------------------------------

    @Message("City")
    fun cityLabel(): String

    /** The city form's submit. Short on purpose: it sits next to a select in a header that
     * wraps to three rows on a phone. */
    @Message("Go")
    fun cityGo(): String

    @Message("Use my location")
    fun cityUseMyLocation(): String

    // --- base.html: the language switch ----------------------------------------------------

    /**
     * The accessible name of the switch, whose visible label is the other language's own name
     * and therefore not translated. In the language of the page being left, because the person
     * reading it is still on that page.
     */
    @Message("Switch language to {language}")
    fun languageSwitch(language: String?): String

    // --- base.html: the session block ------------------------------------------------------

    @Message("Sign in")
    fun signIn(): String

    @Message("Sign out")
    fun signOut(): String

    // --- base.html: the footer ---------------------------------------------------------------

    /** Before the tenant's complaint phone. `/api/app`'s seat map hands the app the same number. */
    @Message("Customer complaints:")
    fun complaintPhoneLabel(): String

    /** Before the tenant's PSE registration number, shown only once one is recorded. */
    @Message("PSE registration no.")
    fun pseRegistrationLabel(): String

    // --- customer/home.html ----------------------------------------------------------------

    /** Both the {@code <title>} and the {@code <h1>}: they are the same sentence and a
     * translator should not be asked to keep two entries equal. */
    @Message("Book tickets")
    fun homeTitle(): String

    /** {@code og:description}, which is what a pasted link renders as in a WhatsApp group —
     * the way a showtime link actually travels in this market. */
    @Message("Cinema showtimes and seat booking.")
    fun homeDescription(): String

    @Message("Pick a cinema, then the film and the time.")
    fun homeCinemasHint(): String

    @Message("Pick a film, then the cinema and the time.")
    fun homeMoviesHint(): String

    @Message("Not yet assigned to a branch")
    fun homeUnassignedHeading(): String

    @Message("These halls are still bookable, but do not appear under a city until they are assigned to one.")
    fun homeUnassignedHint(): String

    // --- Shared across several customer pages ----------------------------------------------
    //
    // One key per phrase rather than one per place it appears. Where two pages say the same
    // thing in English they must keep saying the same thing in Indonesian, and a second key is
    // how that stops being true — home.html's tile labels make the same argument.

    /** The back link on booking.html, which can return to either a branch or the film menu. */
    @Message("Back")
    fun back(): String

    @Message("Home")
    fun backHome(): String

    /** The word for "the list of times a film is on", used as a page title and as a back link.
     * Not {@link #navNowPlaying()}: that names the FILM menu, which is a different page. */
    @Message("Showtimes")
    fun showtimes(): String

    @Message("No upcoming showings.")
    fun noUpcomingShowings(): String

    /** The showtime strip's own empty state. "here" is load-bearing: the strip is opened under
     * one branch on a film page and under one film on a branch page, and without it the sentence
     * reads as "this film is not on anywhere". */
    @Message("No upcoming showings here.")
    fun showtimesFragmentEmpty(): String

    /** The button that opens a showtime strip in place, on both browse pages. */
    @Message("Show times")
    fun showTimes(): String

    @Message("Choose seats")
    fun chooseSeats(): String

    /** A film's running time. Abbreviated in both languages, because it sits inside a line that
     * already carries the classification badge. */
    @Message("{minutes} min")
    fun filmDuration(minutes: Int): String

    /** The poster's alt text. Names the film first in English and last in Indonesian, which is
     * the natural order in each — the reason this is a message and not "{title} + ' poster'". */
    @Message("{title} poster")
    fun filmPosterAlt(title: String?): String

    @Message("Save")
    fun save(): String

    // --- Status vocabulary ------------------------------------------------------------------
    //
    // Every status a customer can see, from four different CHECK constraints (V1): a session's,
    // a reservation group's, a reservation's and a payment's, plus the seat map's own
    // "available". Rendered through StatusLabels, never by interpolating the column value — see
    // that class for why the CSS class and the label have to be different strings.

    @Message("Available")
    fun statusAvailable(): String

    @Message("Held")
    fun statusHeld(): String

    @Message("Confirmed")
    fun statusConfirmed(): String

    /** The seat map's wheelchair mark and its legend. A mark only. */
    @Message("Wheelchair space")
    fun seatAccessible(): String

    @Message("Pending")
    fun statusPending(): String

    @Message("Cancelled")
    fun statusCancelled(): String

    @Message("Failed")
    fun statusFailed(): String

    @Message("Expired")
    fun statusExpired(): String

    @Message("Completed")
    fun statusCompleted(): String

    @Message("Paid")
    fun statusPaid(): String

    @Message("Refunded")
    fun statusRefunded(): String

    @Message("Scheduled")
    fun statusScheduled(): String

    @Message("On sale")
    fun statusOnSale(): String

    @Message("Closed")
    fun statusClosed(): String

    // --- customer/halls.html: the home page of a tenant with no branch configured -----------

    @Message("Choose a venue")
    fun hallsHeading(): String

    @Message("No venues configured yet.")
    fun hallsEmpty(): String

    // --- customer/sites.html: the branches in one city --------------------------------------

    /** Both the {@code <title>} and the {@code <h1>}, and the back link on a branch page. */
    @Message("Cinemas in {city}")
    fun citiesTitle(city: String?): String

    @Message("Every cinema in {city}, with today's showtimes.")
    fun citiesDescription(city: String?): String

    @Message("No cinemas in {city}.")
    fun citiesEmpty(city: String?): String

    // --- customer/cinemaDetail.html ---------------------------------------------------------

    @Message("What's playing here")
    fun cinemaWhatsPlaying(): String

    @Message("Nothing on sale at this cinema right now.")
    fun cinemaEmpty(): String

    /** {@code og:description} for a branch with no city recorded — the site table keeps the column
     * nullable, so this is a real page rather than a defensive branch. */
    @Message("Showtimes and seat booking at {branch}.")
    fun cinemaDescription(branch: String?): String

    @Message("Showtimes and seat booking at {branch}, {city}.")
    fun cinemaDescriptionInCity(branch: String?, city: String?): String

    // --- customer/movies.html ---------------------------------------------------------------
    //
    // The heading reuses navNowPlaying for home.html's reason: the menu and the page it opens
    // are the same two words, and a second key is an invitation for them to diverge.

    @Message("Every film on sale right now, and where it is playing.")
    fun moviesDescription(): String

    @Message("Nothing on sale right now.")
    fun moviesEmpty(): String

    // --- customer/movieDetail.html ----------------------------------------------------------

    /** {@code LocalDate} rather than a preformatted String: the value comes straight off the
     * film row, and rendering it is Qute's job — the same job {@code {film.releaseDate}} did
     * before this line was a message. Dates on the UI are still pinned to English by
     * {@code TemplateExtensions}, which is where that is decided, not here. */
    @Message("released {date}")
    fun movieReleased(date: java.time.LocalDate?): String

    @Message("Watch the trailer")
    fun movieTrailer(): String

    @Message("Where it's playing")
    fun movieWherePlaying(): String

    @Message("These showings are bookable, but their studio does not belong to a cinema yet, so they do not appear under one.")
    fun movieUnassignedHint(): String

    // --- customer/showings.html: one studio's upcoming sessions -----------------------------

    @Message("Upcoming showings at {hall}.")
    fun hallDescription(hall: String?): String

    @Message("All venues")
    fun showingsBackAllVenues(): String

    @Message("Upcoming showings")
    fun showingsUpcoming(): String

    @Message("View seat map")
    fun showingsViewSeatMap(): String

    // --- customer/orders.html ---------------------------------------------------------------

    /**
     * The staff empty state, anchor and all.
     *
     * <p>The link is <em>inside</em> the message rather than split around it, because where a
     * sentence puts its verb differs between the two languages and a translator handed three
     * fragments cannot move it.
     *
     * <p><b>Rendered with {@code .raw}</b>, and it has to be: a bundle method returns an
     * ordinary {@code String}, so {@code HtmlEscaper} treats it like any other expression result
     * and would emit {@code &lt;a href=…&gt;} as visible text. Safe for exactly the reason
     * {@code seoHead.html}'s {@code jsonLd.raw} is — the markup is authored here, in this file,
     * and no caller can put anything into it. Only two messages in this interface carry markup;
     * everything else is plain text and must stay that way.
     */
    @Message("Staff accounts hold no orders of their own. To find a customer's booking, use the <a href=\"/admin/bookings\">booking lookup</a> — they will have an eight-character code.")
    fun ordersStaffEmpty(): String

    @Message("No orders yet. Your orders appear here once you have held some seats.")
    fun ordersEmpty(): String

    @Message("Browse what's on")
    fun ordersBrowse(): String

    @Message("Current orders")
    fun ordersCurrent(): String

    @Message("Nothing coming up. Your next booking appears here.")
    fun ordersNoneCurrent(): String

    @Message("Past orders")
    fun ordersPast(): String

    @Message("Nothing here yet.")
    fun ordersNonePast(): String

    // --- customer/orderCard.html ------------------------------------------------------------

    @Message("Booked {when}")
    fun orderBooked(`when`: String?): String

    /**
     * The seat count on an order card.
     *
     * <p>The plural is inside the message, not in the template, because Indonesian has no
     * plural marking at all — {@code msg_id.properties} answers "{count} kursi" for every
     * number, and a template-side {@code {#if}} would have forced it to pretend otherwise.
     */
    @Message("{count} seat{#if count > 1}s{/if}")
    fun orderSeatCount(count: Int): String

    @Message("No seats were held")
    fun orderNoSeats(): String

    @Message("Paid {when}")
    fun orderPaidAt(`when`: String?): String

    @Message("View order")
    fun orderView(): String

    // --- customer/profile.html --------------------------------------------------------------

    @Message("Your profile")
    fun profileTitle(): String

    @Message("Name")
    fun profileName(): String

    @Message("Email")
    fun profileEmail(): String

    @Message("Age")
    fun profileAge(): String

    @Message("Not provided. Google does not share a date of birth with us — add one below if you would like the age warning.")
    fun profileAgeUnknown(): String

    @Message("You have confirmed at least once that you meet a film's age rating. That is a confirmation, not a date of birth — staff still check at the door.")
    fun profileAgeAttested(): String

    @Message("Date of birth")
    fun profileDateOfBirth(): String

    @Message("Optional. We use it to warn you when a film's age rating does not match the age you have given us. It never stops you booking, staff check age at the door either way, and leaving it blank removes it.")
    fun profileDateOfBirthHint(): String

    @Message("Your name and email come from the Google account you signed in with. To change either, change it there and sign in again.")
    fun profileGoogleNote(): String

    // --- customer/booking.html --------------------------------------------------------------

    @Message("Date")
    fun bookingDate(): String

    @Message("Change date")
    fun bookingChangeDate(): String

    @Message("Time")
    fun bookingTime(): String

    @Message("Change time")
    fun bookingChangeTime(): String

    /**
     * The iOS install prompt, on both pages that carry a seat map.
     *
     * <p>{@code <strong>} inside the message for {@link #ordersStaffEmpty()}'s reason, and
     * rendered with {@code .raw} for the same one, with one extra: the two words it emphasises
     * are the labels on Safari's own menu, so they have to read exactly as iOS renders them in
     * that language rather than being translated freely.
     */
    @Message("To get seat-availability notifications on iPhone or iPad, tap <strong>Share</strong> then <strong>Add to Home Screen</strong> and open this page from there.")
    fun pushIosHint(): String

    // --- customer/seatmap.html --------------------------------------------------------------

    @Message("Seat map")
    fun seatMapTitle(): String

    // --- customer/seatmapFragment.html ------------------------------------------------------

    @Message("Age classification")
    fun seatAgeClassification(): String

    @Message("Up to {seats} seats per booking.")
    fun seatCapPerBooking(seats: Int): String

    @Message("Up to {seats} seats a day, across every showing.")
    fun seatCapPerDay(seats: Int): String

    /** The showtime viability rule. One message rather than two sentences, because the second half is
     * what explains the first and a translator must be free to join them. */
    @Message("The first booking on a showing must be for at least {seats} seats. This cinema cancels a screening that has not sold {seats} seats within {minutes} minutes of its start time.")
    fun seatViability(seats: Int, minutes: Long): String

    @Message("{amount} per seat")
    fun seatPricePerSeat(amount: String?): String

    @Message("Includes:")
    fun seatPriceIncludes(): String

    @Message("Everyone admitted must meet this rating. Staff check at the door.")
    fun seatAgeDoorNote(): String

    @Message("Your name")
    fun seatCustomerName(): String

    @Message("Phone number")
    fun seatCustomerPhone(): String

    @Message("Hold selected seats")
    fun seatHold(): String

    @Message("Refresh availability")
    fun seatRefresh(): String

    // --- customer/authSheet.html ------------------------------------------------------------

    @Message("Sign in to pick your seats")
    fun authSheetTitle(): String

    @Message("Seats are held against your account, so we need to know who you are before you choose. Signing in takes one tap with Google.")
    fun authSheetBody(): String

    @Message("Sign in with Google")
    fun authSheetSignIn(): String

    @Message("Not now")
    fun authSheetDismiss(): String

    // --- customer/reservation.html and reservationFragment.html ------------------------------

    /** The order detail page. Deliberately not {@link #navYourOrders()}: that names the list,
     * and a page titled the same as the list it was reached from reads as not having moved. */
    @Message("Your reservation")
    fun reservationTitle(): String

    @Message("Quote this code if you contact the cinema about this booking.")
    fun orderQuoteCode(): String

    @Message("Booking status")
    fun orderBookingStatus(): String

    @Message("This booking could not be completed — one of the selected seats was taken first. Any other seats from this attempt have been released.")
    fun orderFailedBanner(): String

    @Message("Seat")
    fun orderTableSeat(): String

    @Message("Status")
    fun orderTableStatus(): String

    @Message("Price")
    fun orderTablePrice(): String

    @Message("Hold expires {when}")
    fun orderHoldExpires(`when`: String?): String

    @Message("Cancel")
    fun orderCancelSeat(): String

    @Message("Contact the cinema to cancel")
    fun orderContactCinemaToCancel(): String

    @Message("Every ticket includes:")
    fun orderTicketIncludes(): String

    // --- customer/reservationFragment.html: the payment card --------------------------------

    @Message("Scan to pay")
    fun payScanToPay(): String

    @Message("expires {when}")
    fun payExpires(`when`: String?): String

    @Message("QRIS payment code")
    fun payQrisAlt(): String

    @Message("Scan with any QRIS-enabled app. Your seats are confirmed automatically once payment settles — this page updates by itself, so there is nothing to press here.")
    fun payQrisHint(): String

    /** The QR is on the screen of the phone that would scan it. Every QRIS app scans from the
     * gallery, and a long press saves a web image on both Android and iOS, so the page needs no
     * download link — which a cross-origin image would not honour anyway. */
    @Message("Paying from this phone? Press and hold the QR to save it, then open it from the gallery in your payment app's scanner.")
    fun payQrisSamePhone(): String

    @Message("Paid")
    fun payPaidHeading(): String

    @Message("{amount} received {when}")
    fun payReceived(amount: String?, `when`: String?): String

    @Message("Refund owed")
    fun payRefundHeading(): String

    @Message("Showing cancelled")
    fun orderShowingCancelled(): String

    @Message("The cinema cancelled this showing, and your seats are released. Nothing more is to be paid.")
    fun orderShowingCancelledNote(): String

    @Message("{amount} is owed back to you. The cinema returns it; ask at the counter if it has not reached you.")
    fun payRefundOwed(amount: String?): String

    @Message("{amount} returned to your payment app on {date}.")
    fun payRefundReturned(amount: String?, date: String?): String

    // the order page's opt-in (order-push.js reads these off #order-push's data-msg-*) and the
    // push itself, rendered in the language stored with the watch (OrderPush).
    @Message("Notify me about this order")
    fun orderPushWatch(): String

    @Message("Stop notifying me about this order")
    fun orderPushUnwatch(): String

    @Message("You'll be notified if the showing is cancelled or a refund is sent.")
    fun orderPushOn(): String

    @Message("Notifications for this order are off.")
    fun orderPushStopped(): String

    @Message("Notifications are blocked for this site, so nothing can be sent.")
    fun orderPushRefused(): String

    @Message("Could not turn on notifications. Try again later.")
    fun orderPushFailed(): String

    @Message("The cinema cancelled the showing of order {code}. Open it to see what comes back to you.")
    fun orderPushCancelledBody(code: String?): String

    @Message("Refund sent")
    fun orderPushRefundSentTitle(): String

    @Message("{amount} was returned to your payment app for order {code}.")
    fun orderPushRefundSentBody(amount: String?, code: String?): String

    @Message("Your refund")
    fun orderPushRefundRefusedTitle(): String

    @Message("The automatic refund of {amount} for order {code} could not be processed. Our staff will return it to you.")
    fun orderPushRefundRefusedBody(amount: String?, code: String?): String

    @Message("(cash, at the counter)")
    fun payCashAtCounter(): String

    @Message("Payment not completed")
    fun payNotCompleted(): String

    /** Two keys rather than one with the status interpolated: "That QR code has Expired." is what
     * interpolating a status LABEL produces, and interpolating the raw column value would put an
     * English word in the Indonesian sentence. */
    @Message("That QR code has expired. If your seats are still held you can start a new payment.")
    fun payQrExpiredNote(): String

    @Message("That QR code has failed. If your seats are still held you can start a new payment.")
    fun payQrFailedNote(): String

    @Message("Try again")
    fun payTryAgain(): String

    @Message("Payment")
    fun payHeading(): String

    @Message("Pay now with QRIS")
    fun payNowQris(): String

    @Message("Or confirm to pay at the counter instead.")
    fun payOrCounter(): String

    @Message("Online payment is not configured for this venue — confirm to pay at the counter.")
    fun payNotConfigured(): String

    @Message("Confirm booking (pay at counter)")
    fun payConfirmAtCounter(): String

    /** Staff only: the counter's two buttons on this page, worded as the desktop counter's own
     * labels for the same two actions. */
    @Message("Cash received")
    fun payCashReceived(): String

    @Message("Show QRIS")
    fun payShowQris(): String

    // --- customer/reservationFragment.html: the ticket --------------------------------

    @Message("Your ticket{#if count > 1}s{/if}")
    fun ticketHeading(count: Int): String

    @Message("Show this at the door. It works offline — no need to sign in again.")
    fun ticketShowAtDoor(): String

    @Message("Seat {seat}")
    fun ticketSeatLabel(seat: String?): String

    @Message("Add to Google Wallet")
    fun ticketAddToWallet(): String

    @Message("Admitted {when}")
    fun ticketAdmitted(`when`: String?): String

    // =======================================================================================
    // admin/ — the staff side.
    //
    // Converted after customer/, and it reads differently on purpose: customer copy is almost
    // all labels, admin copy is explanatory prose written for somebody who has to act on it.
    // That difference shows up as the {@code .raw} messages below. customer/ needed two; admin
    // needs twelve, nearly all of them a paragraph whose emphasis carries the meaning —
    // "<strong>taken the money</strong>", "<em>the day before it</em>". Splitting such a
    // sentence into three keys so the tags can stay in the markup is worse, not better: the
    // fragments are untranslatable on their own, and Indonesian does not put the emphasis in
    // the same place English does. The safety argument is the one seoHead.html's jsonLd.raw
    // already rests on and is unchanged here — the markup is authored in this file and no
    // caller can reach it.
    //
    // NOT here, and the pages are correspondingly still part-English: the validation messages
    // thrown out of the service layer into these pages' {error} banners
    // (HallAdminResource:190, :265, :278, SeatMapPlan, SeatMapCsv, and
    // about twenty more). They are a separate pass because they are a separate problem: most
    // are assembled by string concatenation around a value, several are diagnostics rather
    // than copy ("Malformed rate_rule.rule_definition: …" is for whoever reads the log, not
    // for the counter), and deciding which is which is the work.
    // =======================================================================================

    // --- admin: shared across pages ---------------------------------------------------------

    @Message("All halls")
    fun admAllHalls(): String

    @Message("Cancel")
    fun admCancel(): String

    @Message("Save")
    fun admSave(): String

    @Message("Add")
    fun admAdd(): String

    @Message("Preview")
    fun admPreview(): String

    @Message("Name")
    fun admName(): String

    @Message("Address")
    fun admAddress(): String

    @Message("Date")
    fun admDate(): String

    @Message("Remove")
    fun admRemove(): String

    @Message("Branch")
    fun admBranch(): String

    @Message("Label")
    fun admLabel(): String

    @Message("Row")
    fun admRow(): String

    @Message("Sequence")
    fun admSequence(): String

    @Message("Seats")
    fun admSeats(): String

    // --- admin/halls.html: the tenant admin index -------------------------------------------

    @Message("Branches")
    fun admBranches(): String

    @Message("No branches yet. A single-cinema tenant does not need one — the public site shows the hall list directly. Add branches when you operate in more than one place.")
    fun admNoBranches(): String

    @Message("Add a branch")
    fun admAddBranch(): String

    /** The example is a branch name, and stays in Indonesian in both languages: it is
     * a name, not copy. Same rule as the brand in base.html. */
    @Message("Name (e.g. \"Bioskop Sentral\")")
    fun admBranchName(): String

    @Message("Province")
    fun admProvince(): String

    @Message("Timezone")
    fun admTimezone(): String

    @Message("Create branch")
    fun admCreateBranch(): String

    @Message("Find a booking")
    fun admFindBooking(): String

    @Message("Look a booking up by its code")
    fun admLookUpByCode(): String

    @Message("the eight characters a customer quotes on the phone.")
    fun admLookUpByCodeNote(): String

    @Message("For admitting people at the door, use the admission scan on the showing instead: that is per seat and records who walked in.")
    fun admDoorInsteadNote(): String

    @Message("Pricing calendar")
    fun admPricingCalendar(): String

    @Message("Public holidays")
    fun admPublicHolidays(): String

    @Message("the dates that drive the holiday price band, and the day before each of them.")
    fun admPublicHolidaysNote(): String

    @Message("Only needed by halls on calendar pricing. Set a hall's bands on its own page.")
    fun admPublicHolidaysWhoNeeds(): String

    @Message("Payments")
    fun admPayments(): String

    @Message("Payments needing attention")
    fun admPaymentsNeedingAttention(): String

    @Message("captures held for fraud review, and refunds owed on cancelled bookings.")
    fun admPaymentsNote(): String

    @Message("Usually empty. Everything else about a payment settles itself; these two wait on a person.")
    fun admPaymentsUsuallyEmpty(): String

    /** Above the sales report's form; the CSV's columns stay English (PaymentAttentionResource.salesCsv). */
    @Message("Sales report: payments received per day, cinema, city and channel, as a CSV file. Gross and refund owed are listed side by side, not netted. Days are each hall's local date.")
    fun admSalesReportWhat(): String

    @Message("From")
    fun admSalesFrom(): String

    @Message("To")
    fun admSalesTo(): String

    @Message("Download CSV")
    fun admSalesDownload(): String

    @Message("Films")
    fun admFilms(): String

    @Message("No films yet. A showing can still be created with just a label, but it won't appear under \"Now playing\" until it names a film.")
    fun admNoFilms(): String

    @Message("Add a film")
    fun admAddFilm(): String

    @Message("Title")
    fun admTitle(): String

    @Message("Runtime (minutes)")
    fun admRuntimeMinutes(): String

    /** Minutes, beside a number. Separate from the label above because this one is read as part
     * of a value ("104 min") and the other as a form label. */
    @Message("{minutes} min")
    fun admFilmRuntime(minutes: Int): String

    /** LSF is Lembaga Sensor Film, the Indonesian classification board — an institution's
     * initials, so it is not translated either way. */
    @Message("Age classification (LSF)")
    fun admAgeClassification(): String

    @Message("— not recorded —")
    fun admNotRecorded(): String

    @Message("SU — all ages")
    fun admClassificationSu(): String

    @Message("Synopsis")
    fun admSynopsis(): String

    @Message("Poster URL")
    fun admPosterUrl(): String

    @Message("Trailer URL")
    fun admTrailerUrl(): String

    @Message("Release date")
    fun admReleaseDate(): String

    @Message("Create film")
    fun admCreateFilm(): String

    @Message("Halls")
    fun admHalls(): String

    @Message("Not assigned to a branch.")
    fun admNotAssignedToBranch(): String

    @Message("No halls yet — add one below.")
    fun admNoHalls(): String

    @Message("Add a hall")
    fun admAddHall(): String

    @Message("— none —")
    fun admNoneOption(): String

    @Message("Create hall")
    fun admCreateHall(): String

    // --- admin/hallDetail.html: one hall ----------------------------------------------------

    @Message("Not assigned to a branch, so this hall does not appear under a city on the public site. It is still bookable by its own link.")
    fun admHallNoBranch(): String

    @Message("Move to branch")
    fun admMoveToBranch(): String

    @Message("Assign")
    fun admAssign(): String

    @Message("Seat map ({count} seats)")
    fun admSeatMapHeading(count: Int): String

    @Message("No seats configured yet.")
    fun admNoSeatsYet(): String

    /** .raw, and the longest message in the file. It is the one paragraph a tenant reads before
     * doing the only irreversible-looking thing on this page, and every one of its four emphases
     * marks a word that changes what happens: add vs replace, retired vs deleted. */
    @Message("<strong>Adding seats</strong> is the default: every form below creates what the hall is missing and refuses a seat name it already has. Tick <em>replace the whole map</em> on any of them when a studio has actually been re-seated — then seats the new layout does not name are <strong>retired</strong>: they leave the seat map and stop being sellable, but they keep their bookings and their history, and a later layout can bring them back as the same seat. A seat someone is holding or has paid for cannot be retired.")
    fun admSeatFormsIntro(): String

    @Message("Generate rows")
    fun admGenerateRows(): String

    @Message("The fast way to set up a studio. Shows you the result before creating anything.")
    fun admGenerateRowsNote(): String

    /** The row expression is syntax, not prose — "A-K" means the same in both languages, and the
     * parser reads exactly these characters. Only the sentence around it translates. */
    @Message("Rows (e.g. \"A-K\", or \"A,B,C,E\" to skip one)")
    fun admRowsLabel(): String

    @Message("Seats per row")
    fun admSeatsPerRow(): String

    @Message("Replace the whole map (relayout)")
    fun admReplaceWholeMap(): String

    @Message("Generate an irregular layout")
    fun admGenerateIrregular(): String

    @Message("One line per row. Several ranges on a line leave an aisle between them, and a row you don't list simply isn't created.")
    fun admGenerateIrregularNote(): String

    @Message("Seat plan")
    fun admSeatPlan(): String

    @Message("Import a seat file")
    fun admImportSeatFile(): String

    /** .raw: the three <code> spans are the file formats themselves. A reader matching their
     * spreadsheet against this paragraph is looking for the literal column names, so those stay
     * in the monospace they are written in. */
    @Message("A studio's layout you already have, exported from a spreadsheet. Comma, semicolon or tab separated. Three shapes are read: one seat per line under a <code>row</code>/<code>seat</code> header, one <em>row</em> per line (<code>A,1,2,3</code> — an empty cell is an aisle), or one seat label per line (<code>A1</code>). A <code>studio</code> column filters the file, so a sheet covering the whole branch imports only this hall's lines. Nothing is created until you confirm.")
    fun admImportSeatFileNote(): String

    @Message("Seat file")
    fun admSeatFile(): String

    @Message("Copy another hall's layout")
    fun admCopyLayout(): String

    @Message("Studios in a multiplex usually repeat.")
    fun admCopyLayoutNote(): String

    @Message("Copy from")
    fun admCopyFrom(): String

    @Message("Add a single seat")
    fun admAddSingleSeat(): String

    @Message("Label (e.g. \"A1\")")
    fun admSeatLabelField(): String

    @Message("Row / group key (e.g. \"A\")")
    fun admGroupKeyField(): String

    @Message("Sequence number (for contiguous-block search)")
    fun admSequenceNoField(): String

    @Message("Add seat")
    fun admAddSeat(): String

    @Message("Pricing")
    fun admPricing(): String

    /** .raw: "by calendar" is the answer to "what rate is this hall on", and it is bold because
     * it is the one word on the line that varies. */
    @Message("Current rate: <strong>by calendar</strong>")
    fun admRateByCalendar(): String

    @Message("Band")
    fun admBand(): String

    @Message("Per seat")
    fun admPerSeat(): String

    /** .raw, and the anchor is mid-sentence because the list it points at is the subject of the
     * clause, not a footnote to it. */
    @Message("The public-holiday band also covers <strong>the day before</strong> each date on the <a href=\"/admin/holidays\">public-holiday list</a>. The day of the week is worked out in the branch's timezone, not the server's.")
    fun admHolidayBandNote(): String

    /** .raw for the same reason as admRateByCalendar: the amount is the varying part. */
    @Message("Current flat rate: <strong>{amount}</strong> per seat")
    fun admRateFlat(amount: String?): String

    @Message("Every ticket includes:")
    fun admEveryTicketIncludes(): String

    @Message("No rate configured yet — bookings can't be held until one is set.")
    fun admNoRate(): String

    @Message("Set a flat rate")
    fun admSetFlatRate(): String

    @Message("Flat rate per seat (IDR)")
    fun admFlatRateField(): String

    @Message("Included with every ticket (one per line, optional)")
    fun admInclusionsField(): String

    /** The placeholder is a worked example of a tenant's own copy, so it stays Indonesian in the
     * English page too — an operator typing this field types Indonesian whatever language the
     * chrome is in, because it is printed on a ticket their customer reads. */
    @Message("Termasuk voucher minuman Rp 10.000")
    fun admInclusionsPlaceholder(): String

    @Message("Apply to all {count} halls at {site}")
    fun admApplyToBranch(count: Int, site: String?): String

    @Message("Set flat rate")
    fun admSetFlatRateButton(): String

    @Message("Set calendar pricing")
    fun admSetCalendarPricing(): String

    /** .raw: the emphasised clause is the rule people get wrong — they expect the band to apply
     * on the holiday only. */
    @Message("Price by the day of the week, the way cinemas here publish it. The public-holiday band applies on a listed holiday <em>and on the day before one</em>, and overrides the weekday band — a holiday falling on a Tuesday sells at the holiday price.")
    fun admCalendarPricingNote(): String

    @Message("Mon–Wed (IDR)")
    fun admBandMonWed(): String

    @Message("Thu–Fri (IDR)")
    fun admBandThuFri(): String

    @Message("Sat–Sun (IDR)")
    fun admBandSatSun(): String

    @Message("Public holiday and the day before (IDR)")
    fun admBandHoliday(): String

    @Message("Leave the holiday band blank to charge the Sat–Sun rate on holidays.")
    fun admBandHolidayBlank(): String

    @Message("Showings")
    fun admShowings(): String

    @Message("Starts")
    fun admStarts(): String

    @Message("Status")
    fun admStatus(): String

    @Message("Manage reservations")
    fun admManageReservations(): String

    @Message("Counter sale")
    fun admCounterSale(): String

    @Message("Close sales")
    fun admCloseSales(): String

    @Message("Open for sale")
    fun admOpenForSale(): String

    @Message("Cancel showing")
    fun admCancelShowing(): String

    @Message("No showings yet.")
    fun admNoShowings(): String

    @Message("Create a showing")
    fun admCreateShowingHeading(): String

    @Message("Film")
    fun admFilm(): String

    @Message("— no film (event, private hire, other) —")
    fun admNoFilmOption(): String

    @Message("Picking a film is what makes this showing findable under \"Now playing\". Leave the label blank to use the film's title.")
    fun admPickFilmNote(): String

    @Message("Label (optional when a film is picked)")
    fun admLabelOptional(): String

    @Message("Starts at")
    fun admStartsAt(): String

    @Message("Ends at")
    fun admEndsAt(): String

    @Message("Create showing (opens on-sale immediately)")
    fun admCreateShowing(): String

    // --- admin/showingReservations.html: the seat map, staff side ---------------------------

    @Message("Reservations")
    fun admReservations(): String

    @Message("Scan tickets at the door")
    fun admScanAtDoor(): String

    @Message("Click a held or confirmed seat to release/cancel it — phone cancellations and no-shows.")
    fun admClickSeatToRelease(): String

    @Message("Held (click to release)")
    fun admLegendHeldClick(): String

    @Message("Confirmed (click to cancel)")
    fun admLegendConfirmedClick(): String

    @Message("Cancel {seat}")
    fun admCancelSeat(seat: String?): String

    // --- admin/admission.html + admissionPanel.html: the door --------------------------

    @Message("Admission")
    fun admAdmission(): String

    @Message("Reservations for this showing")
    fun admReservationsForShowing(): String

    @Message("Door")
    fun admDoor(): String

    @Message("Seat {seat}")
    fun admScanSeat(seat: String?): String

    @Message("Admitted {when}")
    fun admScanAdmittedWhen(`when`: String?): String

    @Message("Scan or type the admission code")
    fun admScanFieldLabel(): String

    @Message("Admit")
    fun admAdmit(): String

    @Message("A counter barcode scanner types into this field and presses Enter by itself. The code printed under the customer's barcode can be typed here instead.")
    fun admScannerNote(): String

    // --- AdmissionService.Outcome: what the usher reads first --------------------------------
    //
    // On the bundle rather than in the enum, unlike severity(), and the split is the point: the
    // CSS class is a hook the stylesheet selects on and must stay the literal ok/warn/bad, the
    // headline is copy. Exactly the same split StatusLabels makes for a status badge.

    @Message("Admitted")
    fun doorAdmitted(): String

    @Message("Already admitted")
    fun doorAlreadyAdmitted(): String

    @Message("Wrong showing")
    fun doorWrongShowing(): String

    @Message("Not valid for entry")
    fun doorNotAdmissible(): String

    @Message("Unknown code")
    fun doorUnknownToken(): String

    /** The body under the ADMITTED headline. Nearly the same word as the headline and kept
     * separate anyway: the headline is read from arm's length and the body is read by whoever
     * leans in, so one of the two will eventually want to say more than the other. */
    @Message("Admitted.")
    fun doorAdmittedMessage(): String

    @Message("No code was scanned. Try again, or type the code printed under the barcode.")
    fun doorNoCodeScanned(): String

    @Message("That code is not a ticket for this cinema.")
    fun doorNotThisCinema(): String

    @Message("Already admitted — this ticket was used once already.")
    fun doorUsedAlready(): String

    @Message("This ticket is for a different showing.")
    fun doorDifferentShowing(): String

    /** The showing is named when it can be: an usher who can say "this is the 21:30" sends the
     * customer to the right screen instead of to the counter. */
    @Message("This ticket is for {showing} — a different showing. Do not admit here.")
    fun doorDifferentShowingNamed(showing: String?): String

    @Message("Not paid for. Send them to the counter to pay — the seat is still held.")
    fun doorNotPaid(): String

    @Message("This booking was cancelled. Not valid for entry.")
    fun doorCancelled(): String

    @Message("This booking expired before it was paid for. Not valid for entry.")
    fun doorExpired(): String

    @Message("This screening has already finished.")
    fun doorFinished(): String

    /** The fallback, and it carries the raw status on purpose: it can only be reached by a
     * status a later migration added, and the word nobody translated is the prompt to add it. */
    @Message("This ticket cannot be admitted (status: {status}).")
    fun doorUnknownStatus(status: String?): String

    // --- admin/bookingLookup.html: the counter lookup ---------------------------------------

    @Message("Booking code")
    fun admBookingCode(): String

    @Message("Find booking")
    fun admFindBookingButton(): String

    @Message("Codes are eight characters and contain no O, I, L, S, Z, B, 0 or 1 — if the customer read one of those out, try the character it looks like.")
    fun admBookingCodeAlphabet(): String

    // --- admin/counterSale.html --------------------------------------------------------------

    /** .raw: the placeholder customer's name is a record in the tenant's own data, and bold is
     * what tells staff it is a thing that exists rather than a turn of phrase. */
    @Message("Seats are held for the tenant's <strong>Walk-in Customer</strong> record and recorded as a staff sale. Settle on the next screen — cash, or QRIS.")
    fun admCounterWalkIn(): String

    @Message("Age classification:")
    fun admCounterAgeClassification(): String

    @Message("check ID before selling.")
    fun admCounterCheckId(): String

    @Message("{amount} per seat")
    fun admCounterPerSeat(amount: String?): String

    @Message("Includes:")
    fun admCounterIncludes(): String

    @Message("Hold selected seats")
    fun admHoldSelectedSeats(): String

    @Message("Refresh availability")
    fun admRefreshAvailability(): String

    // --- Cash settlement refusals (PaymentService.CashOutcome) -------------------------------
    //
    // What staff are told when "cash received" records nothing. The web counter answers the
    // same refusal by showing the order, whose payment card already says how it was paid. Both
    // send the reader to the order, because the money question is answered there and nowhere else.

    @Message("This order is already paid, so nothing more was recorded. Check its payment before taking any money.")
    fun admCashAlreadyPaid(): String

    @Message("No seat in this order is still held or sold, so there is nothing to take money for. Start a new sale.")
    fun admCashNothingToPay(): String

    // --- admin/holidays.html ------------------------------------------------------------------

    /** .raw: both emphases name the thing the page exists for, and the second one — the day
     * before — is the half a reader does not expect. */
    @Message("These dates drive the <strong>public-holiday price band</strong> — both the day itself and <strong>the day before it</strong>, which is how most cinemas here price a holiday eve. A date that is not on this list is priced by its day of the week.")
    fun admHolidaysWhat(): String

    /** .raw for the italicised <em>cuti bersama</em>, which stays Indonesian in the English page
     * too: it is the name of a thing decided by a ministerial decree, not a phrase. The nine
     * holiday names beside it are names for the same reason. */
    @Message("Only the five fixed national days are pre-filled. Idul Fitri, Idul Adha, Nyepi, Waisak, Imlek, Isra Mikraj, Maulid, Wafat and Kenaikan Isa Almasih all move each year, and <em>cuti bersama</em> is decided each year too — they come from the SKB 3 Menteri, so add them here once the decree is out. Add your own closure days the same way.")
    fun admHolidaysMoveable(): String

    @Message("Add a date")
    fun admAddDate(): String

    @Message("If two holidays fall on the same day, name both on one line — the price band is the same either way, and one date is one row.")
    fun admTwoHolidaysOneDay(): String

    @Message("This year onward")
    fun admThisYearOnward(): String

    @Message("No dates listed. Halls on calendar pricing will price every day by its day of the week until one is added.")
    fun admNoHolidays(): String

    // --- admin/paymentsNeedingAttention.html ---------------------------------------------------

    @Message("Everything else settles itself — a paid charge confirms its seats, an unpaid one expires, and the reconciliation scan chases the ones whose notification went missing. These two lists are what no job can finish for you.")
    fun admPaymentsIntro(): String

    @Message("Held for fraud review")
    fun admFraudReview(): String

    /** .raw: "taken the money" and "their dashboard" are the two facts that decide what staff do
     * next, and the sentence is long enough that unemphasised they are missed. */
    @Message("Midtrans has <strong>taken the money</strong> and is waiting on an accept-or-deny decision in <strong>their dashboard</strong>. The seats stay held, not confirmed, until it lands — confirming them against a payment that may still be reversed is the outcome worth avoiding.")
    fun admFraudReviewWhat(): String

    @Message("Nothing on this page decides one. Accept or deny it in the Midtrans MAP; this platform picks the outcome up on its next reconciliation pass and confirms or fails the booking then. If a customer is waiting at the counter, a decision there is what unblocks them.")
    fun admFraudReviewAction(): String

    @Message("Waiting since")
    fun admWaitingSince(): String

    @Message("Order")
    fun admOrder(): String

    @Message("Amount")
    fun admAmount(): String

    @Message("Booking")
    fun admBooking(): String

    @Message("View order")
    fun admViewOrder(): String

    @Message("Nothing held for review. This list is usually empty — QRIS has no fraud-review step, so only a card-style capture can land here.")
    fun admNoFraudReview(): String

    @Message("Refunds owed")
    fun admRefundsOwed(): String

    /** .raw: paid is emphasised because the list is exactly the paid ones — an unpaid cancelled
     * booking owes nobody anything and is not here. */
    @Message("A <strong>paid</strong> booking that was later cancelled. The amount below is what the customer is owed back, totalled from the seats actually cancelled at the price each was sold at.")
    fun admRefundsWhat(): String

    @Message("A debt on a QRIS payment is sent back to the buyer's payment app automatically, and leaves this list once Midtrans confirms the refund. A cash debt, or an automatic refund Midtrans refused (listed under Gateway refunds to check, with what to do), is returned from the Midtrans dashboard or at the counter. Paying somebody back at the counter does not remove the row on its own, while a refund Midtrans reports over BI-SNAP clears what it covers.")
    fun admRefundsAction(): String

    @Message("Owed since")
    fun admOwedSince(): String

    @Message("Owed")
    fun admOwed(): String

    @Message("Of")
    fun admOf(): String

    @Message("Reason")
    fun admReason(): String

    @Message("Nothing owed.")
    fun admNothingOwed(): String

    @Message("Gateway refunds to check")
    fun admGatewayRefunds(): String

    @Message("Midtrans reports these payments refunded, but the refund settled no debt recorded here, so their seats are still sold and nothing was changed. Decide what happens to the seats: cancelling a paid seat records a new debt, which this refund may already have paid. Automatic refunds Midtrans refused are listed here too, with what to do; their debt stays under Refunds owed until the money is returned.")
    fun admGatewayRefundsWhat(): String

    @Message("None.")
    fun admNoGatewayRefunds(): String

    @Message("Once you have decided, mark the row resolved and it leaves this list. A later refund notice for the same payment lists it again.")
    fun admGatewayRefundsResolveNote(): String

    @Message("Mark resolved")
    fun admMarkResolved(): String

    // --- admin/seatPlanPreview.html --------------------------------------------------------------

    @Message("Seat plan preview")
    fun admSeatPlanPreview(): String

    @Message("That plan can't be applied")
    fun admPlanRefused(): String

    @Message("Nothing was created. Go back and correct the plan.")
    fun admPlanRefusedNote(): String

    @Message("Back to {hall}")
    fun admBackToHall(hall: String?): String

    @Message("Copy {source}'s layout into {hall}")
    fun admPreviewClone(source: String?, hall: String?): String

    @Message("Import {seats} seats across {rows} rows into {hall}")
    fun admPreviewImport(seats: Int, rows: Int, hall: String?): String

    @Message("Create {seats} seats across {rows} rows")
    fun admPreviewCreate(seats: Int, rows: Int): String

    @Message("These seats already exist in this hall and would be duplicated:")
    fun admPreviewConflicts(): String

    @Message("Nothing will be created. Remove those rows from the plan, or add only the rows this hall is missing.")
    fun admPreviewConflictsNote(): String

    @Message("{seats} seats across {rows} rows. Nothing has been created yet.")
    fun admPreviewSummary(seats: Int, rows: Int): String

    @Message("Create these {seats} seats")
    fun admPreviewApply(seats: Int): String

    // --- admin/seatPlanRelayout.html ---------------------------------------------------------

    @Message("Relayout")
    fun admRelayout(): String

    @Message("Relayout {hall}")
    fun admRelayoutHeading(hall: String?): String

    @Message("That layout can't be applied")
    fun admRelayoutRefused(): String

    @Message("Nothing was changed. Go back and correct the layout.")
    fun admRelayoutRefusedNote(): String

    @Message("Re-seat {hall} to match {source}")
    fun admRelayoutClone(hall: String?, source: String?): String

    @Message("{hall} would have {seats} seats")
    fun admRelayoutCount(hall: String?, seats: Int): String

    @Message("These seats are booked and can't be retired:")
    fun admRelayoutBlocked(): String

    @Message("Cancel or move those bookings first, then run this again. Nothing has been changed.")
    fun admRelayoutBlockedNote(): String

    @Message("This layout is exactly the hall you already have — nothing to change.")
    fun admRelayoutNoop(): String

    @Message("What happens")
    fun admRelayoutWhatHappens(): String

    @Message("Stay exactly as they are")
    fun admRelayoutUnchanged(): String

    @Message("Keep their name and move")
    fun admRelayoutMoving(): String

    @Message("Stay put, wheelchair mark changes")
    fun admRelayoutRemarking(): String

    @Message("Come back from retirement")
    fun admRelayoutReinstating(): String

    @Message("Are created")
    fun admRelayoutCreating(): String

    @Message("Are retired")
    fun admRelayoutRetiring(): String

    @Message("Retired")
    fun admRelayoutRetiredHeading(): String

    /** .raw: <strong>not</strong> deleted is the whole reassurance, and an unemphasised "not" in
     * a long sentence is the one word a worried reader skips. */
    @Message("These leave the seat map and stop being sellable. They are <strong>not</strong> deleted: past bookings still name them, and a later layout can bring them back as the same seat.")
    fun admRelayoutRetiredNote(): String

    @Message("Created")
    fun admRelayoutCreatedHeading(): String

    @Message("Back from retirement")
    fun admRelayoutReinstatedHeading(): String

    @Message("The same seats as before, not new ones with the same names.")
    fun admRelayoutReinstatedNote(): String

    @Message("Moved")
    fun admRelayoutMovedHeading(): String

    @Message("These keep their bookings — a seat that keeps its name keeps its identity.")
    fun admRelayoutMovedNote(): String

    @Message("Apply this layout")
    fun admRelayoutApply(): String

    // --- admin/staff.html ----------------------------------------------------------------

    /** .raw: Managers is bold because the paragraph's whole job is to say what the second level
     * adds, and the reader is scanning for it. */
    @Message("Everyone listed here can sell at the counter, scan tickets at the door and cancel a booking over the phone. <strong>Managers</strong> can additionally add and remove people on this page.")
    fun admStaffLevels(): String

    @Message("Add someone by the Google address they will sign in with — they do not need an account here first, and nothing is sent to them: the address is recognised the first time they sign in. Removing someone deactivates them rather than deleting, so the record of what they did at the till stays attributable.")
    fun admStaffHowToAdd(): String

    @Message("Add a staff member")
    fun admAddStaff(): String

    @Message("Google email address")
    fun admStaffEmail(): String

    @Message("Must be an address that is not already registered here — including as a customer. Somebody who buys tickets with their personal address needs a separate one for staff access.")
    fun admStaffEmailNote(): String

    @Message("Current staff")
    fun admCurrentStaff(): String

    @Message("Email")
    fun admEmail(): String

    @Message("Level")
    fun admLevel(): String

    @Message("Signed in")
    fun admSignedIn(): String

    @Message("you")
    fun admYou(): String

    @Message("Manager")
    fun admManager(): String

    @Message("Staff")
    fun admStaffLevel(): String

    @Message("deactivated")
    fun admDeactivatedBadge(): String

    @Message("never")
    fun admNever(): String

    @Message("Remove manager")
    fun admRemoveManager(): String

    @Message("Make manager")
    fun admMakeManager(): String

    @Message("Deactivate")
    fun admDeactivate(): String

    @Message("Reactivate")
    fun admReactivate(): String

    @Message("No staff listed.")
    fun admNoStaff(): String

    // =======================================================================================
    // BookingService's hold refusals, and ShowtimeViability's.
    //
    // These render in the seat map's .error-banner, on a page that is otherwise entirely
    // Indonesian — which is what made them worth doing before the rest of the service layer.
    // A bundle can reach them because BookingService is a bean: it injects LanguagePreference
    // exactly as ShowtimeResource does.
    //
    // WHAT IS NOT HERE, deliberately: the NotFoundException and IllegalStateException messages
    // in the same class ("Showing not found: <uuid>", "Authenticated customer is linked to
    // customer <uuid>"). Those are diagnostics — they name a primary key and they are read in a
    // log by whoever is debugging, not by a customer looking at a seat map. Translating a
    // stack-trace message would make it harder to search for and no easier to read.
    // =======================================================================================

    @Message("Select at least one seat.")
    fun holdSelectAtLeastOne(): String

    /** The per-booking seat cap. Three numbers rather than one because the useful half of the
     * refusal is the arithmetic the customer would otherwise have to do at the seat map. */
    @Message("You can hold up to {limit} seats in one booking. You selected {selected} — untick {untick} and try again.")
    fun holdPerHoldCap(limit: Int, selected: Int, untick: Int): String

    /** The status arrives as a label from {@code StatusLabels}, not as the raw column value.
     * Unlike payQrExpiredNote — which needed two whole keys because the status sat mid-clause
     * and "That QR code has Expired." is what interpolating there produces — this one is a
     * parenthetical, where a label reads correctly in both languages. */
    @Message("This showing is not currently open for booking (status: {status}).")
    fun holdShowingNotOpen(status: String?): String

    /** A tenant suspended for non-payment, refused at the hold on every channel. Says nothing about
     * why: the buyer is not a party to the cinema's bill. */
    @Message("This cinema is not taking new bookings at the moment. Tickets already paid for are still valid.")
    fun holdTenantSuspended(): String

    @Message("One of the seats you selected is no longer part of this hall. Reload the seat map and choose again — nothing was booked.")
    fun holdSeatNotInHall(): String

    @Message("One of the selected seats was just taken by someone else.")
    fun holdSeatJustTaken(): String

    /** Names the rating, because the customer is looking at a seat map and the refusal has to
     * explain itself: "this film is 17+" is the fact, and the checkbox is the remedy. The rating
     * itself is LSF's vocabulary and is interpolated untranslated. */
    @Message("This film is rated {rating}. Tick the checkbox confirming you are old enough, then try again.")
    fun holdAgeGate(rating: String?): String

    /** "1 seat" / "3 seats". A refusal that reads "You already have 1 seats" undermines the one
     * thing it has to do, which is be believed. Indonesian has no plural marker, so msg_id
     * carries the same string for both — the {#if} is the English half's problem alone. */
    @Message("{count} seat{#if count > 1}s{/if}")
    fun seatCount(count: Int): String

    /** The per-day seat cap, in two whole sentences rather than a shared head and two tails: the
     * head names a day and a limit, and Indonesian does not put either where English does, so
     * a message that stopped mid-sentence could not be translated at all. */
    @Message("You already have {held} for showings on {day}, and the limit is {limit} a day. Release one of those, or pick a showing on another day.")
    fun holdDayCapFull(held: String?, day: String?, limit: Int): String

    @Message("You already have {held} for showings on {day}, and the limit is {limit} a day. You can add {remaining} more — untick {untick} and try again.")
    fun holdDayCapPartial(held: String?, day: String?, limit: Int, remaining: Int, untick: Int): String

    /** The minimum-first-booking rule. Built in one place so the refusal and the seat map's up-front notice
     * cannot describe different rules — ShowtimeViability.refusal is the only caller. */
    @Message("This showing has no seats sold yet, and the first booking has to be for at least {minimum} seats — the cinema cancels a screening that has not sold {minimum} within {grace} minutes of its start time. You selected {requested}.")
    fun holdFirstBuyerMinimum(minimum: Int, grace: Long, requested: Int): String

    @Message("This booking has been paid for and can only be cancelled by cinema staff. Please contact the cinema.")
    fun cancelPaidByStaffOnly(): String

    // --- /profile's date-of-birth banner ----------------------------------------------------
    //
    // Three strings from two classes, all rendering into the SAME error banner on
    // customer/profile.html — one of the seventeen templates that is otherwise entirely
    // translated. Only the first was planned, as "the genuinely trivial one"; the other
    // two were found beside it and share its banner, so leaving them would have left the page
    // half-converted in a way a reader would notice and a grep would not.

    /** Reachable only from a caller that is not a browser: {@code <input type="date">} submits
     * ISO-8601 or nothing. Handled rather than assumed away, and translated for the same
     * reason it is handled. */
    @Message("Enter a date as YYYY-MM-DD.")
    fun profileDateFormat(): String

    @Message("That date is in the future.")
    fun profileDateInFuture(): String

    @Message("Please check that date — it is more than {years} years ago.")
    fun profileDateTooOld(years: Int): String

    // --- The age attestation, and the advisory above it ------------------------------------
    //
    // THESE TWO ARE NOT ORDINARY COPY, and the difference is worth reading before touching them.
    // The attestation sentence is hashed into consent_record.policy_text_hash, so the string
    // below is not merely displayed — it is the thing a stored consent row pins down. An edit to
    // the wording here makes every existing row stop matching, which is the property that makes
    // those rows evidence rather than decoration.
    //
    // That is also why translating it was held open as a legal question rather than done with the rest of
    // customer/: an Indonesian attestation hashes to a different value than an English one, so
    // one classification stopped having one hash. The decision taken (2026-09-17) is that this is
    // correct and the previous behaviour was not — the record must say what the person actually
    // read, and hashing an English sentence a customer never saw records an agreement that did
    // not happen. What it costs is that a hash is no longer resolvable on its own, so
    // Gate.consentPurpose now carries the language tag beside the classification.
    //
    // Consequence for anybody editing the Indonesian below: changing it is a data change, not a
    // copy change. Every consent row written before the edit stops matching the sentence the file
    // now holds, exactly as it would in English. Do not "improve" the phrasing casually.

    @Message("I confirm that I am {age} years of age or older.")
    fun ageStatementMinimum(age: Int): String

    /** The unreachable branch, kept because the failure direction matters: the CHECK constraint
     * permits only the four LSF values, but gating on a rating we cannot name the age for costs
     * one tick, where waving it through would sell a restricted film with no attestation. */
    @Message("I confirm that I meet the age requirement for this film's {classification} rating.")
    fun ageStatementUnknownRating(classification: String?): String

    /** Names both numbers. "You may be too young for this film" without them reads as a guess
     * about the customer rather than arithmetic on what they told us, and a customer who thinks
     * the page is guessing has no reason to correct the date it guessed from. Not hashed — this
     * one warns and records nothing, so it is an ordinary message. */
    @Message("Your profile says you will be {age} on the day of this showing, and this film is rated {classification}. You can still book — staff check age at the door.")
    fun ageAdvisoryMessage(age: Int, classification: String?): String

    // =======================================================================================
    // Copy for the two hand-written scripts — seatmap-selection.js and seatmap-push.js.
    //
    // A bundle cannot reach a .js file, so these are rendered into data-msg-* attributes on the
    // elements the scripts already read, and the scripts take their sentences from the DOM.
    // The template is the only layer that knows the language, exactly as it is already the only
    // layer that knows the sign-in URL (data-signin-url, seatmapFragment.html).
    //
    // WHY %seats% AND %status% RATHER THAN QUTE'S OWN {seats}. These messages are patterns that
    // JavaScript fills in, not messages Qute resolves: the value is not known until a seat is
    // lost or a request fails. A {…} in a @Message is a Qute expression and the build demands a
    // matching parameter, so the placeholder has to be something Qute does not read. Percent
    // signs are ignored by the parser, they survive attribute escaping untouched, and a
    // translator can see them.
    //
    // WHY SINGULAR AND PLURAL ARE TWO KEYS. English needs "is"/"are" and "it"/"they"; the script
    // used to choose between them with a ternary on names.length, which is English grammar
    // compiled into a script that now serves two languages. Indonesian marks neither, so msg_id
    // carries the same sentence twice — which looks redundant and is the honest encoding: the
    // distinction exists in one language and not the other. Same shape as seatCount above.
    // =======================================================================================

    @Message("%seats% was just taken by someone else.")
    fun jsSeatTakenOne(): String

    @Message("%seats% were just taken by someone else.")
    fun jsSeatTakenMany(): String

    /** Held and confirmed are different promises, and saying "taken" about both loses the one
     * that matters to a customer deciding what to do next: a held seat is somebody's unpaid
     * claim and comes back if they do not pay, a confirmed one is gone. */
    @Message("%seats% is being held by someone else — it may free up shortly.")
    fun jsSeatHeldOne(): String

    @Message("%seats% are being held by someone else — they may free up shortly.")
    fun jsSeatHeldMany(): String

    /** The conjunction before the last name in "A1, A2 and A3". A key of its own because the
     * list is assembled in the browser from seat labels that are tenant data. */
    @Message("and")
    fun jsSeatListAnd(): String

    @Message("Your other seats are still selected.")
    fun jsOtherSeatsStillSelected(): String

    /** Not "you are not logged in" — they were, which is why they got this far with a form full
     * of seats. The expiry is the thing that surprises them. */
    @Message("Your session expired, so those seats were not held. Your selection is still here.")
    fun jsHoldSessionExpired(): String

    @Message("Sign in again")
    fun jsSignInAgain(): String

    @Message("This account is not allowed to hold seats. Use the account you book with — your selection is unchanged.")
    fun jsHoldNotPermitted(): String

    @Message("Those seats could not be held (server error %status%). Your selection is unchanged — please try again.")
    fun jsHoldServerError(): String

    @Message("Could not reach the server, so those seats were not held. Your selection is unchanged — please try again.")
    fun jsHoldUnreachable(): String

    // --- seatmap-push.js: the one status line under the seat map -----------------------------

    @Message("Notifications off — the map still refreshes automatically.")
    fun jsPushOff(): String

    @Message("Notifications unavailable — the map still refreshes automatically.")
    fun jsPushUnavailable(): String

    @Message("Could not register for updates — the map still refreshes automatically.")
    fun jsPushNotRegistered(): String

    @Message("You'll be notified when seats for this showing change.")
    fun jsPushSubscribed(): String

    // --- customer/chat.html, customer/chatTurn.html ---------------------------------------------
    // The assistant's own answers are the model's text, rendered escaped; these are the page's.

    @Message("Booking assistant")
    fun chatTitle(): String

    @Message("Ask in your own words, for example: \"Two seats for The Last Ferry in Bandung tomorrow night, in the middle.\" The assistant finds the showing and suggests seats; you check them on the seat map and hold them yourself. It never books or pays for you.")
    fun chatIntro(): String

    /** The cross-border notice: the model runs abroad,
     * so the reader is told before typing, not after. */
    @Message("Demo service: what you type here is sent to {host} to write the answer, and may be logged there. Do not type your name, phone number, email address or anything else personal.")
    fun chatPrivacy(host: String): String

    @Message("Up to {cap} messages a day.")
    fun chatDailyCap(cap: Int): String

    @Message("Your message")
    fun chatMessageLabel(): String

    @Message("Send")
    fun chatSend(): String

    @Message("Looking it up… a seat suggestion can take a minute or two.")
    fun chatWaiting(): String

    @Message("You")
    fun chatYou(): String

    @Message("Assistant")
    fun chatAssistant(): String

    @Message("Open the seat map with {seats} ticked")
    fun chatOpenSeatMap(seats: String): String

    @Message("Please type a question first.")
    fun chatEmpty(): String

    @Message("You have used today's {cap} messages. The assistant is available again tomorrow; the cinema and film pages work as usual.")
    fun chatCapReached(cap: Int): String

    @Message("The assistant is not available right now. Please try again in a minute, or find your showing on the cinema and film pages.")
    fun chatUnavailable(): String
}
