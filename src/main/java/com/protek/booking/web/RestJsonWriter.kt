package com.protek.booking.web

import com.protek.booking.json.JacksonText
import jakarta.annotation.Priority
import jakarta.inject.Inject
import jakarta.ws.rs.Priorities
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedMap
import jakarta.ws.rs.ext.MessageBodyWriter
import jakarta.ws.rs.ext.Provider
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.io.OutputStream
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * Writes this application's own JSON responses with kotlinx.serialization, in Jackson's bytes.
 *
 * **What it replaced.** Until 2026-10-08 `JacksonJsonProvider` stood here and wrote every
 * `com.protek.booking` response with Jackson, because `quarkus-rest-kotlin-serialization`
 * cannot serialise a Java record. The REST types then became Kotlin
 * `@Serializable` classes and `quarkus-rest-jackson` was removed.
 *
 * **Why there is still a writer of our own.** The extension's writer would serialise these types
 * correctly, but it escapes a control character as `\u001f` where Jackson wrote `\u001F`. Every
 * JSON reader treats the two alike; the owner asked that the bytes not change anyway, and kotlinx
 * offers no setting for it. So this encodes with the same configured [Json] bean the extension
 * uses — same serializers, same `encodeDefaults`/`explicitNulls`, so the same document — and only
 * the final rendering goes through [JacksonText]. `RestJsonWriterTest` pins the output for every
 * REST type to the bytes Jackson produced before it was removed.
 *
 * **Only our types, and that is now safe.** The old provider claimed everything *except* another
 * application's package,
 * because an allow-list once missed Quarkus's own Jackson error payload (`MismatchedJsonInputError`)
 * and turned a 400 into a 500. That payload went with Jackson. Anything this does not claim falls to
 * the extension's writer, which serialises it as it always did — any other endpoint's JSON is
 * untouched, and so is a `String` body built by hand, which no `com.protek.booking` predicate matches.
 */
@Provider
@Produces(MediaType.APPLICATION_JSON)
// Ahead of the extension's writer, which sits at USER priority: two writers claiming
// application/json is not a choice to leave to registration order.
@Priority(Priorities.ENTITY_CODER)
class RestJsonWriter : MessageBodyWriter<Any> {

    /** The extension's own configured instance, not a second one — two `Json`s would be two
     * dialects for one application. */
    @Inject
    lateinit var json: Json

    override fun isWriteable(type: Class<*>?, genericType: Type?, annotations: Array<Annotation>?,
                             mediaType: MediaType?): Boolean =
        type != null && mediaType != null && MediaType.APPLICATION_JSON_TYPE.isCompatible(mediaType) &&
            isOurs(type, genericType)

    override fun writeTo(entity: Any, type: Class<*>, genericType: Type, annotations: Array<Annotation>,
                         mediaType: MediaType, httpHeaders: MultivaluedMap<String, Any>,
                         entityStream: OutputStream) {
        val tree = json.encodeToJsonElement(json.serializersModule.serializer(genericType), entity)
        entityStream.write(JacksonText.render(tree).toByteArray(Charsets.UTF_8))
    }

    companion object {
        private const val OUR_PACKAGE = "com.protek.booking."

        /**
         * A type declared under `com.protek.booking`, or a collection of one.
         *
         * A `List<Showing>` arrives as `ArrayList`, whose own package says nothing about its
         * contents, so a collection is judged by its element type — and therefore needs its
         * generic type. A resource method declaring `List<…>` supplies it; a `Response` carrying a
         * bare list does not, which is why `PushResource.testSend` wraps its list in a
         * `GenericEntity`.
         *
         * An element type can arrive as a wildcard: Kotlin writes that `GenericEntity<List<SendResult>>`
         * into the class file as `List<? extends SendResult>`, final class or not, and
         * `RestJsonWriterTest` caught this predicate missing it. So a wildcard is judged by its bound.
         */
        fun isOurs(type: Class<*>, genericType: Type?): Boolean {
            if (Iterable::class.java.isAssignableFrom(type)) {
                val arguments = (genericType as? ParameterizedType)?.actualTypeArguments ?: return false
                return arguments.any { argument ->
                    val element = if (argument is WildcardType) argument.upperBounds.firstOrNull() else argument
                    element is Class<*> && element.name.startsWith(OUR_PACKAGE)
                }
            }
            return type.name.startsWith(OUR_PACKAGE)
        }
    }
}
