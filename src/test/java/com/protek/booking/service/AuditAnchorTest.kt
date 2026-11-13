package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusMock
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.HexFormat
import java.util.UUID

/** AuditAnchorService against a fake authority: what it stamps, and when it stamps nothing. The
 * real reply's checks are TimestampAuthorityTest's. */
@QuarkusTest
class AuditAnchorTest {

    @Inject
    lateinit var anchors: AuditAnchorService
    @Inject
    lateinit var db: Db
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var fixture: TestFixture

    private val stamped = ArrayList<String>()

    @BeforeEach
    fun fakeAuthority() {
        QuarkusMock.installMockForType(object : TimestampAuthority() {
            // Off, so the hourly anchorAll() cannot run into this test; it calls anchor() itself.
            override val enabled: Boolean get() = false
            override fun stamp(sha256: ByteArray): ByteArray {
                stamped.add(HexFormat.of().formatHex(sha256))
                return "reply ${stamped.size}".toByteArray()
            }
        }, TimestampAuthority::class.java)
    }

    @Test
    fun eachRunStampsTheNewestRowsHash_andATenantWithNothingNewIsNotStampedAgain() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val booking = fixture.reservation(tenant, fixture.group(tenant, pool, fixture.customer(tenant), 1),
                fixture.unit(tenant, pool, "A1", "A", 1), fixture.session(tenant, pool, "on_sale"),
                fixture.policy(tenant), BigDecimal("50000.00"))

        val first = anchors.anchor(tenant.tenantId)
        assertEquals(listOf(head(tenant.tenantId)), stamped)
        assertNull(anchors.anchor(tenant.tenantId))

        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.confirm(c, tenant.tenantId, booking.reservationId) }
        val second = anchors.anchor(tenant.tenantId)
        assertEquals(head(tenant.tenantId), stamped.last())

        assertEquals(listOf("$first reply 1", "$second reply 2"), db.inTenantTransaction(tenant.tenantId) { c ->
            db.query(c, "SELECT audit_id, tsa_reply FROM audit_anchor WHERE tenant_id = ? ORDER BY audit_id",
                    { rs -> "${rs.getLong(1)} ${String(rs.getBytes(2))}" }, tenant.tenantId) })
    }

    private fun head(tenantId: UUID): String = db.inTenantTransaction(tenantId) { c ->
        db.query(c, "SELECT row_hash FROM audit_log WHERE tenant_id = ? ORDER BY audit_id DESC LIMIT 1",
                { rs -> rs.getString(1) }, tenantId).single() }
}
