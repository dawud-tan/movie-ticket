package com.protek.booking.web

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.util.Optional

/**
 * Where the "Sign in" link points. Exists because sign-in is the one flow that cannot be
 * scheme-agnostic, even though ordinary browsing is.
 *
 * <p><b>The problem.</b> This server listens on plain HTTP 8080 and HTTPS 8443 at once, with no
 * HSTS and no redirect between them, so a visitor may be on either. Google rejects any OAuth
 * {@code redirect_uri} that is not https, and
 * {@code quarkus.oidc.authentication.force-redirect-https-scheme} fixes the <em>scheme</em> — but
 * not the <em>port</em>. Verified directly: a request to a protected path over {@code http://…:8080}
 * produces {@code redirect_uri=https://…:8080/callback}. That URI is unusable twice over — it is not
 * the one registered in the Google console, and even if it were registered the browser would then
 * attempt TLS against the plaintext port and fail.
 *
 * <p><b>The fix.</b> Point sign-in at the https origin explicitly. Browsing stays on whatever
 * scheme the client chose; only the login hop is pinned, which is the one hop that has to be.
 *
 * <p>A consequence worth knowing rather than being surprised by: the OIDC session cookie is set
 * during the callback on the https origin, so it carries the {@code Secure} flag and is not sent
 * over http. A signed-in session therefore lives on https, while http remains the anonymous
 * public-browse entry point. The alternative — forcing the session cookie insecure so it works on
 * both — would put a session token on the wire in plaintext, which is not a trade worth making to
 * tidy up a demo.
 *
 * <p>Unset by default, which falls back to a relative {@code /login}. That is right for local
 * development, where the app is reached at {@code http://localhost} (the sole origin Google exempts
 * from its https rule) and OIDC is usually off entirely.
 */
@Named("signIn")
@ApplicationScoped
class SignInLink {

    /* Optional<String>, not String with defaultValue="". The property is declared in
     * application.properties as ${PUBLIC_HTTPS_BASE_URL:}, so when the env var is unset the property
     * IS defined and its value is empty — SmallRye converts empty to null, and a plain String
     * injection point then fails the whole deployment with "Failed to load config value of type
     * class java.lang.String". A defaultValue does not save it, because the property is present.
     * Caught by three tests refusing to start Quarkus. */
    @ConfigProperty(name = "booking.public.https-base-url")
    lateinit var httpsBaseUrl: Optional<String>

    /** The href for the sign-in anchor: absolute onto the https origin when one is configured. */
    fun url(): String {
        // Java's strip().
        val base = httpsBaseUrl.orElse("").trim { Character.isWhitespace(it) }
        if (base.isEmpty()) {
            return "/login"
        }
        // Tolerate a trailing slash in config rather than emitting "//login", which resolves as a
        // protocol-relative URL to a host named "login".
        return if (base.endsWith("/")) base + "login" else "$base/login"
    }
}
