package com.protek.booking.web

import com.protek.booking.domain.Tenant
import com.protek.booking.domain.TenantBillingPeriod
import com.protek.booking.service.TenantProvisioningService
import com.protek.booking.web.api.AppApiResource
import com.protek.booking.web.customer.AuthResource
import com.protek.booking.web.customer.CityResource
import com.protek.booking.web.customer.PushResource
import com.protek.booking.web.platform.PlatformAdminResource
import jakarta.ws.rs.core.GenericEntity
import jakarta.ws.rs.core.MediaType
import jakarta.ws.rs.core.MultivaluedHashMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Type
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Every REST type this application answers with, pinned to the bytes Jackson wrote for it.
 *
 * **Where the expected strings come from.** On 2026-10-08, before `quarkus-rest-jackson` was
 * removed, each type here was still a Java record. The same values were serialised by Jackson
 * 2.21.7 configured exactly as quarkus-jackson 3.40.1 configures its `ObjectMapper` (JavaTimeModule,
 * Jdk8Module, ParameterNamesModule; unknown properties ignored, dates not timestamps, UTC), and the
 * output was pasted below verbatim. Jackson's time module is gone from the classpath now, so it
 * cannot be asked again; these strings are the record of what it said.
 *
 * **Why it matters.** Clients written against what Jackson sent parse these bodies, so the
 * bytes — not merely the meaning — stay the same, control-character escapes included. The values are chosen for the
 * places kotlinx could have differed: nulls, a fraction-second instant, a `BigDecimal` with scale
 * and one already stripped to `5E+4`, `<`, `&`, `é`, and U+001F.
 *
 * The [json] instance is built the way Quarkus's `JsonProducer` builds the one `RestJsonWriter`
 * receives: kotlinx's defaults except `encodeDefaults = true` (`quarkus.kotlin-serialization.json.*`,
 * all at their defaults in this application). The endpoint tests — `AppApiTest`,
 * `PushSubscribeEndpointTest` — exercise the real bean through HTTP.
 */
class RestJsonWriterTest {

    private val json = Json { encodeDefaults = true }

    private val writer = RestJsonWriter().also { it.json = json }

    private val u1 = UUID.fromString("0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60")
    private val u2 = UUID.fromString("a1b2c3d4-e5f6-4789-8abc-def012345678")
    private val t1 = Instant.parse("2099-06-07T12:00:00Z")
    private val t2 = Instant.parse("2099-06-07T14:05:30.123456Z")

    private fun write(entity: Any, genericType: Type = entity.javaClass): String {
        assertTrue(writer.isWriteable(entity.javaClass, genericType, arrayOf(), MediaType.APPLICATION_JSON_TYPE),
            "RestJsonWriter must claim " + genericType.typeName)
        val out = ByteArrayOutputStream()
        writer.writeTo(entity, entity.javaClass, genericType, arrayOf(), MediaType.APPLICATION_JSON_TYPE,
            MultivaluedHashMap(), out)
        return out.toString(Charsets.UTF_8)
    }

    private fun returnTypeOf(type: Class<*>, method: String, vararg parameters: Class<*>): Type =
        type.getMethod(method, *parameters).genericReturnType

    // --- Escaping ---------------------------------------------------------------------

    @Test
    fun aControlCharacterIsEscapedInJacksonsUppercase_whichKotlinxAloneWouldNotDo() {
        // The one place kotlinx's own writer and Jackson's disagree on valid JSON, and the reason
        // RestJsonWriter renders through JacksonText instead of letting the extension write.
        // U+001F is what a QRIS payload carries; Jackson wrote its escape with an uppercase hex digit.
        val chosen = CityResource.Chosen("00020101\u001f")
        val kotlinxAlone = json.encodeToString(CityResource.Chosen.serializer(), chosen)
        assertEquals(JACKSON_CONTROL.replace("\\u001F", "\\u001f"), kotlinxAlone,
            "kotlinx on its own differs from Jackson by the case of one hex digit, and nothing else")
        assertEquals(JACKSON_CONTROL, write(chosen))
    }

    // --- The browser's and the operator's JSON --------------------------------------------

    @Test
    fun pushAuthAndCityAnswersAreWhatJacksonWrote() {
        assertEquals(JACKSON_PUSHCONFIG, write(PushResource.PushConfig(false, null, "k", "d", "p", "s", "m", "a")))
        val results = object : GenericEntity<List<PushResource.SendResult>>(
            listOf(PushResource.SendResult("12345678", "mid", null))) {}
        assertEquals(JACKSON_SENDRESULTS, write(results.entity, results.type))
        assertEquals(JACKSON_WHOAMI, write(AuthResource.WhoAmI("a@b.c", null, u1, null)))
        assertEquals(JACKSON_CHOSEN, write(CityResource.Chosen(null)))
    }

    @Test
    fun thePlatformOperatorsAnswersAreWhatJacksonWrote() {
        assertEquals(JACKSON_SUMMARY, write(
            listOf(PlatformAdminResource.TenantSummary(u1, "demo", "Demo", "active", "shared", "shared", "subdomain",
                null, true)),
            returnTypeOf(PlatformAdminResource::class.java, "listTenants")))
        assertEquals(JACKSON_BILLING, write(
            listOf(TenantBillingPeriod(u1, u2, LocalDate.of(2099, 6, 1), LocalDate.of(2099, 6, 30), "STARTER",
                BigDecimal("1500000.00"), "IDR", "pending", null, t1, null)),
            returnTypeOf(PlatformAdminResource::class.java, "billingHistory", UUID::class.java)))
        val tenant = Tenant(u1, "demo", "Demo", "hospitality", "shared", "shared", "subdomain", null, null, null,
            "ID", "+62", null, "active", false, null)
        assertEquals(JACKSON_PROVISIONED, write(
            TenantProvisioningService.ProvisionResult(tenant, u1, u2, u1, listOf("Step 1"))))
    }

    // --- What the writer claims -----------------------------------------------------------

    @Test
    fun theWriterClaimsOnlyThisApplicationsTypes_soHandBuiltStringsKeepTheirOwnWriters() {
        val jsonType = MediaType.APPLICATION_JSON_TYPE
        assertFalse(writer.isWriteable(String::class.java, String::class.java, arrayOf(), jsonType),
            "a String body is JSON built by hand (the webhooks, /push/subscribe) and must go out as is")
        assertFalse(writer.isWriteable(ArrayList::class.java, ArrayList::class.java, arrayOf(), jsonType),
            "a bare list says nothing about its elements — PushResource wraps one in a GenericEntity")
        assertFalse(writer.isWriteable(CityResource.Chosen::class.java, CityResource.Chosen::class.java, arrayOf(),
            MediaType.TEXT_HTML_TYPE))
    }

    // --- Request bodies keep Jackson's leniency -------------------------------------------

    @Test
    fun anUnknownKeyIsIgnoredAndAMissingOneIsNull_asJacksonBoundThem() {
        val hold = json.decodeFromString(AppApiResource.HoldRequest.serializer(),
            """{"seatIds":["$u1"],"addedByANewerApp":true}""")
        assertEquals(listOf(u1), hold.seatIds)
        assertNull(json.decodeFromString(AppApiResource.HoldRequest.serializer(), "{}").seatIds)

        json.decodeFromString(AppApiResource.Empty.serializer(), """{"anything":1}""")

        val provision = json.decodeFromString(TenantProvisioningService.ProvisionRequest.serializer(),
            """{"slug":"demo","planTier":"STARTER","notAField":"x"}""")
        assertEquals("demo", provision.slug)
        assertEquals(TenantProvisioningService.PlanTier.STARTER, provision.planTier)
        assertNull(provision.customDomain, "validate(), not the reader, says what is missing")

        assertNull(json.decodeFromString(PlatformAdminResource.DisclosureRequest.serializer(),
            """{"disclosed":null}""").disclosed, "null, which the resource reads as false, as Jackson did")
    }

    @Test
    fun aMalformedValueIsASerializationException_whichMalformedJsonInterceptorAnswersWith400() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(PushResource.SubscribeRequest.serializer(), """{"poolId":"not-a-uuid"}""")
        }
        assertThrows(SerializationException::class.java) {
            json.decodeFromString(TenantProvisioningService.ProvisionRequest.serializer(), """{"planTier":"starter"}""")
        }
    }

    companion object {
        /** Jackson's escape for U+001F: `\u` and the hex digits in uppercase. */
        private const val JACKSON_CONTROL =
            """{"city":"00020101\u001F"}"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_PUSHCONFIG =
            """{"enabled":false,"vapidKey":null,"apiKey":"k","authDomain":"d","projectId":"p","storageBucket":"s","messagingSenderId":"m","appId":"a"}"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_SENDRESULTS =
            """[{"tokenSuffix":"12345678","messageId":"mid","error":null}]"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_WHOAMI =
            """{"email":"a@b.c","role":null,"appUserId":"0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60","customerId":null}"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_CHOSEN =
            """{"city":null}"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_SUMMARY =
            """[{"tenantId":"0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60","slug":"demo","displayName":"Demo","status":"active","computeMode":"shared","dataMode":"shared","domainMode":"subdomain","pseRegistrationNo":null,"otherSalesChannelsDisclosed":true}]"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_BILLING =
            """[{"tenantBillingPeriodId":"0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60","tenantId":"a1b2c3d4-e5f6-4789-8abc-def012345678","periodStart":"2099-06-01","periodEnd":"2099-06-30","planTier":"STARTER","amountDue":1500000.00,"currency":"IDR","status":"pending","invoicedAt":null,"paidAt":"2099-06-07T12:00:00Z","paymentReference":null}]"""

        /** Jackson 2.21.7, configured as quarkus-jackson 3.40.1 configures it, on the Java record. */
        private const val JACKSON_PROVISIONED =
            """{"tenant":{"tenantId":"0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60","slug":"demo","displayName":"Demo","vertical":"hospitality","computeMode":"shared","dataMode":"shared","domainMode":"subdomain","customDomain":null,"dedicatedDbHost":null,"dedicatedDbName":null,"jurisdiction":"ID","complaintPhone":"+62","pseRegistrationNo":null,"status":"active","otherSalesChannelsDisclosed":false,"otherSalesChannelsDetail":null},"walkInCustomerId":"0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60","staffAppUserId":"a1b2c3d4-e5f6-4789-8abc-def012345678","agreementAcceptanceId":"0f6d2f0e-3c3a-4b8e-9a51-2a7c1d4e5f60","manualFollowUp":["Step 1"]}"""
    }
}
