package com.protek.booking.payment

/**
 * The production verifier with a fixed server key, so signatures a [StubMidtransGateway]
 * produces actually validate.
 *
 * Its own public class rather than a nested helper because the configured key is the
 * `__unset__` sentinel under test, which the real verifier correctly treats as unconfigured
 * and rejects everything with — so any test that drives a settlement needs this, including tests
 * in other packages that cannot see `serverKey`. It had already been copied once.
 *
 * Extends the real verifier rather than stubbing the interface: `QuarkusMock
 * .installMockForType` resolves by bean class, so the replacement must be assignable to it.
 */
class TestSignatureVerifier(key: String?) : MidtransSignatureVerifier() {

    init {
        this.serverKey = key
    }
}
