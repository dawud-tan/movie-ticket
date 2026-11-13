package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Payment
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.FilmRepository
import com.protek.booking.repository.PaymentRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourceUnitRepository
import com.protek.booking.web.ComplaintContact
import io.quarkus.mailer.Mail
import io.quarkus.mailer.Mailer
import io.quarkus.runtime.LaunchMode
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.context.ManagedExecutor
import org.jboss.logging.Logger
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Optional
import java.util.UUID

/**
 * The buyer's email: a showing cancelled, a refund
 * sent, a refund refused. Plain text, to the order's customer, whose address Google verified at
 * sign-in. A counter sale belongs to the walk-in placeholder and gets none.
 *
 * **One message, Indonesian then English.** An order stores no language, and the cancel is
 * pressed by staff, not by the reader, so there is no reader's preference to follow. Written here, not in the message
 * bundle: a mail is not a page, and both languages go in every message.
 *
 * **After commit, fire-and-forget, and off until configured**, like the push: a send failure is a
 * log line, and with no SMTP host and sender (`SMTP_HOST`, `MAIL_FROM`) nothing is sent and every
 * booking works as before. Dev and test use Quarkus's mock mailbox.
 */
@ApplicationScoped
class BuyerMail {

    @Inject
    lateinit var mailer: Mailer

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var executor: ManagedExecutor

    @Inject
    lateinit var groupRepo: ReservationGroupRepository

    @Inject
    lateinit var customerRepo: CustomerRepository

    @Inject
    lateinit var reservationRepo: ReservationRepository

    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository

    @Inject
    lateinit var poolRepo: ResourcePoolRepository

    @Inject
    lateinit var unitRepo: ResourceUnitRepository

    @Inject
    lateinit var filmRepo: FilmRepository

    @Inject
    lateinit var paymentRepo: PaymentRepository

    @Inject
    lateinit var complaint: ComplaintContact

    @ConfigProperty(name = "quarkus.mailer.host")
    lateinit var host: String

    @ConfigProperty(name = "quarkus.mailer.from")
    lateinit var from: String

    @ConfigProperty(name = "quarkus.mailer.mock")
    lateinit var mock: Optional<Boolean>

    /** Quarkus mocks by default in dev and test, so the mailbox there is the mock one. */
    fun enabled(): Boolean = mock.orElse(LaunchMode.current().isDevOrTest) ||
        (host != UNSET && from != UNSET)

    /** Each order on a showing staff just cancelled; [owedBack] is what each order's paid
     * payment took as a debt in that cancel (absent when nothing was paid). */
    fun showingCancelled(tenantId: UUID, owedBack: Map<UUID, BigDecimal?>) =
        later("showing cancelled") {
            for ((groupId, owed) in owedBack) {
                val order = order(tenantId, groupId) ?: continue
                val payment = owed?.let { order.paid }
                val (idOwed, enOwed) = when {
                    payment == null || owed.signum() <= 0 ->
                        "Tidak ada pembayaran yang perlu dikembalikan." to "Nothing was paid, so nothing is owed back."
                    Payment.CHANNEL_QRIS == payment.paymentChannel && payment.qrisReferenceNo != null ->
                        "${money(owed)} dikembalikan otomatis ke aplikasi pembayaran yang Anda pakai. Kami kirim email lagi setelah terkirim." to
                            "${money(owed)} is being returned automatically to the payment app you paid with. We will email you again once it is sent."
                    else ->
                        "${money(owed)} dikembalikan tunai oleh staf kami di loket." to
                            "${money(owed)} will be returned to you in cash by our staff at the counter."
                }
                send(order.email, "Pertunjukan dibatalkan / Showing cancelled: ${order.film}",
                    "Pertunjukan berikut dibatalkan oleh bioskop.\n\n${order.details(ID)}\n\n$idOwed",
                    "The cinema cancelled this showing.\n\n${order.details(EN)}\n\n$enOwed")
            }
        }

    /** Called by `RefundService`, already off the request thread. */
    fun refundSent(tenantId: UUID, groupId: UUID, amount: BigDecimal, at: Instant) = now("refund sent") {
        val order = order(tenantId, groupId) ?: return@now
        send(order.email, "Dana dikembalikan / Refund sent: ${money(amount)}",
            "${money(amount)} sudah dikembalikan ke aplikasi pembayaran Anda pada ${date(at, order.zone, ID)}. " +
                "Lama dana tampil tergantung aplikasi pembayaran Anda.\n\n${order.details(ID)}",
            "${money(amount)} was returned to your payment app on ${date(at, order.zone, EN)}. " +
                "How soon it shows depends on your payment app.\n\n${order.details(EN)}")
    }

    fun refundRefused(tenantId: UUID, groupId: UUID, amount: BigDecimal) = now("refund refused") {
        val order = order(tenantId, groupId) ?: return@now
        val phone = complaint.phoneOf(tenantId)
        send(order.email, "Pengembalian dana / Your refund: ${money(amount)}",
            "Pengembalian otomatis ${money(amount)} ke aplikasi pembayaran Anda tidak dapat diproses. Staf kami akan " +
                "mengembalikannya kepada Anda." + (phone?.let { " Pertanyaan atau keluhan: $it." }.orEmpty()) +
                "\n\n${order.details(ID)}",
            "The automatic refund of ${money(amount)} to your payment app could not be processed. Our staff will " +
                "return it to you." + (phone?.let { " Questions or complaints: $it." }.orEmpty()) +
                "\n\n${order.details(EN)}")
    }

    private class Order(val email: String, val code: String?, val film: String, val hall: String?,
                        val startsAt: Instant?, val zone: ZoneId, val seats: List<String>, val paid: Payment?) {
        fun details(locale: Locale): String {
            val id = locale == ID
            return "Film: " + film + "\n" +
                (if (id) "Jadwal: " else "Showing: ") + (startsAt?.let { date(it, zone, locale) } ?: "-") + "\n" +
                (if (id) "Studio: " else "Hall: ") + (hall ?: "-") + "\n" +
                (if (id) "Kursi: " else "Seats: ") + seats.joinToString(", ") + "\n" +
                (if (id) "Kode pemesanan: " else "Booking code: ") + (code ?: "-")
        }
    }

    /** Null when nobody is to be written to: a walk-in counter sale, or no address on file. */
    private fun order(tenantId: UUID, groupId: UUID): Order? = db.inTenantTransaction(tenantId) { c ->
        val group = groupRepo.findById(c, tenantId, groupId).orElse(null) ?: return@inTenantTransaction null
        val customer = customerRepo.findById(c, tenantId, group.customerId).orElse(null)
        val email = customer?.email?.takeIf { it.isNotBlank() && !customer.walkInPlaceholder }
            ?: return@inTenantTransaction null
        val reservations = reservationRepo.findByGroup(c, tenantId, groupId)
        val showing = reservations.firstOrNull()?.let { sessionRepo.findById(c, tenantId, it.resourcePoolSessionId).orElse(null) }
        val hall = poolRepo.findById(c, tenantId, group.resourcePoolId).orElse(null)
        val film = showing?.filmId?.let { filmRepo.findById(c, tenantId, it).orElse(null)?.title } ?: showing?.label ?: "-"
        val seats = reservations.mapNotNull { unitRepo.findById(c, tenantId, it.resourceUnitId).orElse(null)?.label }
        val paid = paymentRepo.findByReservationGroup(c, tenantId, groupId).firstOrNull { Payment.STATUS_PAID == it.status }
        Order(email, group.bookingCode, film, hall?.name, showing?.startsAt,
            runCatching { ZoneId.of(hall?.timezone) }.getOrDefault(JAKARTA), seats, paid)
    }

    private fun send(to: String, subject: String, indonesian: String, english: String) {
        mailer.send(Mail.withText(to, subject, "$indonesian\n\n---\n\n$english\n"))
        LOG.infof("Mailed the buyer (%s): %s", to, subject)
    }

    private fun later(what: String, body: () -> Unit) {
        if (!enabled()) return
        executor.execute { now(what, body) }
    }

    private fun now(what: String, body: () -> Unit) {
        if (!enabled()) return
        try {
            body()
        } catch (e: RuntimeException) {
            // A committed booking or refund is never undone by a mail that did not go.
            LOG.warnf(e, "Could not mail the buyer (%s)", what)
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(BuyerMail::class.java)
        private const val UNSET = "__unset__"
        private val ID: Locale = Locale.forLanguageTag("id")
        private val EN: Locale = Locale.ENGLISH
        private val JAKARTA: ZoneId = ZoneId.of("Asia/Jakarta")

        private fun money(amount: BigDecimal): String = com.protek.booking.web.TemplateExtensions.fmtMoney(amount)

        /** In the hall's zone, as the order page shows a showing; WIB named as the site names it. */
        private fun date(at: Instant, zone: ZoneId, locale: Locale): String =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", locale).withZone(zone).format(at) +
                if (zone == JAKARTA) " WIB" else " $zone"
    }
}
