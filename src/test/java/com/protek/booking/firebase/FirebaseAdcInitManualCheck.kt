package com.protek.booking.firebase

import com.google.api.client.json.gson.GsonFactory
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Manual, on-demand check that the Firebase Admin SDK initializes via Application
 * Default Credentials (GOOGLE_APPLICATION_CREDENTIALS env var — no path in code
 * anywhere here, [GoogleCredentials.getApplicationDefault] finds it itself).
 *
 * Deliberately named so Surefire's default `**&#47;*Test.java` include pattern
 * does NOT pick this up as part of the normal `mvn test` run — most environments
 * running this app have no Google credentials configured at all, and this isn't a
 * shipped code path. Surefire matches that pattern against the compiled class, so it holds
 * for a `.kt` too. Run it explicitly:
 * ```
 * mvn test -Dtest=FirebaseAdcInitManualCheck
 * ```
 */
class FirebaseAdcInitManualCheck {

    @Test
    fun firebaseAppInitializesViaApplicationDefaultCredentials() {
        val credentials = GoogleCredentials.getApplicationDefault()
        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            // GsonFactory, as PushNotificationService sets it, so this checks the options
            // production builds. It does not make google-http-client-jackson2 optional:
            // FirebaseOptions loads JacksonFactory regardless, and the module is on the main
            // classpath (pom.xml's comment on it says why).
            .setJsonFactory(GsonFactory.getDefaultInstance())
            .build()
        val app = FirebaseApp.initializeApp(options, "adc-check-" + System.nanoTime())
        assertNotNull(app)
        app.delete()
    }
}
