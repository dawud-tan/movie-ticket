package com.protek.booking.payment

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.time.Instant

/**
 * The gateway refunds already counted against one payment, kept in `raw_webhook_payload` under
 * `_refundsApplied` beside the earlier `_gatewayRefunds` (no migration). Each entry is one Midtrans
 * `refundNo` with its amount and when we counted it. Both paths that learn of a refund — the
 * `'04'`'s history and the refund call's own 2007800 answer — check this list under the payment's
 * `FOR UPDATE` lock, so one refund reduces a debt once whichever arrives first.
 */
object RefundLedger {

    const val KEY = "_refundsApplied"

    data class Applied(val refundNo: String, val amount: BigDecimal, val at: Instant?)

    /**
     * `RefundService`'s state for one payment, under `_autoRefund`. [seq] numbers the
     * `partnerRefundNo` and moves on only when that refund is counted; [tries] counts its sends;
     * [amount] is what that number asks for, null between refunds; [refused] stops the sweep, with
     * Midtrans's code (or `no-answer`).
     */
    @Serializable
    data class AutoRefund(val seq: Int = 1, val tries: Int = 0, val amount: String? = null, val refused: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    fun autoRefund(rawPayload: String?): AutoRefund {
        val root = rawPayload?.let { runCatching { MidtransJson.parse(it) }.getOrNull() } as? JsonObject
        val state = root?.get("_autoRefund") as? JsonObject ?: return AutoRefund()
        return runCatching { json.decodeFromJsonElement(AutoRefund.serializer(), state) }.getOrDefault(AutoRefund())
    }

    fun encode(state: AutoRefund): String = json.encodeToString(AutoRefund.serializer(), state)

    fun applied(rawPayload: String?): List<Applied> {
        val root = rawPayload?.let { runCatching { MidtransJson.parse(it) }.getOrNull() } as? JsonObject
        val entries = root?.get(KEY) as? JsonArray ?: return emptyList()
        return entries.mapNotNull { e ->
            val no = MidtransJson.text(e, "refundNo") ?: return@mapNotNull null
            val amount = MidtransJson.text(e, "amount")?.toBigDecimalOrNull() ?: return@mapNotNull null
            Applied(no, amount, MidtransJson.text(e, "at")?.let { runCatching { Instant.parse(it) }.getOrNull() })
        }
    }

    /** New entries as a JSON array, for the repository to append. Amounts as plain strings, the way
     * Midtrans sends them, so no number is re-rendered on the way. */
    fun entries(refunds: List<Pair<String, BigDecimal>>, at: Instant): String = MidtransJson.render(buildJsonArray {
        for ((no, amount) in refunds) add(buildJsonObject {
            put("refundNo", no)
            put("amount", JsonPrimitive(amount.toPlainString()))
            put("at", at.toString())
        })
    })
}
