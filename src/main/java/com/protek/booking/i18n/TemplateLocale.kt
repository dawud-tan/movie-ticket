package com.protek.booking.i18n

import io.quarkus.qute.EngineBuilder
import io.quarkus.qute.TemplateInstance
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import jakarta.inject.Inject

/**
 * Tells every rendered template which language it is in — the one wire without which
 * {@code {msg:…}} silently renders English forever.
 *
 * <p><b>Why this class has to exist at all.</b> Qute resolves {@code {msg:…}} against the
 * template instance's {@code locale} attribute, falling back to the selected variant's locale
 * and then to the default bundle. {@code quarkus-rest-qute} does read {@code Accept-Language}
 * and run JAX-RS variant selection over it — but the variants it selects between are built by
 * Qute's {@code EngineProducer}, which stamps <em>every</em> template with
 * {@code quarkus.default-locale}. So the negotiation has exactly one language to choose from
 * and always picks it, and there is no {@code LocaleResolver} SPI in Quarkus 3.38 to hook
 * instead. Setting the attribute is the supported way, and this is the one place to set it.
 *
 * <p><b>Every template instance, not every response.</b> The initializer runs when the instance
 * is created, which for a {@code @CheckedTemplate} method is on the request thread inside the
 * resource method — so {@link LanguagePreference} can still see the request. It also runs for
 * templates rendered with no request at all, which is why that bean is application-scoped and
 * answers "no request" with the default language rather than throwing.
 *
 * <p>{@code EngineBuilder} arrives as a CDI event: {@code EngineProducer} takes an
 * {@code Event<EngineBuilder>} and fires it while building the engine.
 */
@ApplicationScoped
class TemplateLocale {

    @Inject
    lateinit var language: LanguagePreference

    fun setLocaleOnEveryTemplateInstance(@Observes builder: EngineBuilder) {
        builder.addTemplateInstanceInitializer { instance ->
            instance.setAttribute(TemplateInstance.LOCALE, language.locale()) }
    }
}
