package com.protek.booking.service

import org.bouncycastle.tsp.TSPException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.HexFormat

/**
 * The anchor's request and reply checks, offline, against a real reply: DigiCert's
 * (`booking.audit-anchor.tsa-url`) for sha256("probe"), captured on 2026-11-06 with
 * `openssl ts -query -digest … -sha256 -cert -no_nonce` and curl, and accepted by
 * `openssl ts -verify` against the system CA bundle the same day. No container needed.
 */
class TimestampAuthorityTest {

    private val probe = HexFormat.of().parseHex("ba9c736f19e7f60b7f6764adb0b7908c0a2b394e09b6c09863528c7f2bc86095")
    private val reply = javaClass.getResourceAsStream("/tsa/digicert-probe.tsr")!!.use { it.readAllBytes() }

    /** openssl sends the same request with a NULL in the SHA-256 AlgorithmIdentifier; RFC 5754
     * says to generate it absent and accept both. DigiCert granted this exact form on 2026-11-06,
     * and `openssl ts -verify` accepted the reply. */
    @Test
    fun theRequestIsSha256WithItsParametersAbsent_andAsksForTheCertificates() {
        assertEquals("3037020101302f300b0609608648016503040201" + "0420" +
                "ba9c736f19e7f60b7f6764adb0b7908c0a2b394e09b6c09863528c7f2bc86095" + "0101ff",
                HexFormat.of().formatHex(TimestampAuthority.request(probe).encoded))
    }

    @Test
    fun aGrantedReplyOverTheDigestAskedFor_isKeptWhole() {
        assertArrayEquals(reply, TimestampAuthority.accept(TimestampAuthority.request(probe), reply))
    }

    @Test
    fun aReplyOverAnotherDigest_isRefused() {
        val other = probe.copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertThrows<TSPException> { TimestampAuthority.accept(TimestampAuthority.request(other), reply) }
    }

    @Test
    fun aReplyWhoseSignatureWasAltered_isRefused() {
        // The signature is the reply's last field (SignerInfo, after the certificates).
        val altered = reply.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows<TSPException> { TimestampAuthority.accept(TimestampAuthority.request(probe), altered) }
    }
}
