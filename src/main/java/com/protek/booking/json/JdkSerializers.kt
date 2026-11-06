package com.protek.booking.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

/*
 * kotlinx serializers for the four JDK types this application's REST JSON carries, each writing
 * exactly what Jackson wrote before 2026-10-08.
 *
 * Why exactly, and not merely "valid JSON for the same value": clients were already parsing these
 * bodies when quarkus-rest-jackson was removed that day, so these serializers became the wire
 * format those clients were written against. Jackson here means
 * Quarkus's own ObjectMapper — JavaTimeModule registered, WRITE_DATES_AS_TIMESTAMPS off — and
 * RestJsonMatchesJacksonTest pins every REST type to the bytes it produced, captured from it
 * before it was removed.
 *
 * A DTO file opts in with @file:UseSerializers(...) naming these, rather than a contextual
 * module registered on the shared Json: that Json serves every endpoint, and a module
 * would change what they all accept without anything in their own files saying so.
 */

/** Canonical lowercase form, as Jackson's `UUIDSerializer` writes it. */
object UuidSerializer : KSerializer<UUID> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.util.UUID", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())

    // A malformed id is the caller's mistake, so it surfaces as a SerializationException — which
    // MalformedJsonInterceptor turns into a 400 — and never as the bare IllegalArgumentException
    // UUID.fromString throws, which is the same failure wearing a server-error's name.
    override fun deserialize(decoder: Decoder): UUID {
        val text = decoder.decodeString()
        return try {
            UUID.fromString(text)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("Not a UUID: '$text'", e)
        }
    }
}

/** ISO-8601 in UTC (`2099-06-07T12:00:00Z`, fraction only when non-zero) — `Instant.toString()`,
 * which is `ISO_INSTANT`, which is what Jackson's `InstantSerializer` writes with dates-as-text. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        val text = decoder.decodeString()
        return try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            throw SerializationException("Not an ISO-8601 instant: '$text'", e)
        }
    }
}

/** `2099-06-01` — `LocalDate.toString()`, Jackson's `LocalDateSerializer` with dates-as-text. */
object LocalDateSerializer : KSerializer<LocalDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.LocalDate", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): LocalDate {
        val text = decoder.decodeString()
        return try {
            LocalDate.parse(text)
        } catch (e: DateTimeParseException) {
            throw SerializationException("Not an ISO-8601 date: '$text'", e)
        }
    }
}

/**
 * A JSON **number**, written as `BigDecimal.toString()` — so `50000.00` stays `50000.00` and a
 * stripped `5E+4` stays `5E+4`.
 *
 * That second case is not hypothetical. `RatePlan` reads amounts the way Jackson's tree did, which
 * strips trailing zeros, so a rule stored as `50000.00` prices a seat at `5E+4` and a seat's
 * price has always gone out as `5E+4`. Jackson's generator writes `toString()` unless
 * `WRITE_BIGDECIMAL_AS_PLAIN` is on, and Quarkus leaves it off. Writing `toPlainString()` here
 * would have been tidier and a change to the wire format; a JSON number reader takes either,
 * but this conversion was not the place to decide that.
 *
 * Unquoted, which kotlinx only allows through `JsonUnquotedLiteral`: a quoted amount would be a
 * string in every client that does not coerce, and typed clients declare a decimal.
 */
@OptIn(ExperimentalSerializationApi::class)
object BigDecimalSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        if (encoder is JsonEncoder) {
            encoder.encodeJsonElement(JsonUnquotedLiteral(value.toString()))
        } else {
            encoder.encodeString(value.toString())
        }
    }

    // Through the element's text, never through a Double: a JSON number arrives here as the digits
    // that were sent, and BigDecimal(text) keeps every one of them, as Jackson did for a BigDecimal
    // property. A quoted number is accepted too, which Jackson's scalar coercion also allowed.
    override fun deserialize(decoder: Decoder): BigDecimal {
        val text = if (decoder is JsonDecoder) {
            (decoder.decodeJsonElement() as? JsonPrimitive)?.content
                ?: throw SerializationException("Expected a number")
        } else {
            decoder.decodeString()
        }
        return try {
            BigDecimal(text)
        } catch (e: NumberFormatException) {
            throw SerializationException("Not a number: '$text'", e)
        }
    }
}
