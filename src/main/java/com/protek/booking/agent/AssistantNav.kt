package com.protek.booking.agent

import com.protek.booking.security.Roles
import com.protek.booking.web.customer.ChatResource
import io.quarkus.security.identity.SecurityIdentity
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.context.ContextNotActiveException
import jakarta.enterprise.inject.Instance
import jakarta.inject.Inject
import jakarta.inject.Named
import org.eclipse.microprofile.config.inject.ConfigProperty

/**
 * Whether to offer the `/chat` link in the header: the assistant is configured and the caller is
 * signed in as someone who may use it. Its own bean for the reason `StaffNav` gives (a new method on
 * `CurrentActor` called from a template renders every page 500). UX, not the boundary:
 * `ChatResource` is `@RolesAllowed` and 404s while unconfigured, whatever this says.
 */
@ApplicationScoped
@Named("assistantNav")
class AssistantNav {

    @Inject
    lateinit var identity: Instance<SecurityIdentity>

    @ConfigProperty(name = "quarkus.langchain4j.openai.api-key")
    lateinit var apiKey: String

    fun visible(): Boolean {
        if (apiKey == ChatResource.UNSET) return false
        try {
            if (!identity.isResolvable) return false
            val current: SecurityIdentity? = identity.get()
            return current != null && !current.isAnonymous &&
                (current.hasRole(Roles.CUSTOMER) || current.hasRole(Roles.TENANT_STAFF))
        } catch (e: ContextNotActiveException) {
            return false
        }
    }
}
