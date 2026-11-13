package com.protek.booking.web.admin

import com.protek.booking.domain.Payment
import com.protek.booking.payment.PaymentService
import com.protek.booking.security.Roles
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.PathParam
import jakarta.ws.rs.Produces
import jakarta.ws.rs.QueryParam
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The two payment states that need a person rather than a scheduled job: a capture held for
 * manual fraud review and a settled payment still owing money back.
 *
 * <p><b>Why a screen at all.</b> Both were already recorded correctly and neither was reachable.
 * The fraud hold existed only as a {@code LOG.warnf} on a server nobody tails, which left
 * open whether a challenged payment should surface to staff rather than sitting
 * in a log line. The refund obligation had a repository query whose own comment called it "the
 * one a staff refund screen would be built on" and no caller anywhere. In both cases the money is
 * real and the only thing missing is somewhere for it to be seen.
 *
 * <p><b>Both queues on one page, deliberately.</b> A fraud hold is close to moot on this
 * deployment — QRIS under BI-SNAP has no fraud-review state at all, so only the legacy rollback
 * path can produce one — and a page that is structurally always empty is a page that gets deleted
 * as dead. What the two share is the shape that matters operationally: money in an unresolved
 * position that no sweep will resolve. One page staff can learn to check beats two they will not.
 *
 * <p><b>Read-only, and that is the whole design.</b> Neither obligation is settled here. (The one
 * write, the gateway-refund queue's "Mark resolved", settles nothing either: it records that staff
 * judged a row, and the flag stays.) A fraud
 * challenge is accepted or denied in Midtrans's own dashboard — this platform must not hold a
 * second opinion about a decision Midtrans owns, or a stale local copy becomes a confirmed seat —
 * and there is still no refund path to Midtrans at all, so a "Refund" button here could only
 * lie. This page tells staff what is outstanding and where to go; it does not pretend to act.
 */
@Path("/admin/payments")
@RunOnVirtualThread
// Authorization rule 1, as every admin resource: the nav link is UX, this is the boundary.
@RolesAllowed(Roles.TENANT_STAFF)
class PaymentAttentionResource {

    @CheckedTemplate(basePath = "admin")
    object Templates {
        @JvmStatic
        external fun paymentsNeedingAttention(
            fraudReview: List<Payment>?,
            refundsDue: List<Payment>?,
            gatewayRefunds: List<Payment>?,
        ): TemplateInstance
    }

    @Inject
    lateinit var paymentService: PaymentService

    @GET
    @Produces(MediaType.TEXT_HTML)
    fun list(): TemplateInstance =
        Templates.paymentsNeedingAttention(
            paymentService.paymentsUnderFraudReview(), paymentService.refundsDue(),
            paymentService.gatewayRefundsFlagged())

    /** The gateway-refund queue's "Mark resolved". Back to the page either way: a second click on a
     * row already resolved (or another tenant's id, which RLS hides) has nothing left to do. */
    @POST
    @Path("/{paymentId}/gateway-refund-resolved")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    fun resolveGatewayRefund(@PathParam("paymentId") paymentId: UUID): Response {
        paymentService.resolveGatewayRefund(paymentId)
        return Response.seeOther(URI.create("/admin/payments")).build()
    }

    /**
     * The sales report, from the form on `/admin` (`halls.html`): what a cinema files PBJT from
     * (UU 1/2022 Pasal 55–58), which nothing produced before.
     * A CSV and not a page because it goes to whoever does the tax, into a spreadsheet. The column
     * names stay English in both languages, as identifiers a spreadsheet can be keyed on, the way
     * `CalendarPricing`'s band labels are. Inclusive dates, at most a year: the form is the only
     * caller, and an unbounded range is one GET away from a full scan of every payment.
     */
    @GET
    @Path("/sales.csv")
    @Produces("text/csv")
    fun salesCsv(@QueryParam("from") from: String?, @QueryParam("to") to: String?): Response {
        val start = parseDate(from)
        val end = parseDate(to)
        if (end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 366) {
            throw BadRequestException("'to' must be on or after 'from', and at most 366 days after it")
        }
        val csv = StringBuilder("date,cinema,city,channel,payments,gross_idr,refund_owed_idr\r\n")
        for (l in paymentService.salesByDay(start, end)) {
            csv.append(l.day).append(',').append(csvField(l.cinema)).append(',').append(csvField(l.city ?: ""))
                .append(',').append(l.channel).append(',').append(l.payments)
                .append(',').append(l.gross.toPlainString()).append(',').append(l.refundOwed.toPlainString())
                .append("\r\n")
        }
        return Response.ok(csv.toString())
            .header("Content-Disposition", "attachment; filename=\"sales-$start-$end.csv\"").build()
    }

    private fun parseDate(s: String?): LocalDate =
        try {
            LocalDate.parse(s ?: "")
        } catch (e: DateTimeParseException) {
            throw BadRequestException("Dates are YYYY-MM-DD")
        }

    private companion object {
        /** RFC 4180 quoting, and a leading `'` on what a spreadsheet would run as a formula: a
         * branch name is staff-typed text. */
        fun csvField(s: String): String {
            val safe = if (s.isNotEmpty() && s[0] in "=+-@") "'$s" else s
            return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
                "\"" + safe.replace("\"", "\"\"") + "\"" else safe
        }
    }
}
