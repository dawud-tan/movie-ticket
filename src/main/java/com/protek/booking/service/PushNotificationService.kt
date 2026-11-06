package com.protek.booking.service

import com.google.api.client.json.gson.GsonFactory
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.AndroidConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.WebpushConfig
import com.google.firebase.messaging.WebpushFcmOptions
import com.google.firebase.messaging.WebpushNotification
import com.protek.booking.isJavaBlank
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.io.IOException

/**
 * FCM-token-mediated push sender via the Firebase Admin SDK — replaces an earlier raw
 * Web Push (RFC 8030/8291 + VAPID, `nl.martijndwars:web-push` + BouncyCastle)
 * implementation that hit a real, unresolved GraalVM/Mandrel limitation:
 * `javax.crypto.KeyAgreement.getInstance("ECDH","BC")` rejects any dynamically-registered
 * provider under native-image, with no known fix short of bypassing the JCA/JCE provider
 * framework entirely. This path reuses the same Admin SDK send mechanism already
 * exercised earlier for its native-image reflection surface, introducing no new crypto
 * code and therefore no new native-image risk of that kind.
 *
 * `FirebaseApp` is initialized lazily, on first real send, deliberately not at
 * CDI startup: this app has a hard, repeatedly-verified guarantee that it boots with zero
 * required environment variables, and most environments running it have no
 * `GOOGLE_APPLICATION_CREDENTIALS` configured at all.
 */
@ApplicationScoped
class PushNotificationService {

    @ConfigProperty(name = "webpush.vapid.public-key")
    @JvmField
    final var publicKey: String? = null

    /** Still needed client-side: the browser's `register(messaging, {vapidKey})`
     * call requires it, even though the server-side Admin SDK send below does not. */
    fun isConfigured(): Boolean {
        val key = publicKey
        return key != null && !key.isJavaBlank() && key != UNSET
    }

    fun vapidPublicKey(): String? = publicKey

    @Throws(IOException::class)
    private fun ensureFirebaseInitialized() {
        if (firebaseInitialized) {
            return
        }
        synchronized(FIREBASE_INIT_LOCK) {
            if (firebaseInitialized) {
                return
            }
            if (FirebaseApp.getApps().isEmpty()) {
                val options = FirebaseOptions.builder()
                        .setCredentials(GoogleCredentials.getApplicationDefault())
                        // Gson, not FirebaseOptions' default JacksonFactory, does the JSON. It
                        // was chosen to keep google-http-client-jackson2 off the main classpath,
                        // and that did not work: build() still loads JacksonFactory through
                        // ApiClientUtils, so pom.xml carries the module at compile scope (its
                        // comment has the NoClassDefFoundError). Kept, because switching would
                        // change a serializer only a real FCM send exercises.
                        // FirebaseClasspathTest builds these same options; change both together.
                        .setJsonFactory(GsonFactory.getDefaultInstance())
                        .build()
                FirebaseApp.initializeApp(options)
            }
            firebaseInitialized = true
        }
    }

    /**
     * @return the FCM message ID on success.
     *
     * `setFid`: both clients register through Firebase's FID API (`register()`/`onRegistered()` in
     * seatmap-push.js, `FirebaseMessaging.register()` on the phone), so what they hand us is a
     * Firebase Installation ID. A registration token sent here would come back `UNREGISTERED`,
     * which [sendToAll] treats as a dead subscription and [SeatAvailabilityNotifier] deletes; that
     * is how watch rows saved before the switch (2026-10-23) clear themselves.
     */
    @Throws(Exception::class)
    fun send(fcmToken: String?, title: String?, body: String?): String {
        ensureFirebaseInitialized()
        val message = Message.builder()
                .setFid(fcmToken)
                .setWebpushConfig(WebpushConfig.builder()
                        .setNotification(WebpushNotification(title, body))
                        .build())
                .build()
        return FirebaseMessaging.getInstance().send(message)
    }

    /**
     * The outcome of a batch send, split into what the caller must act on.
     *
     * @param sent          how many tokens FCM accepted
     * @param deadTokens    tokens FCM reported as unregistered or invalid. This is the
     *                      signal to DELETE that `resource_pool_watch` row rather than
     *                      retry it, so it is returned rather than merely logged.
     * @param failedTokens  tokens that failed for some other reason (a transient FCM error, a
     *                      quota problem). Deliberately kept separate from `deadTokens`:
     *                      deleting a watch because FCM was briefly unavailable would silently
     *                      unsubscribe a live customer.
     */
    @JvmRecord
    data class BatchResult(val sent: Int, val deadTokens: List<String>, val failedTokens: List<String>) {
        companion object {
            internal fun empty(): BatchResult = BatchResult(0, java.util.List.of(), java.util.List.of())
        }
    }

    /**
     * Sends one notification to many tokens, chunked to FCM's per-call limit.
     *
     * `data` rides along so the service worker can act on the message rather than only
     * display it — the seat-map page uses it to refresh the grid in place: a live-updating
     * map, as opposed to just a toast.
     *
     * Never throws for a per-token failure: a batch where some subscriptions have died must
     * still deliver to the ones that are alive, and the dead ones come back in the result for the
     * caller to prune. A genuine failure to reach FCM at all does propagate, since that is not a
     * per-token condition.
     */
    @Throws(Exception::class)
    fun sendToAll(fcmTokens: List<String>?, title: String?, body: String?,
                  data: Map<String, String>?, link: String? = null): BatchResult {
        if (fcmTokens == null || fcmTokens.isEmpty()) {
            return BatchResult.empty()
        }
        ensureFirebaseInitialized()

        var sent = 0
        val dead = ArrayList<String>()
        val failed = ArrayList<String>()

        for (start in 0 until fcmTokens.size step MAX_TOKENS_PER_CALL) {
            val chunk = fcmTokens.subList(start,
                    Math.min(start + MAX_TOKENS_PER_CALL, fcmTokens.size))

            val builder = MulticastMessage.builder()
                    .addAllFids(chunk)  // FIDs, for the reason on send()
                    .setWebpushConfig(WebpushConfig.builder()
                            // userVisibleOnly is enforced by the browser on the subscription
                            // side, so every push must carry something displayable — a silent
                            // data-only push would be dropped or draw a browser-generated
                            // "site updated in the background" notice instead.
                            .setNotification(WebpushNotification(title, body))
                            // What a tap opens: the order or the seat map. The SDK's own
                            // worker shows this notification and handles its click; sw.html shows
                            // nothing itself, or every push would arrive twice. FCM wants an
                            // absolute https URL here.
                            .apply { if (link != null) setFcmOptions(WebpushFcmOptions.withLink(link)) }
                            .build())
                    // The phone's watch gets only the data below, and a data message's
                    // default priority waits for Doze to end, the phone-in-a-pocket case this is
                    // for. High is fair use here: off screen, the app shows a notification for each one.
                    .setAndroidConfig(AndroidConfig.builder()
                            .setPriority(AndroidConfig.Priority.HIGH)
                            .build())
            data?.forEach { key, value -> builder.putData(key, value) }

            // sendEachForMulticast rather than the deprecated sendMulticast: it reports a result
            // per token, which is precisely what distinguishing dead tokens from transient
            // failures requires.
            val response = FirebaseMessaging.getInstance().sendEachForMulticast(builder.build())
            for (i in 0 until response.responses.size) {
                val each = response.responses[i]
                if (each.isSuccessful) {
                    sent++
                    continue
                }
                val token = chunk[i]
                val code: MessagingErrorCode? = each.exception?.messagingErrorCode
                if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                    dead.add(token)
                } else {
                    failed.add(token)
                }
            }
        }
        return BatchResult(sent, dead, failed)
    }

    companion object {
        private const val UNSET = "__unset__"
        private val FIREBASE_INIT_LOCK = Any()

        @Volatile
        private var firebaseInitialized = false

        /** FCM's documented ceiling for one multicast call ("up to 500 per call"). */
        const val MAX_TOKENS_PER_CALL = 500
    }
}
