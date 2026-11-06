package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.support.TestFixture
import io.quarkus.test.junit.QuarkusTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@QuarkusTest
class CustomerRepositoryTest {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var repo: CustomerRepository
    @Inject
    lateinit var fixture: TestFixture

    @Test
    fun insert_returnsPersistedCustomer() {
        val tenant = fixture.tenant()

        val customer = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "Budi Santoso", "+6281234567890", "budi@example.com", true) }

        assertNotNull(customer.customerId)
        assertEquals("Budi Santoso", customer.fullName)
        assertTrue(customer.dateOfBirthAttested)
    }

    @Test
    fun findByPhone_returnsRow() {
        val tenant = fixture.tenant()
        val inserted = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.insert(c, tenant.tenantId, "Siti", "+6281111111111", null, false) }

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findByPhone(c, tenant.tenantId, "+6281111111111") }

        assertTrue(found.isPresent)
        assertEquals(inserted.customerId, found.get().customerId)
    }

    @Test
    fun findByPhone_unknown_returnsEmpty() {
        val tenant = fixture.tenant()

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findByPhone(c, tenant.tenantId, "+6289999999999") }

        assertTrue(found.isEmpty)
    }

    @Test
    fun findById_returnsRow() {
        val tenant = fixture.tenant()
        val customer = fixture.customer(tenant)

        val found = db.inTenantTransaction(tenant.tenantId) { c ->
            repo.findById(c, tenant.tenantId, customer.customerId) }

        assertTrue(found.isPresent)
        assertEquals(customer.customerId, found.get().customerId)
    }
}
