package com.protek.booking.payment.snap

import com.protek.booking.payment.MidtransJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.math.BigDecimal

/**
 * One refund in `additionalInfo.refundHistory[]`, as a `'04'` notification and `qr-mpm-query` both
 * carry it (sandbox). [refundNo] is Midtrans's id for the refund, the same string the
 * refund call answered as `referenceNo`, so it is the key that counts one refund once whichever
 * path saw it first. [partnerRefundNo] is ours (`partnerReferenceNo` in the history).
 */
data class GatewayRefund(val refundNo: String, val partnerRefundNo: String?, val amount: BigDecimal) {

    companion object {
        /**
         * The history's refunds that went through: `refundStatus` absent (the notification spec's
         * QRIS table has no such field) or `00`. Null when the history is missing or any refund in
         * it lacks a readable `refundNo` or `refundAmount.value`: the history is cumulative, so a
         * partial read could count the same money twice later, and the caller flags it instead.
         * The order's `amount.value` is never a fallback: it stays the original charge after a
         * partial refund, which is the defect this replaced.
         */
        fun history(body: JsonElement?): List<GatewayRefund>? {
            val additional = (body as? JsonObject)?.get("additionalInfo") as? JsonObject
            val entries = additional?.get("refundHistory") as? JsonArray ?: return null
            val refunds = mutableListOf<GatewayRefund>()
            for (entry in entries) {
                val status = MidtransJson.text(entry, "refundStatus")
                if (status != null && status != "00") continue
                val refundNo = MidtransJson.text(entry, "refundNo")?.takeIf { it.isNotBlank() } ?: return null
                val amount = MidtransJson.text((entry as? JsonObject)?.get("refundAmount"), "value")
                    ?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
                refunds += GatewayRefund(refundNo, MidtransJson.text(entry, "partnerReferenceNo"), amount)
            }
            return refunds
        }
    }
}
