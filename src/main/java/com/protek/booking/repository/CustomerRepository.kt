package com.protek.booking.repository

import com.protek.booking.db.Db
import com.protek.booking.domain.Customer
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.sql.Connection
import java.sql.ResultSet
import java.util.Optional
import java.util.UUID

@ApplicationScoped
class CustomerRepository {

    @Inject
    lateinit var db: Db

    fun insert(c: Connection, tenantId: UUID?, fullName: String?, phone: String?,
               email: String?, dateOfBirthAttested: Boolean): Customer =
        db.queryOne(c,
            "INSERT INTO customer (tenant_id, full_name, phone, email, date_of_birth_attested) " +
                    "VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS,
            ::map, tenantId, fullName, phone, email, dateOfBirthAttested
        ).orElseThrow()

    fun findByPhone(c: Connection, tenantId: UUID?, phone: String?): Optional<Customer> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM customer WHERE tenant_id = ? AND phone = ?",
            ::map, tenantId, phone)

    fun findByEmail(c: Connection, tenantId: UUID?, email: String?): Optional<Customer> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM customer WHERE tenant_id = ? AND lower(email) = lower(?)",
            ::map, tenantId, email)

    /**
     * The per-tenant "Walk-in" placeholder that counter sales attach to. Created at
     * provisioning, so a missing one is a provisioning defect rather than something to
     * create on demand — hence [Optional] for the caller to fail loudly on, instead of
     * a silent get-or-create that would mask a half-provisioned tenant.
     */
    fun findWalkInPlaceholder(c: Connection, tenantId: UUID?): Optional<Customer> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM customer WHERE tenant_id = ? AND is_walk_in_placeholder",
            ::map, tenantId)

    /** Provisioning-time insert of the walk-in placeholder. The partial unique index
     * `idx_customer_walk_in_per_tenant` rejects a second one for the same tenant, so a
     * re-run of provisioning fails here rather than splitting counter-sale history. */
    fun insertWalkInPlaceholder(c: Connection, tenantId: UUID?): Customer =
        db.queryOne(c,
            "INSERT INTO customer (tenant_id, full_name, is_walk_in_placeholder) " +
                    "VALUES (?, 'Walk-in Customer', true) RETURNING " + COLUMNS,
            ::map, tenantId
        ).orElseThrow()

    fun findById(c: Connection, tenantId: UUID?, customerId: UUID?): Optional<Customer> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM customer WHERE tenant_id = ? AND customer_id = ?",
            ::map, tenantId, customerId)

    /**
     * The same row, locked for the rest of the transaction — the per-day purchase cap.
     *
     * **Why a lock at all.** The daily cap is the one check in a hold that is decided by
     * *counting* rather than by a constraint. Under READ COMMITTED two of this customer's
     * holds running at once each see the other's rows as absent, so both pass at 9 of 10 and
     * eleven seats land. A scalper with two tabs open is the realistic case, not a contrived
     * one — it is the whole population the cap exists for.
     *
     * **Why the customer row rather than something in `reservation`.** There is no
     * row to lock on the thing being counted: the rows that would breach the cap do not exist
     * yet. Locking the identity the count is scoped to serialises exactly the transactions that
     * could race each other and nothing else — one person's concurrent holds, which is a handful
     * of requests ever, never two different customers.
     *
     * Taken **after** the session's `FOR UPDATE` (see `BookingService`), which
     * is the lock order every booking path uses; nothing anywhere locks a customer before a
     * session, so there is no cycle to deadlock on.
     *
     * Not used by the counter, which is exempt from the cap and would otherwise serialise
     * every walk-in sale in the building behind one placeholder row.
     */
    fun findByIdForUpdate(c: Connection, tenantId: UUID?, customerId: UUID?): Optional<Customer> =
        db.queryOne(c,
            "SELECT " + COLUMNS + " FROM customer WHERE tenant_id = ? AND customer_id = ? FOR UPDATE",
            ::map, tenantId, customerId)

    /**
     * Records that this customer has attested to their date of birth.
     *
     * The column has existed since V1 with nothing writing it. It is a durable summary —
     * "this person has attested at least once" — and emphatically **not** a permission:
     * one boolean cannot distinguish 13+ from 21+, so nothing may read it to skip asking again.
     * The row that answers "attested to what, and when" is the `consent_record` written
     * beside it. See `AgeClassification` for the whole of that argument.
     *
     * Guarded by `NOT date_of_birth_attested` so a repeat attestation is not a write:
     * the fact is already true, and the evidence is the consent row, not this flag.
     */
    fun markDateOfBirthAttested(c: Connection, tenantId: UUID?, customerId: UUID?): Int =
        db.execute(c,
            "UPDATE customer SET date_of_birth_attested = true " +
                    "WHERE tenant_id = ? AND customer_id = ? AND NOT date_of_birth_attested",
            tenantId, customerId)

    /**
     * Records a date of birth learned from the identity provider.
     *
     * `IS NULL` in the WHERE clause, so this only ever fills a blank. A value already
     * on the row was either supplied by a previous sign-in or entered by staff, and silently
     * overwriting it on every login would make the column say "whatever the last token
     * happened to carry" rather than "what we know about this person".
     */
    fun fillDateOfBirth(c: Connection, tenantId: UUID?, customerId: UUID?,
                        dateOfBirth: java.time.LocalDate?): Int =
        db.execute(c,
            "UPDATE customer SET date_of_birth = ? " +
                    "WHERE tenant_id = ? AND customer_id = ? AND date_of_birth IS NULL",
            java.sql.Date.valueOf(dateOfBirth), tenantId, customerId)

    /**
     * Records a date of birth the customer entered themselves on `/profile`.
     *
     * **No `IS NULL` guard, unlike [fillDateOfBirth], and that difference is
     * the reason there are two methods rather than one.** That one runs on every sign-in and
     * must never overwrite, or the column would come to mean "whatever the last token happened
     * to carry". This one runs only when somebody deliberately submitted a form, and a customer
     * who mistyped their birth year has to be able to correct it: a date that can be set once
     * and never fixed is worse than no date at all, because every age derived from it is then
     * permanently and visibly wrong.
     *
     * A null clears the column. A customer who would rather not tell us gets to stop telling
     * us — data minimisation makes that their call and not ours.
     */
    fun setDateOfBirth(c: Connection, tenantId: UUID?, customerId: UUID?,
                       dateOfBirth: java.time.LocalDate?): Int {
        if (dateOfBirth == null) {
            // A literal NULL rather than a bound one. pgjdbc infers a parameter's type from the
            // value, and an untyped null is the case it cannot always infer; a clear is rare
            // enough that a second statement costs nothing worth measuring.
            return db.execute(c,
                "UPDATE customer SET date_of_birth = NULL " +
                        "WHERE tenant_id = ? AND customer_id = ?",
                tenantId, customerId)
        }
        return db.execute(c,
            "UPDATE customer SET date_of_birth = ? " +
                    "WHERE tenant_id = ? AND customer_id = ?",
            java.sql.Date.valueOf(dateOfBirth), tenantId, customerId)
    }

    private fun map(rs: ResultSet): Customer {
        val dateOfBirth = rs.getDate("date_of_birth")
        return Customer(
            rs.getObject("customer_id", UUID::class.java),
            rs.getObject("tenant_id", UUID::class.java),
            rs.getString("full_name"),
            rs.getString("phone"),
            rs.getString("email"),
            rs.getBoolean("date_of_birth_attested"),
            dateOfBirth?.toLocalDate(),
            rs.getBoolean("is_walk_in_placeholder"),
            rs.getTimestamp("created_at").toInstant(),
        )
    }

    private companion object {
        const val COLUMNS =
            "customer_id, tenant_id, full_name, phone, email, date_of_birth_attested, " +
                    "date_of_birth, is_walk_in_placeholder, created_at"
    }
}
