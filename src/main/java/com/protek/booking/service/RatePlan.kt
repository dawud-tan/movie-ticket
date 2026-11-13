package com.protek.booking.service

import com.protek.booking.isJavaBlank
import com.protek.booking.javaTrim
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.math.BigInteger
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Collections
import java.util.EnumMap

/**
 * A parsed `rate_rule.rule_definition` — the single place in the
 * codebase that knows what that jsonb means.
 *
 * **Two shapes, one discriminator.** `{"type":"flat","amount":50000}` is the original
 * and stays exactly as it was; `{"type":"by_calendar","bands":{...}}` is what a real
 * cinema chain was measured pricing on. Before this class the `type` key was written by the
 * seed and read by nobody, which was the first defect to fix: a discriminator nothing
 * discriminates on is a column-shaped lie.
 *
 * **Why four fixed bands and not a general day-of-week map.** The published
 * price list of all 38 branches of one chain was read: every one of them publishes the same three weekday bands
 * — Mon-Wed, Thu-Fri, Sat-Sun — plus a holiday band, with only the amounts varying by branch.
 * A general `{"monday": …, "tuesday": …}` map would be seven inputs on the admin form
 * to express what every real tenant fills in as three, and the standing rule
 * is to build the measured shape rather than the imaginable one. A tenant
 * whose Thursday differs from their Friday is the trigger to generalise; none exists.
 *
 * **Deliberately read as a tree rather than bound to a class.** Data-binding to a record would
 * reject any jsonb a future shape adds a key to, where reading the tree keeps this additive — the
 * same reasoning `BookingService` already applied to the flat shape.
 *
 * **Read the way Jackson read it, to the digit** (kotlinx since 2026-10-08). Jackson parsed this with `USE_BIG_DECIMAL_FOR_FLOATS`, and its tree strips trailing
 * zeros from every fraction: `50000.00` came back as `5E+4`, `25000.10` as `25000.1`. Those
 * amounts reach the JSON API and the admin form unchanged, so
 * [jacksonText] reproduces that rather than kotlinx's literal text — which would have quietly
 * changed what a client is sent for every rule an operator typed with cents.
 */
class RatePlan private constructor(
    private val type: String,
    private val flatAmount: BigDecimal?,
    private val bands: Map<Band, BigDecimal>,
    private val inclusions: List<String>,
) {

    /**
     * The bands the measured chain publishes. `HOLIDAY` is not a fifth day of the week — it overrides
     * whichever of the other three the date falls in, including a Sunday.
     */
    enum class Band(
        /** The jsonb key this band is stored under. */
        @get:JvmName("key") val key: String,
        /** What a customer or an operator sees. */
        @get:JvmName("label") val label: String,
    ) {
        MON_WED("mon_wed", "Mon-Wed"),
        THU_FRI("thu_fri", "Thu-Fri"),
        SAT_SUN("sat_sun", "Sat-Sun"),
        HOLIDAY("holiday", "Public holiday"),
    }

    fun isCalendar(): Boolean = TYPE_BY_CALENDAR == type

    fun type(): String = type

    /** The amount for a band, or the flat amount if this rule has no bands at all. */
    fun amountFor(band: Band): BigDecimal? = if (isCalendar()) bands[band] else flatAmount

    /** The flat amount, or `null` for a calendar rule — for the admin screen, which
     * shows one number or four. */
    fun flatAmount(): BigDecimal? = flatAmount

    fun bands(): Map<Band, BigDecimal> = Collections.unmodifiableMap(bands)

    /**
     * What every ticket under this rule includes — a cinema may bundle a fixed drink voucher
     * into all four bands.
     *
     * Text lines, not stock: an F&amp;B *ordering* system stays out of scope for the
     * reason that it reuses none of the `EXCLUDE` spine this platform's value
     * rests on. This is one more line on the rate rule that prints on the ticket, and it is
     * the whole of the feature.
     */
    fun inclusions(): List<String> = inclusions

    /** Serialises back to the jsonb the column stores. */
    fun json(): String {
        val sb = StringBuilder("{\"type\":\"").append(type).append('"')
        if (isCalendar()) {
            sb.append(",\"bands\":{")
            var first = true
            for (band in Band.entries) {
                // Refused rather than defaulted: a missing band would be a rule that prices
                // three days of the week and silently fails on the fourth, at whatever hour
                // the first Thursday hold comes in.
                val amount = bands[band]
                    ?: throw IllegalArgumentException("Band " + band.label + " has no amount")
                if (!first) {
                    sb.append(',')
                }
                sb.append('"').append(band.key).append("\":").append(amount.toPlainString())
                first = false
            }
            sb.append('}')
        } else {
            sb.append(",\"amount\":").append(flatAmount!!.toPlainString())
        }
        if (inclusions.isNotEmpty()) {
            sb.append(",\"inclusions\":[")
            inclusions.forEachIndexed { i, line ->
                if (i > 0) {
                    sb.append(',')
                }
                sb.append(quote(line))
            }
            sb.append(']')
        }
        return sb.append('}').toString()
    }

    companion object {
        const val TYPE_FLAT = "flat"
        const val TYPE_BY_CALENDAR = "by_calendar"

        /**
         * Parses stored jsonb.
         *
         * @throws IllegalStateException if the JSON is malformed or names a type this build does
         *         not know. Loud on purpose: the alternative is falling back to some default
         *         amount, and a booking priced by a guess is worse than a booking that fails.
         */
        @JvmStatic
        fun parse(json: String?): RatePlan {
            // Blank reads as an empty document, as Jackson's readTree did (a "missing" node), and
            // so fails below as a flat rule with no amount rather than as unparseable. There is no
            // literal check on top of kotlinx's parse, unlike MidtransJson: the column is jsonb,
            // so PostgreSQL has refused anything that is not JSON before this ever sees it.
            val node: JsonElement = try {
                if (json == null || json.isJavaBlank()) JsonNull else Json.parseToJsonElement(json)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("Malformed rate_rule.rule_definition: $json", e)
            }
            // Absent `type` reads as flat. Nothing in the tree writes such a row -- the demo seed has always
            // written the discriminator -- but a rule hand-inserted during a demo might, and the
            // flat shape is the one this codebase shipped with.
            val typeNode = field(node, "type")
            val type = if (typeNode == null || typeNode is JsonNull) TYPE_FLAT else jacksonText(typeNode)
            val inclusions = readInclusions(node)

            if (TYPE_FLAT == type) {
                return RatePlan(TYPE_FLAT, amountAt(node, "amount", json), emptyMap(), inclusions)
            }
            if (TYPE_BY_CALENDAR == type) {
                val bandsNode = field(node, "bands") as? JsonObject
                    ?: throw IllegalStateException("by_calendar rate_rule has no bands object: $json")
                val parsed = EnumMap<Band, BigDecimal>(Band::class.java)
                for (band in Band.entries) {
                    parsed[band] = amountAt(bandsNode, band.key, json)
                }
                return RatePlan(TYPE_BY_CALENDAR, null, parsed, inclusions)
            }
            throw IllegalStateException("Unknown rate_rule.rule_definition type '$type': $json")
        }

        /** Builds the flat shape — byte-for-byte what `CatalogService` wrote before calendar pricing. */
        @JvmStatic
        fun flat(amount: BigDecimal?, inclusions: List<String>?): RatePlan =
            RatePlan(TYPE_FLAT, amount, emptyMap(), normalise(inclusions))

        /** Builds the calendar shape. Every band must be priced — see [json]. */
        @JvmStatic
        fun byCalendar(bands: Map<Band, BigDecimal>, inclusions: List<String>?): RatePlan =
            RatePlan(TYPE_BY_CALENDAR, null, EnumMap<Band, BigDecimal>(Band::class.java).apply { putAll(bands) },
                normalise(inclusions))

        /**
         * Which band a showing falls in.
         *
         * Takes a [LocalDate] already resolved in the branch's timezone — deliberately,
         * because the trap is exactly that a 23:30 WIB Sunday showing is a Monday in
         * UTC and would price a band too low. Handing this method an `Instant` would let a
         * caller skip the conversion and never notice; making it demand a local date means the
         * caller has to have answered "local where?" first. [CalendarPricing] is where that
         * answer comes from.
         *
         * `holidayOrEve` wins over the day of the week. For the measured chain the two agree on a
         * weekend, so the precedence is invisible in its price list and has to be decided here
         * rather than discovered: a holiday falling on a Tuesday is priced as a holiday.
         */
        @JvmStatic
        fun bandFor(localDate: LocalDate, holidayOrEve: Boolean): Band {
            if (holidayOrEve) {
                return Band.HOLIDAY
            }
            return when (localDate.dayOfWeek!!) {
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY -> Band.MON_WED
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY -> Band.THU_FRI
                DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> Band.SAT_SUN
            }
        }

        /** Jackson's `node.get(field)`: null unless the node is an object holding that key. */
        private fun field(node: JsonElement, name: String): JsonElement? = (node as? JsonObject)?.get(name)

        private fun readInclusions(node: JsonElement): List<String> {
            val array = field(node, "inclusions") as? JsonArray
                ?: return emptyList()
            val lines = ArrayList<String>()
            for (element in array) {
                // Jackson's asText(""): a JSON null and a container both read as "", so they are
                // skipped like a blank line rather than printed as "null".
                val text = (if (element is JsonNull) "" else jacksonText(element)).javaTrim()
                if (text.isNotEmpty()) {
                    lines.add(text)
                }
            }
            return Collections.unmodifiableList(lines)
        }

        private fun normalise(inclusions: List<String>?): List<String> {
            if (inclusions == null) {
                return emptyList()
            }
            return inclusions.map { it.javaTrim() }.filter { it.isNotEmpty() }
        }

        /**
         * Reads an amount as a [BigDecimal] via its text — Jackson's text, see [jacksonText].
         *
         * Through the text, never through a `Double`. Money never round-trips through binary
         * floating point in this codebase, and the flat path has always read the amount through
         * its text — this keeps that true for four amounts instead of one.
         *
         * What is *not* preserved is scale: `25000.10` comes back as `25000.1`, because that is
         * what Jackson's node factory did and the amount leaves this class in JSON. Harmless for
         * the booking — `reservation.price_amount` is `numeric(14,2)` and imposes its own scale —
         * and worth knowing before writing an assertion that compares with `equals` rather than
         * `compareTo`.
         */
        private fun amountAt(node: JsonElement, field: String, json: String?): BigDecimal {
            val value = field(node, field)
            if (value == null || value is JsonNull) {
                throw IllegalStateException("rate_rule.rule_definition is missing '$field': $json")
            }
            try {
                return BigDecimal(jacksonText(value))
            } catch (e: NumberFormatException) {
                throw IllegalStateException("rate_rule.rule_definition '$field' is not a number: $json", e)
            }
        }

        /**
         * Jackson's `asText()` on a tree read with `USE_BIG_DECIMAL_FOR_FLOATS`, which is how this
         * column was read until 2026-10-08:
         *  - a string is its own content, and an object or array is `""`;
         *  - an integer is its canonical digits (`-0` is `0`), as an int, long or BigInteger node;
         *  - a fraction or exponent is a `BigDecimal` with trailing zeros stripped and zero kept as
         *    plain `0` — `50000.00` reads `5E+4`, `25000.10` reads `25000.1`, `0.00` reads `0`.
         *    Measured against Jackson 2.21.7 before the switch; `RatePlanTest` pins the cases.
         */
        private fun jacksonText(value: JsonElement): String {
            val primitive = value as? JsonPrimitive ?: return ""
            if (primitive.isString) return primitive.content
            val content = primitive.content
            if (content == "true" || content == "false") return content
            return try {
                if (content.none { it == '.' || it == 'e' || it == 'E' }) {
                    BigInteger(content).toString()
                } else {
                    val decimal = BigDecimal(content)
                    if (decimal.signum() == 0) "0" else decimal.stripTrailingZeros().toString()
                }
            } catch (e: NumberFormatException) {
                // Not reachable through jsonb (see parse); handed to the caller's own number check.
                content
            }
        }

        /** Minimal JSON string escaping — this builds the jsonb by hand rather than taking a
         * writer, so the one thing an operator can type into it has to be escaped here. */
        private fun quote(text: String): String {
            val sb = StringBuilder("\"")
            for (ch in text) {
                when (ch) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (ch.code < 0x20) {
                        sb.append(String.format("\\u%04x", ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
            return sb.append('"').toString()
        }
    }
}
