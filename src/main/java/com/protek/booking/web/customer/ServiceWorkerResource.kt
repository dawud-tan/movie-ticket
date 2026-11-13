package com.protek.booking.web.customer

import io.smallrye.common.annotation.RunOnVirtualThread
import jakarta.ws.rs.GET
import jakarta.ws.rs.Path
import jakarta.ws.rs.Produces
import jakarta.ws.rs.core.Response
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Serves the Firebase messaging service worker from the application <b>root</b>.
 *
 * <p><b>Why this exists as its own resource rather than another method on {@code PushResource}.</b>
 * A service worker's default scope is the path it is served from, so the copy at
 * {@code /push/firebase-messaging-sw.js} can only ever control pages under {@code /push/}.
 * Registering it from the seat map at {@code /halls/{poolId}/showings/{sessionId}} fails outright
 * with a scope error. The worker therefore has to be reachable at {@code /firebase-messaging-sw.js},
 * which is a root path and cannot be expressed inside a {@code @Path("/push")} class.
 *
 * <p>It is also the filename the Firebase JS SDK looks for by default, so a client that ever
 * relies on the default rather than passing an explicit registration finds it in the right place.
 *
 * <p>The existing {@code /push/firebase-messaging-sw.js} is deliberately left alone: the
 * {@code /push/test} harness registers it and works, and repointing that page while debugging a
 * live push problem would remove the one known-good reference.
 */
@Path("/firebase-messaging-sw.js")
@RunOnVirtualThread
class ServiceWorkerResource {

    // Reuses PushResource's declaration rather than declaring its own: Qute allows exactly one
    // @CheckedTemplate per template path, and two classes both declaring push/sw fails the build
    // with "Multiple checked templates exist for the template path push/sw". Same package, so the
    // package-private accessor is reachable, and both endpoints stay guaranteed to serve
    // byte-identical worker source — which matters, because a browser that has registered one and
    // then fetches the other must not see them as different workers.

    @ConfigProperty(name = "webpush.firebase.api-key")
    lateinit var apiKey: String
    @ConfigProperty(name = "webpush.firebase.auth-domain")
    lateinit var authDomain: String
    @ConfigProperty(name = "webpush.firebase.project-id")
    lateinit var projectId: String
    @ConfigProperty(name = "webpush.firebase.storage-bucket")
    lateinit var storageBucket: String
    @ConfigProperty(name = "webpush.firebase.messaging-sender-id")
    lateinit var messagingSenderId: String
    @ConfigProperty(name = "webpush.firebase.app-id")
    lateinit var appId: String

    @GET
    @Produces("text/javascript")
    fun serviceWorker(): Response {
        // Service-Worker-Allowed widens the scope a registration may claim. Not strictly needed
        // while the file is served from the root, but it keeps the worker registerable at root
        // scope if the app is ever mounted under a prefix, which is otherwise a confusing
        // scope-error-only-in-production failure.
        //
        // no-store because the worker embeds the Firebase project config: a stale cached copy
        // would keep talking to whichever project was configured when it was cached, and browsers
        // cache service workers aggressively.
        // render() to a String rather than returning the TemplateInstance: a browser REFUSES to
        // register a service worker whose Content-Type is not a JavaScript MIME type, and a
        // TemplateInstance wrapped in a Response comes back as text/html no matter what @Produces
        // or .type() say — Qute sets the content type from the template variant and wins. Verified
        // directly: this served text/html until the template was rendered here instead. A String
        // entity carries the media type set below.
        val script = PushResource.Templates.sw(apiKey, authDomain, projectId, storageBucket,
            messagingSenderId, appId).render()
        return Response.ok(script)
            .type("text/javascript;charset=UTF-8")
            .header("Service-Worker-Allowed", "/")
            .header("Cache-Control", "no-store")
            .build()
    }
}
