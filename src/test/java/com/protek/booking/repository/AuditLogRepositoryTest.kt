package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/** Rows here are written exclusively by the audit triggers — this test exercises them
 * through the repository layer's own writes, then verifies the chain they produce the way a
 * court's expert would: sha256 over the content text for a row they may read, the content hash
 * alone for a row they may not, and each row hash from the one before it. */
@QuarkusTest
class AuditLogRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var auditRepo: AuditLogRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var fixture: TestFixture

    /** One row as the database holds it; [content] is what audit_content() hashes. */
    private data class Link(val auditId: Long, val entityId: UUID, val rowHash: String, val content: String)

    @Test
    fun findByEntity_returnsChainedRowsForInsertAndConfirm() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val unit = fixture.unit(tenant, pool, "A1", "A", 1)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val customer = fixture.customer(tenant)
        val group = fixture.group(tenant, pool, customer, 1)
        val created = fixture.reservation(tenant, group, unit, session, policy, PRICE)
        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.confirm(c, tenant.tenantId, created.reservationId) }

        val entries = db.inTenantTransaction(tenant.tenantId) { c ->
            auditRepo.findByEntity(c, tenant.tenantId, "reservation", created.reservationId) }

        assertEquals(2, entries.size)
        assertEquals("INSERT", entries[0].action)
        assertEquals("UPDATE", entries[1].action)
        val chain = chain(tenant.tenantId)
        assertEquals(entries.map { it.rowHash }, chain.map { it.rowHash })
        assertEquals(emptyList<Long>(), walk(chain) { true })
    }

    @Test
    fun oneBookingVerifies_withAnotherCustomersRowsHandedOverAsTheirContentHashOnly() {
        val tenant = fixture.tenant()
        val pool = fixture.pool(tenant)
        val session = fixture.session(tenant, pool, "on_sale")
        val policy = fixture.policy(tenant)
        val disputed = fixture.reservation(tenant, fixture.group(tenant, pool, fixture.customer(tenant), 1),
                fixture.unit(tenant, pool, "A1", "A", 1), session, policy, PRICE)
        val other = fixture.reservation(tenant, fixture.group(tenant, pool, fixture.customer(tenant), 1),
                fixture.unit(tenant, pool, "A2", "A", 2), session, policy, PRICE)
        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.confirm(c, tenant.tenantId, disputed.reservationId) }
        db.inTenantTransaction(tenant.tenantId) { c -> reservationRepo.confirm(c, tenant.tenantId, other.reservationId) }

        // Interleaved, so proving the disputed booking's second row means walking through the
        // other customer's first one.
        val chain = chain(tenant.tenantId)
        assertEquals(listOf(disputed.reservationId, other.reservationId, disputed.reservationId, other.reservationId),
                chain.map { it.entityId })
        val readable = { link: Link -> link.entityId == disputed.reservationId }
        assertEquals(emptyList<Long>(), walk(chain, readable))

        // And the content is really checked: one character of a readable row changed is caught there.
        val tampered = chain.map { if (it == chain[2]) it.copy(content = it.content.replace("confirmed", "cancelled")) else it }
        assertEquals(listOf(chain[2].auditId), walk(tampered, readable))
    }

    private fun chain(tenantId: UUID): List<Link> = db.inTenantTransaction(tenantId) { c ->
        db.query(c, "SELECT audit_id, entity_id, row_hash, audit_content(a) AS content " +
                "FROM audit_log a WHERE tenant_id = ? ORDER BY audit_id",
                { rs -> Link(rs.getLong("audit_id"), rs.getObject("entity_id", UUID::class.java),
                        rs.getString("row_hash"), rs.getString("content")) }, tenantId) }

    /** The rows whose stored hash a verifier cannot reproduce. A row [readable] says they may not
     * see contributes only its content hash, which is what an export would hand over for it. */
    private fun walk(chain: List<Link>, readable: (Link) -> Boolean): List<Long> {
        val broken = ArrayList<Long>()
        var prior = ""
        for (link in chain) {
            val contentHash = if (readable(link)) sha256Hex(link.content) else storedContentHash(link)
            if (sha256Hex(prior + contentHash) != link.rowHash) broken.add(link.auditId)
            prior = link.rowHash
        }
        return broken
    }

    /** What the operator would export for a row the verifier may not read. */
    private fun storedContentHash(link: Link): String = sha256Hex(link.content)

    private fun sha256Hex(input: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8)))

    companion object {
        private val PRICE = BigDecimal("50000.00")
    }
}
