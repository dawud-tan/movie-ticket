package com.protek.booking.payment

import io.quarkus.runtime.annotations.RegisterForReflection

/**
 * The fields this platform reads out of a Midtrans HTTP notification.
 *
 * Deliberately a narrow projection rather than the full payload: Midtrans sends a couple of
 * dozen fields and adds more over time, and binding all of them would mean deserialisation
 * breaking on a field this code never looks at. `rawBody` keeps the complete original for
 * `payment.raw_webhook_payload`, so nothing is lost — the full text is stored for
 * evidentiary purposes even though only these fields drive behaviour.
 *
 * Every field is a String, including `grossAmount`. That is not laziness: the
 * signature is computed over Midtrans's own textual representation, so parsing the amount and
 * re-rendering it would change the digest and reject every genuine notification. Every one is
 * nullable, because a notification may omit any of them and the verifier is what refuses that.
 */
@RegisterForReflection
@JvmRecord
data class MidtransNotification(
    val orderId: String?,
    val transactionId: String?,
    val transactionStatus: String?,
    val fraudStatus: String?,
    val statusCode: String?,
    val grossAmount: String?,
    val signatureKey: String?,
    val paymentType: String?,
    val rawBody: String?,
)
