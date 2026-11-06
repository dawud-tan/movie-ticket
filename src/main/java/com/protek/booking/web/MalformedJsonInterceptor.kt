package com.protek.booking.web

import jakarta.ws.rs.BadRequestException
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.ext.Provider
import jakarta.ws.rs.ext.ReaderInterceptor
import jakarta.ws.rs.ext.ReaderInterceptorContext

/**
 * A request body that kotlinx.serialization cannot read is the caller's mistake — a 400, not a 500.
 *
 * **Why this is needed now.** Jackson's reader failed with its own exception type, which Quarkus
 * maps to a 400 itself. kotlinx's reader throws `SerializationException`, which Quarkus REST hands
 * to the exception mappers as-is and nothing maps, so after `quarkus-rest-jackson` was removed
 * (2026-10-08) a bad UUID in `POST /push/subscribe` became a 500 —
 * `PushSubscribeEndpointTest.malformedJsonIsRejectedAsBadRequest_notAsAServerError` is the case.
 * A client that gets a 500 cannot tell a malformed request from a broken server, which is the same
 * reason `RestJsonWriter`'s predecessor once fought this exact fight.
 *
 * **An interceptor, not an exception mapper**, because only an interceptor knows the exception came
 * out of *reading the request*. A `SerializationException` thrown while writing a response is a
 * server bug, and mapping every one to 400 would blame the caller for it.
 *
 * `IllegalArgumentException` rather than `SerializationException` alone: the second extends the
 * first, and kotlinx also lets some decoding failures out as the plain kind.
 *
 * Scoped to this application's own types by [RestJsonWriter.isOurs]: any other endpoint on the
 * classpath answers a malformed body as it decides.
 */
@Provider
class MalformedJsonInterceptor : ReaderInterceptor {

    override fun aroundReadFrom(context: ReaderInterceptorContext): Any? =
        try {
            context.proceed()
        } catch (e: IllegalArgumentException) {
            val json = context.mediaType != null && MediaType.APPLICATION_JSON_TYPE.isCompatible(context.mediaType)
            if (!json || !RestJsonWriter.isOurs(context.type, context.genericType)) throw e
            throw BadRequestException("Malformed JSON request body", e)
        }
}
