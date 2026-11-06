@file:UseSerializers(UuidSerializer::class)

package com.protek.booking.web.customer

import com.protek.booking.db.Db
import com.protek.booking.isJavaBlank
import com.protek.booking.json.UuidSerializer
import com.protek.booking.repository.ResourcePoolWatchRepository
import com.protek.booking.security.Roles
import com.protek.booking.service.CurrentTenant
import com.protek.booking.service.PushNotificationService
import com.protek.booking.service.SeatAvailabilityNotifier
import io.quarkus.qute.CheckedTemplate
import io.quarkus.qute.TemplateInstance
import io.quarkus.runtime.annotations.RegisterForReflection
import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.annotation.security.RolesAllowed
import jakarta.inject.Inject
import jakarta.ws.rs.Consumes
import jakarta.ws.rs.DELETE
import jakarta.ws.rs.GET
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.GenericEntity
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.Response
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.jboss.logging.Logger
import java.util.UUID

/**
 * Push subscription endpoints for realtime availability, plus the original
 * FCM-token-mediated verification harness that proved the pipeline end to end.
 *
 * `/subscribe` (POST and DELETE) is the register/unregister pair the seat map drives —
 * see `seatmap-push.js`. `/config` hands the browser everything it needs in one call.
 * `/test` and `/test-send` remain as the manual harness: they were how the send path
 * was first verified against a real device, and they are still the quickest way to answer "is
 * push working at all on this deployment" without waiting for a booking to change.
 *
 * Note the deliberate difference in what they register: the harness subscribes with a NULL
 * session (a watch on the hall), while the seat map subscribes with its specific session. The
 * lookup matches the pool/session pair exactly — `COALESCE(session, ZERO) =
 * COALESCE(:session, ZERO)` — so a hall-wide watch does NOT receive notifications for a specific
 * showing. That is the specified semantics, not an oversight: a customer watching tonight's
 * showing should not be woken by changes to tomorrow's.
 *
 * The JSON bodies are kotlinx.serialization's since 2026-10-08,
 * written in Jackson's bytes by `RestJsonWriter`; request bodies keep Jackson's leniency.
 */
@Path("/push")
@RunOnVirtualThread
class PushResource {

    /** Qute's Kotlin form of a checked template (see `AdmissionResource`). `ServiceWorkerResource`
     * renders `sw` too, from Java, through the static methods these compile to. */
    @CheckedTemplate(basePath = "push")
    object Templates {
        @JvmStatic
        external fun test(): TemplateInstance

        @JvmStatic
        external fun sw(apiKey: String?, authDomain: String?, projectId: String?,
                        storageBucket: String?, messagingSenderId: String?, appId: String?): TemplateInstance
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class SubscribeRequest(val poolId: UUID? = null, val sessionId: UUID? = null, val token: String? = null)

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    data class TestSendRequest(val poolId: UUID? = null, val message: String? = null)

    @RegisterForReflection
    @Serializable
    data class SendResult(val tokenSuffix: String?, val messageId: String?, val error: String?)

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var currentTenant: CurrentTenant

    @Inject
    lateinit var watchRepository: ResourcePoolWatchRepository

    @Inject
    lateinit var pushNotificationService: PushNotificationService

    @Inject
    lateinit var notifier: SeatAvailabilityNotifier

    @ConfigProperty(name = "webpush.firebase.api-key")
    lateinit var firebaseApiKey: String

    @ConfigProperty(name = "webpush.firebase.auth-domain")
    lateinit var firebaseAuthDomain: String

    @ConfigProperty(name = "webpush.firebase.project-id")
    lateinit var firebaseProjectId: String

    @ConfigProperty(name = "webpush.firebase.storage-bucket")
    lateinit var firebaseStorageBucket: String

    @ConfigProperty(name = "webpush.firebase.messaging-sender-id")
    lateinit var firebaseMessagingSenderId: String

    @ConfigProperty(name = "webpush.firebase.app-id")
    lateinit var firebaseAppId: String

    @GET
    @Path("/test")
    @Produces(MediaType.TEXT_HTML)
    fun testPage(): TemplateInstance = Templates.test()

    @GET
    @Path("/firebase-messaging-sw.js")
    @Produces("text/javascript")
    fun serviceWorkerScript(): TemplateInstance =
        Templates.sw(firebaseApiKey, firebaseAuthDomain, firebaseProjectId,
            firebaseStorageBucket, firebaseMessagingSenderId, firebaseAppId)

    @GET
    @Path("/vapid-public-key")
    @Produces(MediaType.TEXT_PLAIN)
    fun vapidPublicKey(): Response {
        if (!pushNotificationService.isConfigured()) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .entity("webpush.vapid.public-key is not configured (VAPID_PUBLIC_KEY not set)")
                .build()
        }
        return Response.ok(pushNotificationService.vapidPublicKey()).build()
    }

    // A null body is a 500 here, as it was in Java: request is non-null, so Kotlin's own
    // parameter check refuses it where Java's request.poolId() threw. unsubscribe and testSend
    // checked for null and still do.
    @POST
    @Path("/subscribe")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun subscribe(request: SubscribeRequest): Response {
        val poolId = request.poolId
        val token = request.token
        if (poolId == null || token == null || token.isJavaBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"poolId and token are required\"}")
                .build()
        }
        // publicCatalogueId(), not id(): a resource_pool_watch is scoped to the catalogue a
        // reader is watching, not to a tenant an operator administers, so a platform_admin
        // browsing a public seat map subscribes like any other reader instead of getting
        // id()'s by-design refusal. See CurrentTenant.publicCatalogueId()'s javadoc.
        val saved = notifier.watch(currentTenant.publicCatalogueId(), poolId, request.sessionId, token)
        return Response.ok("{\"resourcePoolWatchId\":\"" + saved.resourcePoolWatchId + "\"}").build()
    }

    /**
     * The client's own unregister — the *primary* cleanup path: the client deletes its
     * own watch when the seat-map page closes; [expires_at] only catches crashes and dropped
     * connections.
     *
     * Returns 204 whether or not a row was removed. A page-close handler firing twice, or
     * after the expiry sweep already pruned the row, is the normal case rather than an error, and
     * a beacon-style request has nobody left to read a 404.
     */
    @DELETE
    @Path("/subscribe")
    @Consumes(MediaType.APPLICATION_JSON)
    fun unsubscribe(request: SubscribeRequest?): Response {
        if (request?.poolId == null || request.token == null) {
            return Response.status(Response.Status.BAD_REQUEST).build()
        }
        // See subscribe() above: same reasoning, same method.
        val removed = notifier.unwatch(currentTenant.publicCatalogueId(), request.poolId, request.sessionId, request.token)
        LOG.debugf("Unsubscribed %d watch row(s) for pool %s session %s",
            removed, request.poolId, request.sessionId)
        return Response.noContent().build()
    }

    /**
     * Everything the browser needs to subscribe, in one call.
     *
     * A JSON endpoint rather than values templated into each page: the seat map, the test
     * harness and the service worker all need the same Firebase web config, and duplicating it
     * into three templates is three places for it to drift. The values are not secret — they are
     * designed to ship in client-side JS, protected by Firebase Security Rules rather than
     * obscurity (see application.properties).
     *
     * `enabled` is what the client keys off: with no VAPID key configured there is
     * nothing to subscribe with, and the seat map should quietly stay on its polling refresh
     * rather than throwing in the console on every page load.
     */
    @GET
    @Path("/config")
    @Produces(MediaType.APPLICATION_JSON)
    fun config(): PushConfig =
        PushConfig(
            pushNotificationService.isConfigured(),
            if (pushNotificationService.isConfigured()) pushNotificationService.vapidPublicKey() else null,
            firebaseApiKey, firebaseAuthDomain, firebaseProjectId,
            firebaseStorageBucket, firebaseMessagingSenderId, firebaseAppId)

    @RegisterForReflection
    @Serializable
    data class PushConfig(
        val enabled: Boolean, val vapidKey: String?, val apiKey: String?, val authDomain: String?,
        val projectId: String?, val storageBucket: String?, val messagingSenderId: String?,
        val appId: String?,
    )

    // The root-scope service worker lives in its own resource class, ServiceWorkerResource:
    // a service worker's scope is the directory it is served from, so the copy under /push/ can
    // only ever control /push/* and could never control /halls/{id}/showings/{id}. See there.

    // Staff only: it sends caller-chosen text to every watch on a pool, and a poolId is in every
    // public seat-map URL, so an anonymous caller could push a fake "booking cancelled" to them all.
    @POST
    @Path("/test-send")
    @RolesAllowed(Roles.TENANT_STAFF)
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    fun testSend(request: TestSendRequest?): Response {
        val poolId = request?.poolId
            ?: return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"error\":\"poolId is required\"}")
                .build()
        val tenantId = currentTenant.id()
        val watches = db.inTenantTransaction(tenantId) { c -> watchRepository.findByPool(c, tenantId, poolId) }
        if (watches.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                .entity("{\"error\":\"no live watches registered for this pool\"}")
                .build()
        }
        val message = request.message
        val body = if (message != null && !message.isJavaBlank()) message else "test-" + UUID.randomUUID()

        val results = ArrayList<SendResult>()
        for (watch in watches) {
            val token = watch.fcmRegistrationToken!!
            val tokenSuffix = if (token.length > 8) token.substring(token.length - 8) else token
            try {
                val messageId = pushNotificationService.send(token, "Booking Platform Test", body)
                results.add(SendResult(tokenSuffix, messageId, null))
            } catch (e: Exception) {
                results.add(SendResult(tokenSuffix, null, e.message))
            }
        }
        // Wrapped so the writer is told it is a List<SendResult>: a bare list reaches it as an
        // ArrayList, whose own type says nothing about what is in it (RestJsonWriter.isOurs).
        return Response.ok(object : GenericEntity<List<SendResult>>(results) {}).build()
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(PushResource::class.java)
    }
}
