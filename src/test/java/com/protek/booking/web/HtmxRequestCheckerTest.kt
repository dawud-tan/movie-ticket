package com.protek.booking.web

import io.vertx.core.http.HttpServerRequest
import io.vertx.ext.web.RoutingContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * Plain JUnit, no Quarkus: the checker reads one header. The end-to-end half (a signed-out htmx
 * hold answering 499, a page navigation 302) needs a browser against a running app, because no
 * test profile runs the web tenant's code flow.
 */
class HtmxRequestCheckerTest {

    /* Both are interfaces, so a Proxy answering only getHeader is enough; no mocking library. */
    private fun requestWith(headers: Map<String, String>): RoutingContext {
        val request = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(HttpServerRequest::class.java)) { _, m, args ->
            if (m.name == "getHeader" && args?.get(0) is String) headers[args[0] as String] else throw UnsupportedOperationException(m.name)
        } as HttpServerRequest
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RoutingContext::class.java)) { _, m, _ ->
            if (m.name == "request") request else throw UnsupportedOperationException(m.name)
        } as RoutingContext
    }

    private val checker = HtmxRequestChecker()

    @Test
    fun anHtmxRequest_countsAsJavaScript_soASignedOutOneGetsAStatusNotARedirect() {
        assertTrue(checker.isJavaScriptRequest(requestWith(mapOf("HX-Request" to "true"))))
    }

    @Test
    fun aPageNavigation_withoutTheHeader_isNotJavaScript_andKeepsItsRedirectToSignIn() {
        assertFalse(checker.isJavaScriptRequest(requestWith(emptyMap())))
    }

    @Test
    fun anHxRequestHeaderSayingFalse_isNotJavaScript() {
        assertFalse(checker.isJavaScriptRequest(requestWith(mapOf("HX-Request" to "false"))))
    }
}
