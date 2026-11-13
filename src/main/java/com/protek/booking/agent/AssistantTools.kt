package com.protek.booking.agent

import com.lukelast.ktoon.Ktoon
import com.protek.booking.web.api.AppApiResource
import dev.langchain4j.agent.tool.P
import dev.langchain4j.agent.tool.Tool
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.RequestScoped
import jakarta.inject.Inject
import jakarta.ws.rs.WebApplicationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The seats the model proposed during this request, read by `ChatResource` to build the
 * seat-map link. Request-scoped: one chat message, one proposal at most. */
@RequestScoped
class ProposalHolder {
    var proposal: Proposal? = null
}

data class Proposal(val hallId: UUID, val sessionId: UUID, val seatIds: List<UUID>, val labels: List<String>)

/**
 * The booking assistant's tools: six, with the names, Indonesian descriptions and result
 * shapes a tool-calling benchmark measured, each one call into the
 * [AppApiResource] method `/api/app` answers with. So they are reads of public data only, the
 * tenant comes from the service exactly as for an anonymous browser, and no identity reaches
 * them. There is deliberately no hold, cancel, confirm or pay tool: [proposeSeats] only records,
 * and the hold stays the customer's own click on the seat map.
 * An unknown id answers as `/api/app`'s refusal does, not as an exception, so the model
 * sees what the endpoint would say and can look the right id up.
 */
@ApplicationScoped
class AssistantTools {

    @Inject
    lateinit var api: AppApiResource

    @Inject
    lateinit var holder: ProposalHolder

    @Serializable
    data class Showing(
        val sessionId: String?, val hallId: String?, val hallName: String?, val siteName: String?,
        val startsAt: String?, val endsAt: String?, val status: String?, val zone: String?,
    )

    /** `showings` without a date: one row per day and hall, start times space-separated, no IDs. */
    @Serializable
    data class ShowingDay(val date: String?, val hallName: String?, val times: String)

    /** One row's free seats as labels, space-separated. Until 2026-10-24 each free seat was a row
     * with its seatId, and the UUIDs were ~2,900 of a 69-seat table's tokens: a customer who changed
     * the showing in a second message needed a second table and passed Groq's 7,000 ITPM (three
     * runs of a tool-calling benchmark that is not in this repo). [proposeSeats] maps the labels
     * back to ids. */
    @Serializable
    data class FreeRow(val row: String?, val seats: String)

    @Serializable
    data class Price(val amount: String?, val bandLabel: String?)

    @Serializable
    data class AgeGate(val classification: String?, val minimumAge: Int, val restricted: Boolean)

    /** The seat map cut to its free seats, the biggest thing the model reads. */
    @Serializable
    data class FreeSeatMap(
        val sessionId: String?, val hallId: String?, val hallName: String?, val movieTitle: String?,
        val classification: String?, val startsAt: String?, val status: String?, val zone: String?,
        val price: Price?, val ageGate: AgeGate?, val freeSeatCount: Int, val freeSeats: List<FreeRow>,
    )

    /** `/api/app`'s `Movie` without the poster and trailer links, which no answer uses. These are
     * exactly the columns the benchmark's `movies` fixture serves, so the benchmark measures
     * what /chat sends, release date included (owner, 2026-10-29). */
    @Serializable
    data class MovieRow(
        val filmId: String?, val slug: String?, val title: String?, val durationMinutes: Int?,
        val classification: String?, val synopsis: String?, val releaseDate: String?,
    )

    @Serializable
    data class Refusal(val error: String, val message: String)

    private fun <T> toon(serializer: KSerializer<T>, value: T): String = Ktoon.Default.encodeToString(serializer, value)

    private fun refusal(error: String, message: String?) = toon(Refusal.serializer(), Refusal(error, message ?: error))

    /** The model reasons in the cinema's wall-clock time, as the customer speaks; an instant in
     * UTC would read as 12:30 for a 19:30 showing. */
    private fun local(at: Instant?, zone: String?): String? =
        at?.atZone(ZoneId.of(zone ?: "Asia/Jakarta"))?.toLocalDateTime()?.toString()

    private inline fun guarded(block: () -> String): String = try {
        block()
    } catch (e: WebApplicationException) {
        refusal(if (e.response.status == 404) "not_found" else "bad_request", e.message)
    } catch (e: IllegalArgumentException) {
        refusal("bad_request", "ID tidak dikenal: ${e.message}")
    }

    @Tool(name = "cities", value = ["Daftar kota yang punya bioskop (GET /api/app/cities)."])
    fun cities(): String = toon(ListSerializer(AppApiResource.City.serializer()), api.cities())

    @Tool(name = "cinemas", value = ["Daftar bioskop di satu kota (GET /api/app/cities/{city}/cinemas)."])
    fun cinemas(@P(name = "city", description = "Nama kota, persis seperti hasil cities") city: String): String =
        guarded { toon(ListSerializer(AppApiResource.Cinema.serializer()), api.cinemasInCity(city.trim())) }

    // The codes' meaning is spelled out because qwen3.8-27b, given only "SU", glossed it as
    // "(Suasana)" in production on 2026-10-17; it is "Semua Umur".
    @Tool(name = "movies", value = ["Film yang sedang tayang. Dengan siteRef: film yang tayang di bioskop itu " +
        "(GET /api/app/movies, GET /api/app/cinemas/{siteRef}). Kolom classification adalah klasifikasi usia: " +
        "SU = Semua Umur; 13+, 17+ dan 21+ = usia minimum penonton."])
    fun movies(
        @P(name = "siteRef", description = "slug atau siteId bioskop; kosongkan untuk semua bioskop", required = false)
        siteRef: String?,
    ): String = guarded {
        val movies = if (siteRef.isNullOrBlank()) api.movies() else api.cinemaDetail(siteRef.trim()).movies
        toon(ListSerializer(MovieRow.serializer()), movies.map {
            MovieRow(it.filmId?.toString(), it.slug, it.title, it.durationMinutes, it.classification, it.synopsis, it.releaseDate)
        })
    }

    @Tool(name = "showings", value = ["Jadwal tayang satu film di satu bioskop. Dengan date: hanya tanggal itu; " +
        "tanpa date: tanggal dan jam yang masih bisa dipesan, tanpa ID; untuk sessionId dan hallId panggil lagi dengan date " +
        "(GET /api/app/cinemas/{siteRef}/movies/{filmRef}/showings)."])
    fun showings(
        @P(name = "siteRef", description = "slug atau siteId bioskop") siteRef: String?,
        @P(name = "filmRef", description = "slug atau filmId film") filmRef: String?,
        @P(name = "date", description = "tanggal tayang, format YYYY-MM-DD, di zona waktu bioskop", required = false)
        date: String?,
    ): String = guarded {
        // Required in the schema (@P, not the Kotlin type, sets that), but in production on 2026-10-23
        // the model sent "film apa saja yang tayang" without filmRef, and a non-null parameter threw
        // an NPE before guarded could answer. A refusal that names the next call costs the model the
        // same retry, with nothing in the error log.
        if (siteRef.isNullOrBlank() || filmRef.isNullOrBlank()) return@guarded refusal("bad_request",
            "siteRef dan filmRef wajib. Untuk semua film di satu bioskop, panggil movies dengan siteRef " +
                "bioskop itu, lalu showings untuk tiap film.")
        val rows = api.showingsAtCinema(siteRef.trim(), filmRef.trim(), date?.trim()?.ifBlank { null })
        // Without a date, every bookable day with two UUIDs a row was ~2.5k Qwen tokens (a digit is a
        // token); one benchmark run got three at once and sent 10,494 to Groq's 7,000 ITPM (2026-10-27).
        if (date.isNullOrBlank()) return@guarded toon(ListSerializer(ShowingDay.serializer()),
            rows.groupBy { local(it.startsAt, it.zone)?.substringBefore('T') to it.hallName }.map { (k, s) ->
                ShowingDay(k.first, k.second, s.joinToString(" ") { local(it.startsAt, it.zone)?.substringAfter('T') ?: "?" }) })
        val list = rows.map {
            Showing(it.sessionId?.toString(), it.hallId?.toString(), it.hallName, it.siteName,
                local(it.startsAt, it.zone), local(it.endsAt, it.zone), it.status, it.zone)
        }
        toon(ListSerializer(Showing.serializer()), list)
    }

    @Tool(name = "free_seats", value = ["Kursi yang masih kosong untuk satu pertunjukan, beserta harga dan batas " +
        "usia (GET /api/app/halls/{hallId}/showings/{sessionId}, hanya kursi kosong)."])
    fun freeSeats(
        @P(name = "hallId", description = "hallId dari hasil showings") hallId: String,
        @P(name = "sessionId", description = "sessionId dari hasil showings") sessionId: String,
    ): String = guarded {
        val m = api.seatMap(UUID.fromString(hallId.trim()), UUID.fromString(sessionId.trim()))
        val free = m.rows.mapNotNull { r ->
            val labels = r.seats.filter { it.status == "available" }.mapNotNull { it.label }
            if (labels.isEmpty()) null else FreeRow(r.rowLabel, labels.joinToString(" "))
        }
        toon(FreeSeatMap.serializer(), FreeSeatMap(m.sessionId?.toString(), m.hallId?.toString(), m.hallName,
            m.movieTitle, m.classification, local(m.startsAt, m.zone), m.status, m.zone,
            m.price?.let { Price(it.amount?.toPlainString(), it.bandLabel) },
            m.ageGate?.let { AgeGate(it.classification, it.minimumAge, it.restricted) },
            free.sumOf { it.seats.split(' ').size }, free))
    }

    @Tool(name = "propose_seats", value = ["Mengusulkan kursi kepada pelanggan. TIDAK menahan atau memesan apa pun: " +
        "pelanggan meninjau dan mengonfirmasi sendiri di peta kursi."])
    fun proposeSeats(
        @P(name = "hallId", description = "hallId pertunjukan") hallId: String,
        @P(name = "sessionId", description = "sessionId pertunjukan") sessionId: String,
        @P(name = "seats", description = "label kursi dari hasil free_seats, mis. E5") seats: List<String>,
    ): String = guarded {
        val hall = UUID.fromString(hallId.trim())
        val session = UUID.fromString(sessionId.trim())
        val m = api.seatMap(hall, session)
        // resource_unit.label is not UNIQUE, so a label two seats share is refused, not guessed.
        val byLabel = m.rows.flatMap { it.seats }.filter { it.seatId != null }.groupBy { it.label?.uppercase() }
        val chosen = seats.map { l ->
            val match = byLabel[l.trim().uppercase()] ?: return@guarded refusal("not_found", "Kursi $l tidak ditemukan")
            match.singleOrNull() ?: return@guarded refusal("bad_request", "Label kursi $l tidak unik di studio ini")
        }
        val taken = chosen.filter { it.status != "available" }
        if (taken.isNotEmpty()) return@guarded refusal("seat_taken", "Kursi ${taken.joinToString { it.label.orEmpty() }} sudah terisi")
        val labels = chosen.map { it.label.orEmpty() }
        holder.proposal = Proposal(hall, session, chosen.map { it.seatId!! }, labels)
        "Usulan dicatat: ${labels.joinToString()} untuk ${m.movieTitle}, ${local(m.startsAt, m.zone)}. " +
            "Pelanggan akan melihat kursi ini tercentang di peta kursi dan mengonfirmasinya sendiri."
    }
}
