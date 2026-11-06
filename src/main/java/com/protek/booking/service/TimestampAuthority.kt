package com.protek.booking.service

import jakarta.enterprise.context.ApplicationScoped
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.tsp.TSPAlgorithms
import org.bouncycastle.tsp.TSPException
import org.bouncycastle.tsp.TimeStampRequest
import org.bouncycastle.tsp.TimeStampRequestGenerator
import org.bouncycastle.tsp.TimeStampResponse
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Optional

/**
 * An RFC 3161 timestamp authority, which AuditAnchorService asks to sign each tenant's audit-chain
 * head. Off unless `booking.audit-anchor.tsa-url` is set (prod only): tests and dev mode send
 * nothing out.
 *
 * A reply is kept only once it is checked here: granted, over the digest asked for, and signed by
 * the certificate it carries. Whether that certificate chains to a root the court trusts is the
 * verifier's question, answered offline with `openssl ts -verify`, not at write time.
 */
@ApplicationScoped
class TimestampAuthority {

    @ConfigProperty(name = "booking.audit-anchor.tsa-url")
    lateinit var tsaUrl: Optional<String>

    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    val enabled: Boolean get() = tsaUrl.isPresent

    /** The authority's whole reply for [sha256], a 32-byte digest. Throws on anything else. */
    fun stamp(sha256: ByteArray): ByteArray {
        val request = request(sha256)
        val response = http.send(HttpRequest.newBuilder(URI.create(tsaUrl.get()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/timestamp-query")
                .POST(HttpRequest.BodyPublishers.ofByteArray(request.encoded))
                .build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() != 200) {
            throw TSPException("timestamp authority answered HTTP ${response.statusCode()}")
        }
        return accept(request, response.body())
    }

    companion object {
        /** No nonce: the digest is a chain hash, never asked for twice, so a replayed reply has
         * nothing to stand in for. certReq, so the reply carries the certificates that verify it. */
        fun request(sha256: ByteArray): TimeStampRequest =
            TimeStampRequestGenerator().apply { setCertReq(true) }.generate(TSPAlgorithms.SHA256, sha256)

        fun accept(request: TimeStampRequest, reply: ByteArray): ByteArray {
            val response = TimeStampResponse(reply)
            // validate() passes a refusal that carries no token, so the null check is not redundant.
            response.validate(request)
            val token = response.timeStampToken
                    ?: throw TSPException("timestamp authority refused: ${response.statusString}")
            @Suppress("UNCHECKED_CAST")
            val signer = token.certificates.getMatches(token.sid as org.bouncycastle.util.Selector<X509CertificateHolder>)
                    .singleOrNull() ?: throw TSPException("reply does not carry its signer's certificate")
            token.validate(JcaSimpleSignerInfoVerifierBuilder().build(signer))
            return reply
        }
    }
}
