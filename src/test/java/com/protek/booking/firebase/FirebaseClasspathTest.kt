package com.protek.booking.firebase

import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.testing.http.MockHttpTransport
import com.google.api.client.testing.http.MockLowLevelHttpRequest
import com.google.api.client.testing.http.MockLowLevelHttpResponse
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.WebpushConfig
import com.google.firebase.messaging.WebpushNotification
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties
import java.util.Queue
import java.util.TreeMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The Firebase Admin SDK's part of the classpath, checked with no credentials and no network.
 * Nothing else in `mvn test` reaches [FirebaseOptions]: the push tests install
 * [com.protek.booking.push.RecordingPushService] over the real sender, and the real
 * [com.protek.booking.service.PushNotificationService] stops at Application Default
 * Credentials before it builds its options.
 *
 * Written after the Quarkus 4.0.0.Beta1 bump moved quarkus-bom's `com.google.http-client`
 * family to 2.2.0 while `pom.xml` still pinned `google-http-client-jackson2` at 1.47.1.
 * `dependency:tree` showed it; no test did. That skew happened to run — the two Firebase
 * tests below pass on it — which is why the first test reads the versions themselves rather than
 * waiting for a send to break.
 */
class FirebaseClasspathTest {

    companion object {
        /** The four that pom.xml's comment on `google-http-client-jackson2` says must agree. */
        private val HTTP_CLIENT_MODULES = listOf(
            "google-http-client", "google-http-client-gson",
            "google-http-client-jackson2", "google-http-client-apache-v2")

        /**
         * PushNotificationService.ensureFirebaseInitialized's builder chain, but for its credentials:
         * an access token with no expiry is used as is and never refreshed, so nothing calls Google's
         * token endpoint, and the project id ADC would have supplied is given outright.
         */
        private fun productionOptions(): FirebaseOptions.Builder =
            FirebaseOptions.builder()
                .setCredentials(GoogleCredentials.create(AccessToken("test-token", null)))
                .setProjectId("test-project")
                .setJsonFactory(GsonFactory.getDefaultInstance())

        /* FirebaseApp keeps a static registry by name; a fresh name per test keeps a failed delete()
         * from turning into an "already exists" failure in the next. */
        private fun uniqueName(): String = "firebase-classpath-" + System.nanoTime()

        private fun versionOf(module: String): String {
            val path = "META-INF/maven/com.google.http-client/" + module + "/pom.properties"
            FirebaseClasspathTest::class.java.classLoader.getResourceAsStream(path).use { input ->
                assertNotNull(input, module + " is not on the classpath: no " + path)
                val pom = Properties()
                pom.load(input)
                return pom.getProperty("version")
            }
        }
    }

    @Test
    fun theFourGoogleHttpClientModulesResolveToOneVersion() {
        val versions: MutableMap<String, String> = TreeMap()
        for (module in HTTP_CLIENT_MODULES) {
            versions[module] = versionOf(module)
        }
        assertEquals(1, versions.values.toSet().size,
            "com.google.http-client modules disagree (./mvnw dependency:tree"
                    + " -Dincludes='com.google.http-client:*'): " + versions)
    }

    @Test
    fun theOptionsPushNotificationServiceBuildsInitializeFirebase() {
        // No transport set, as in production, so build() takes ApiClientUtils' default
        // transport: the call that throws NoClassDefFoundError for JacksonFactory when
        // google-http-client-jackson2 is missing.
        val app = FirebaseApp.initializeApp(productionOptions().build(), uniqueName())
        try {
            assertNotNull(FirebaseMessaging.getInstance(app))
        } finally {
            app.delete()
        }
    }

    @Test
    fun aSendAndAMulticastReachFcmThroughTheResolvedHttpClient() {
        val bodies: Queue<String> = ConcurrentLinkedQueue()
        // FCM itself, in process: every request answers like a successful v1 send. A fresh
        // response per request, because sendEachForMulticast sends one request per token and a
        // shared MockLowLevelHttpResponse would hand the second an exhausted stream.
        val fcm = object : MockHttpTransport() {
            override fun buildRequest(method: String?, url: String?): LowLevelHttpRequest =
                object : MockLowLevelHttpRequest(url) {
                    override fun execute(): LowLevelHttpResponse {
                        bodies.add(contentAsString)
                        return MockLowLevelHttpResponse()
                            .setContentType("application/json")
                            .setContent("{\"name\":\"projects/test-project/messages/42\"}")
                    }
                }
        }
        val app = FirebaseApp.initializeApp(
            productionOptions().setHttpTransport(fcm).build(), uniqueName())
        try {
            val messaging = FirebaseMessaging.getInstance(app)
            val webpush = WebpushConfig.builder()
                .setNotification(WebpushNotification("Seats freed", "A1 is free"))
                .build()

            // The two calls PushNotificationService makes: send() for the test push,
            // sendEachForMulticast() for a showing's watchers.
            val id = messaging.send(Message.builder()
                .setFid("fid-a").setWebpushConfig(webpush).build())
            val batch = messaging.sendEachForMulticast(MulticastMessage.builder()
                .addAllFids(listOf("fid-b", "fid-c")).setWebpushConfig(webpush)
                .putData("kind", "seats").build())

            assertEquals("projects/test-project/messages/42", id)
            assertEquals(2, batch.successCount)
            for (fid in listOf("fid-a", "fid-b", "fid-c")) {
                // As "fid", not "token": both clients register with Firebase's FID API since
                // 2026-10-23 (register()/onRegistered()), and an FID sent as "token" is not a
                // registration token FCM can address.
                assertTrue(bodies.any { b -> b.contains("\"fid\":\"" + fid + "\"") && b.contains("Seats freed") },
                    "no request carried " + fid + ": " + bodies)
                assertTrue(bodies.none { b -> b.contains("\"token\":\"" + fid + "\"") },
                    "an FID went out as a token: " + bodies)
            }
        } finally {
            app.delete()
        }
    }
}
