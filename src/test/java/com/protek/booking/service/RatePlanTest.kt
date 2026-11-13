package com.protek.booking.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.EnumMap

/**
 * The jsonb half of calendar pricing, tested without a database: what
 * `rate_rule.rule_definition` means, and which band a date falls in.
 *
 * Plain JUnit rather than `@QuarkusTest` on purpose — none of this needs a container,
 * and the band table is the kind of thing worth being able to run in a second while changing it.
 * The half that *does* need a database — resolving the branch's timezone and looking up
 * the holiday list — is [CalendarPricingTest].
 *
 * Kotlin since 2026-10-08, when [RatePlan] stopped taking Jackson's `ObjectMapper` and began
 * reading the column with kotlinx.serialization. Every assertion
 * from the Java version is here unchanged; [fractionsAreReadAsJacksonsTreeReadThem] is new,
 * because what Jackson used to decide about a fraction is now this class's to get right.
 */
class RatePlanTest {

    @Test
    fun phase1sFlatShapeStillParses_becauseEveryExistingTenantIsOnIt() {
        // The row the demo seed has always carried. Calendar pricing must not have changed what it means.
        val plan = RatePlan.parse("{\"type\":\"flat\",\"amount\":50000}")

        assertFalse(plan.isCalendar())
        assertEquals(BigDecimal("50000"), plan.flatAmount())
        // A flat rule prices every band the same — that is what "flat" means, and it is why
        // the caller never has to ask which shape it is holding before asking the price.
        for (band in RatePlan.Band.entries) {
            assertEquals(BigDecimal("50000"), plan.amountFor(band),
                "a flat rule must price " + band.label + " the same as every other day")
        }
    }

    @Test
    fun theTypeDiscriminatorIsActuallyRead_whichWasTheDefect() {
        // Before calendar pricing the `type` key was written by the seed and read by nobody, so a by_calendar
        // rule would have been priced by node.get("amount") -- which is absent -- and thrown a
        // NullPointerException at hold time. This is the assertion that the discriminator now
        // discriminates.
        val plan = RatePlan.parse(
            "{\"type\":\"by_calendar\",\"bands\":{\"mon_wed\":25000,\"thu_fri\":30000," +
                "\"sat_sun\":35000,\"holiday\":35000}}")

        assertTrue(plan.isCalendar())
        assertEquals(BigDecimal("25000"), plan.amountFor(RatePlan.Band.MON_WED))
        assertEquals(BigDecimal("30000"), plan.amountFor(RatePlan.Band.THU_FRI))
        assertEquals(BigDecimal("35000"), plan.amountFor(RatePlan.Band.SAT_SUN))
    }

    @Test
    fun anUnknownTypeIsRefusedRatherThanPricedAtAGuess() {
        // A build that meets a shape a later build wrote must fail loudly. The alternative --
        // falling back to some default -- takes real money at a price nobody configured.
        val e = assertThrows(IllegalStateException::class.java) {
            RatePlan.parse("{\"type\":\"by_seat_category\",\"amount\":50000}")
        }
        assertTrue(e.message!!.contains("by_seat_category"), e.message)
    }

    @Test
    fun aCalendarRuleMissingABandIsRefused_notPricedAtZero() {
        val e = assertThrows(IllegalStateException::class.java) {
            RatePlan.parse(
                "{\"type\":\"by_calendar\",\"bands\":{\"mon_wed\":25000,\"sat_sun\":35000,\"holiday\":35000}}")
        }
        assertTrue(e.message!!.contains("thu_fri"), e.message)
    }

    @Test
    fun theThreeWeekdayBandsAreTheOnesNscPublishes() {
        // A.7b read all 38 branches' published price lists: Mon-Wed, Thu-Fri, Sat-Sun. The
        // boundaries are the whole feature, so each side of each one is pinned.
        assertEquals(RatePlan.Band.MON_WED, RatePlan.bandFor(MONDAY, false))
        assertEquals(RatePlan.Band.MON_WED, RatePlan.bandFor(MONDAY.plusDays(2), false), "Wednesday")
        assertEquals(RatePlan.Band.THU_FRI, RatePlan.bandFor(MONDAY.plusDays(3), false), "Thursday")
        assertEquals(RatePlan.Band.THU_FRI, RatePlan.bandFor(MONDAY.plusDays(4), false), "Friday")
        assertEquals(RatePlan.Band.SAT_SUN, RatePlan.bandFor(MONDAY.plusDays(5), false), "Saturday")
        assertEquals(RatePlan.Band.SAT_SUN, RatePlan.bandFor(MONDAY.plusDays(6), false), "Sunday")
    }

    @Test
    fun aHolidayOverridesTheWeekdayBand_evenOnATuesday() {
        // Invisible in a typical price list, because the holiday and weekend bands are equal
        // at most branches -- so the precedence has to be decided here rather than discovered
        // the first time a tenant prices them differently.
        assertEquals(RatePlan.Band.HOLIDAY, RatePlan.bandFor(MONDAY.plusDays(1), true),
            "a public holiday falling on a Tuesday is priced as a holiday")
        assertEquals(RatePlan.Band.HOLIDAY, RatePlan.bandFor(MONDAY.plusDays(6), true),
            "and so is one falling on a Sunday")
    }

    @Test
    fun inclusionsSurviveARoundTripThroughTheColumn() {
        // A price list may bundle a drink voucher into every band. It is one line on the rule
        // and it prints on the ticket, so it has to come back out exactly as it went in.
        val written = RatePlan.byCalendar(bands(), listOf("Termasuk voucher minuman Rp 10.000"))

        val read = RatePlan.parse(written.json())

        assertEquals(listOf("Termasuk voucher minuman Rp 10.000"), read.inclusions())
        assertEquals(BigDecimal("35000"), read.amountFor(RatePlan.Band.SAT_SUN))
    }

    @Test
    fun anInclusionWithAQuoteInItDoesNotCorruptTheJson() {
        // The rule is serialised by hand rather than by a writer, and this is the one field an
        // operator types free text into. A broken escape here is a rate rule that cannot be
        // parsed back -- which takes the hall off sale.
        val written = RatePlan.flat(BigDecimal("25000"), listOf("Includes a \"large\" drink\\voucher"))

        val read = RatePlan.parse(written.json())

        assertEquals(listOf("Includes a \"large\" drink\\voucher"), read.inclusions())
        assertEquals(BigDecimal("25000"), read.flatAmount())
    }

    @Test
    fun amountsAreNotParsedThroughADouble() {
        // Money never touches binary floating point in this codebase, and a rate rule is the
        // one place a price arrives as a JSON number. Jackson's default was to parse a
        // fractional number into a double first, so the amount that came back out was
        // whatever Double.toString made of it -- which for a value this long is not the value
        // that went in. A fractional rupiah is not a real price; the mechanism is the point,
        // and the day a tenant prices in a currency with subunits it stops being academic.
        val absurdlyPrecise = "1234567890123456789.12"
        val plan = RatePlan.parse(
            "{\"type\":\"by_calendar\",\"bands\":{\"mon_wed\":" + absurdlyPrecise +
                ",\"thu_fri\":30000,\"sat_sun\":35000,\"holiday\":35000}}")

        assertEquals(0, BigDecimal(absurdlyPrecise).compareTo(plan.amountFor(RatePlan.Band.MON_WED)),
            "parsed as " + plan.amountFor(RatePlan.Band.MON_WED)!!.toPlainString())
        // And the ordinary case, where only the value matters: scale is not preserved (Jackson's
        // node factory stripped trailing zeros, and this reads as it did) and does not need to
        // be -- price_amount is numeric(14,2) and imposes its own.
        val ordinary = RatePlan.parse("{\"type\":\"flat\",\"amount\":25000.10}")
        assertEquals(0, BigDecimal("25000.10").compareTo(ordinary.flatAmount()))
    }

    @Test
    fun fractionsAreReadAsJacksonsTreeReadThem() {
        // Measured against Jackson 2.21.7 with USE_BIG_DECIMAL_FOR_FLOATS before the switch to
        // kotlinx. The exact text matters, not just the value: a rule stored as 50000.00 has
        // always priced the counter's seat at 5E+4, and that is what a JSON client has always
        // been sent. kotlinx would have kept the literal 50000.00.
        fun amount(literal: String) = RatePlan.parse("{\"type\":\"flat\",\"amount\":$literal}").flatAmount()!!.toString()

        assertEquals("5E+4", amount("50000.00"))
        assertEquals("25000.1", amount("25000.10"))
        assertEquals("0", amount("0.00"), "zero is kept plain, not 0E-2")
        assertEquals("0", amount("-0.0"))
        assertEquals("1E+3", amount("1e3"))
        assertEquals("1.5E+3", amount("1.5E+3"))
        assertEquals("50000", amount("50000"), "an integer is untouched")
        assertEquals("0", amount("-0"), "and canonical, as Jackson's IntNode was")
        assertEquals("12345678901234567890", amount("12345678901234567890"))
        assertEquals("50000.00", amount("\"50000.00\""), "a quoted amount is its own text, scale and all")
    }

    private fun bands(): Map<RatePlan.Band, BigDecimal> =
        EnumMap<RatePlan.Band, BigDecimal>(RatePlan.Band::class.java).apply {
            put(RatePlan.Band.MON_WED, BigDecimal("25000"))
            put(RatePlan.Band.THU_FRI, BigDecimal("30000"))
            put(RatePlan.Band.SAT_SUN, BigDecimal("35000"))
            put(RatePlan.Band.HOLIDAY, BigDecimal("35000"))
        }

    companion object {
        /** Monday 1 June 2099 through Sunday 7 June 2099 — a fixed week, so "the tests pass except
         * on Thursdays" cannot happen. */
        private val MONDAY: LocalDate = LocalDate.of(2099, 6, 1)
    }
}
