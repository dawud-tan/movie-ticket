package com.protek.booking.web.dto

import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import java.time.ZoneId

/** Composite view for the reservation-detail page — spans several tables, so it's a
 * page-specific composition rather than something a repository would return. */
@JvmRecord
data class ReservationGroupDetail(
    val group: ReservationGroup?,
    val hall: ResourcePool?,
    val showing: ResourcePoolSession?,
    val lines: List<ReservationLine>?,
    /** What each ticket includes at the price already charged — a cinema may bundle a drink
     * voucher into every band. Read from the rate rule in force when the
     * booking was taken, not the current one, so a ticket cannot be restated later. */
    val inclusions: List<String>?,
    /**
     * The zone this showing's times mean anything in — the *branch's*, via
     * `CalendarPricing.zoneFor`, which is the single place that precedence is
     * decided. Resolved in the same transaction as the rest of this record rather
     * than by whoever renders it: a Google Wallet pass groups by local day and prints a
     * local time, so a UTC instant would put a late Makassar showing on the wrong day, and
     * re-deriving the rule here would be a second copy of it that must not exist.
     */
    val zone: ZoneId?,
) {
    @JvmRecord
    data class ReservationLine(val reservation: Reservation?, val seat: ResourceUnit?)

    /**
     * The seats that currently have a ticket worth showing at a door — the confirmed ones.
     *
     * Derived here rather than filtered in the template so the page can ask one question,
     * "are there any tickets", and get an answer that cannot disagree with what it then renders.
     * The alternative — wrapping the card in a check on `group.status` and filtering inside
     * it — renders an empty card in two states that really happen: a group whose seats have been
     * swept to `completed` after the screening, and a confirmed group whose only seat was
     * later cancelled by staff.
     *
     * `confirmed` and nothing else, deliberately. A `held` seat is unpaid and
     * showing a barcode for it would promise entry the scan endpoint will refuse; `completed`
     * means the screening has finished and the ticket is spent.
     *
     * Still `Stream.toList()`, so the list stays unmodifiable as the Java one was.
     */
    fun admissionLines(): List<ReservationLine> =
        lines!!.stream()
            .filter { line -> "confirmed" == line.reservation!!.status }
            .toList()
}
