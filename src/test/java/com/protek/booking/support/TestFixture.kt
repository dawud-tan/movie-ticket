package com.protek.booking.support

import com.protek.booking.db.Db
import com.protek.booking.domain.AppUser
import com.protek.booking.domain.ConsentRecord
import com.protek.booking.domain.Customer
import com.protek.booking.domain.Film
import com.protek.booking.domain.PolicyDocument
import com.protek.booking.domain.PublicHoliday
import com.protek.booking.domain.RateRule
import com.protek.booking.domain.Reservation
import com.protek.booking.domain.ReservationGroup
import com.protek.booking.domain.ResourcePool
import com.protek.booking.domain.ResourcePoolSession
import com.protek.booking.domain.ResourceUnit
import com.protek.booking.domain.Site
import com.protek.booking.domain.Tenant
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.ConsentRecordRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.FilmRepository
import com.protek.booking.repository.PolicyDocumentRepository
import com.protek.booking.repository.PublicHolidayRepository
import com.protek.booking.repository.RateRuleRepository
import com.protek.booking.repository.ReservationGroupRepository
import com.protek.booking.repository.ReservationRepository
import com.protek.booking.repository.ResourcePoolRepository
import com.protek.booking.repository.ResourcePoolSessionRepository
import com.protek.booking.repository.ResourceUnitRepository
import com.protek.booking.repository.SiteRepository
import com.protek.booking.repository.TenantRepository
import com.protek.booking.seo.Slug
import com.protek.booking.service.AgeClassification
import com.protek.booking.service.RatePlan
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import java.math.BigDecimal
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.EnumMap
import java.util.UUID

/**
 * Shared test scaffolding: every repository test needs a tenant, and most need a pool,
 * a seat, a session, a customer, etc. below it. Each method opens its own short
 * transaction, mirroring how the application itself would create these rows.
 *
 * Kotlin since 2026-10-16. Java tests still call every
 * public method here, so the signatures are the Java ones: `String?` wherever the Java would
 * have passed a null through to the repository (pattern 6).
 */
@ApplicationScoped
class TestFixture {

    @Inject
    lateinit var db: Db
    @Inject
    lateinit var tenantRepo: TenantRepository
    @Inject
    lateinit var poolRepo: ResourcePoolRepository
    @Inject
    lateinit var unitRepo: ResourceUnitRepository
    @Inject
    lateinit var sessionRepo: ResourcePoolSessionRepository
    @Inject
    lateinit var customerRepo: CustomerRepository
    @Inject
    lateinit var policyRepo: PolicyDocumentRepository
    @Inject
    lateinit var rateRuleRepo: RateRuleRepository
    @Inject
    lateinit var groupRepo: ReservationGroupRepository
    @Inject
    lateinit var reservationRepo: ReservationRepository
    @Inject
    lateinit var consentRepo: ConsentRecordRepository
    @Inject
    lateinit var siteRepo: SiteRepository
    @Inject
    lateinit var appUserRepo: AppUserRepository
    @Inject
    lateinit var filmRepo: FilmRepository
    @Inject
    lateinit var holidayRepo: PublicHolidayRepository

    fun tenant(): Tenant {
        val slug = "test-" + UUID.randomUUID()
        return db.inTransaction { c ->
            db.execute(c,
                "INSERT INTO tenant (slug, display_name, vertical, complaint_phone) VALUES (?, ?, ?, ?)",
                slug, "Test Tenant " + slug, "live_entertainment", "+62-21-0000000")
            tenantRepo.findBySlug(c, slug).orElseThrow()
        }
    }

    fun pool(tenant: Tenant): ResourcePool =
        db.inTenantTransaction(tenant.tenantId) { c ->
            poolRepo.insert(c, tenant.tenantId, "Test Hall", "Somewhere", "Asia/Jakarta", "contiguous_n")
        }

    /** A branch. */
    fun site(tenant: Tenant, name: String?, city: String?): Site =
        db.inTenantTransaction(tenant.tenantId) { c ->
            siteRepo.insert(c, tenant.tenantId, name, siteSlug(c, tenant, name), city,
                "Jawa Tengah", "Jl. Test 1", "Asia/Jakarta")
        }

    /**
     * The public slug for a fixture branch, allocated exactly as `CatalogService`
     * allocates it.
     *
     * This fixture inserts through the repositories rather than through the service, so it
     * has to supply the slug itself — and `site.slug` is unique per tenant, so a fixture
     * that made one up would either collide the second time a test used the same branch name
     * or drift from the rule the application actually applies. Going through
     * [Slug.free] means a test asserting on a URL is asserting on the real one.
     */
    private fun siteSlug(c: Connection, tenant: Tenant, name: String?): String =
        Slug.free(name, "cinema") { base -> siteRepo.findSlugsUnder(c, tenant.tenantId, base) }

    /**
     * A branch with coordinates — the input the nearest-city ranking needs.
     *
     * Written with an UPDATE after the insert rather than through a wider `insert`
     * signature: the site table made latitude/longitude optional and nothing in the application writes
     * them, so adding two parameters to the repository's insert would put a column into the
     * production path that only tests use.
     */
    fun siteAt(tenant: Tenant, name: String?, city: String?, latitude: Double, longitude: Double): Site {
        val site = site(tenant, name, city)
        db.inTenantTransaction(tenant.tenantId) { c ->
            db.execute(c,
                "UPDATE site SET latitude = ?, longitude = ? WHERE tenant_id = ? AND site_id = ?",
                BigDecimal.valueOf(latitude), BigDecimal.valueOf(longitude),
                tenant.tenantId, site.siteId)
        }
        return db.inTenantTransaction(tenant.tenantId) { c ->
            siteRepo.findById(c, tenant.tenantId, site.siteId).orElseThrow()
        }
    }

    /** A showing of a film at a chosen instant — for the booking page's date and time fields, where the
     * whole question is which showings fall on which local day, and for the viability sweep,
     * where it is how long ago the screening started. */
    fun sessionOfFilmAt(tenant: Tenant, pool: ResourcePool, film: Film,
                        start: Instant, status: String?): ResourcePoolSession {
        val end = start.plus(Duration.ofHours(2))
        return db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, film.title, start, end,
                start.minusSeconds(900), status, film.filmId)
        }
    }

    /**
     * An `app_user` row — what a booking draft is keyed on.
     *
     * Needed because a draft belongs to a sign-in and not to a `customer` row, which
     * does not exist until the first hold. See the booking_draft table.
     */
    fun appUser(tenant: Tenant, email: String?, role: String?): AppUser =
        // inTenantTransaction because the app_user privilege audit audits tenant_staff inserts, and that trigger writes
        // to audit_log — which is under FORCE ROW LEVEL SECURITY, so a staff row created with
        // no tenant context is refused by the policy. Harmless for the customer rows this
        // method also creates, since the trigger deliberately ignores those.
        db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.insert(c, email, role, tenant.tenantId, null)
        }

    /** A branch in a chosen timezone — the value calendar pricing prices day-of-week bands from.
     * A chain spanning Java and Papua is not one timezone, and neither is a test of one. */
    fun siteInZone(tenant: Tenant, name: String?, city: String?, timezone: String?): Site =
        db.inTenantTransaction(tenant.tenantId) { c ->
            siteRepo.insert(c, tenant.tenantId, name, siteSlug(c, tenant, name), city,
                "Papua", "Jl. Test 2", timezone)
        }

    /** A hall inside a branch, for the city → branch → hall browse path. */
    fun poolAtSite(tenant: Tenant, site: Site, name: String?): ResourcePool =
        db.inTenantTransaction(tenant.tenantId) { c ->
            poolRepo.insert(
                c, tenant.tenantId, name, "Somewhere", "Asia/Jakarta", "contiguous_n", site.siteId)
        }

    fun unit(tenant: Tenant, pool: ResourcePool, label: String?, groupKey: String?, seq: Int): ResourceUnit =
        db.inTenantTransaction(tenant.tenantId) { c ->
            unitRepo.insert(c, tenant.tenantId, pool.resourcePoolId, label, groupKey, seq)
        }

    /** A film. */
    fun film(tenant: Tenant, title: String?, classification: String?): Film =
        db.inTenantTransaction(tenant.tenantId) { c ->
            filmRepo.insert(c, tenant.tenantId, title,
                Slug.free(title, "film") { base -> filmRepo.findSlugsUnder(c, tenant.tenantId, base) },
                118, classification, "Test synopsis", null, null, LocalDate.now().minusDays(7))
        }

    /** An upcoming showing of a film, for the film-first browse path. */
    fun sessionOfFilm(tenant: Tenant, pool: ResourcePool, film: Film, status: String?): ResourcePoolSession {
        val start = Instant.now().plus(Duration.ofDays(1))
        val end = start.plus(Duration.ofHours(2))
        return db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, film.title, start, end,
                start.minusSeconds(900), status, film.filmId)
        }
    }

    fun session(tenant: Tenant, pool: ResourcePool, status: String?): ResourcePoolSession {
        val start = Instant.now().plus(Duration.ofDays(1))
        val end = start.plus(Duration.ofHours(2))
        return db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, "Test Showing", start, end,
                start.minusSeconds(900), status)
        }
    }

    /** A showing that starts at a chosen instant — for pricing, where the whole question is
     * which local day the start falls on. */
    fun sessionAt(tenant: Tenant, pool: ResourcePool, start: Instant, status: String?): ResourcePoolSession {
        val end = start.plus(Duration.ofHours(2))
        return db.inTenantTransaction(tenant.tenantId) { c ->
            sessionRepo.insert(c, tenant.tenantId, pool.resourcePoolId, "Test Showing", start, end,
                start.minusSeconds(900), status)
        }
    }

    fun customer(tenant: Tenant): Customer {
        val phone = "+62812" + (Math.random() * 1_000_0000).toLong()
        return db.inTenantTransaction(tenant.tenantId) { c ->
            customerRepo.insert(c, tenant.tenantId, "Test Customer", phone, "test@example.com", true)
        }
    }

    fun policy(tenant: Tenant): PolicyDocument =
        db.inTenantTransaction(tenant.tenantId) { c ->
            policyRepo.insert(c, tenant.tenantId, "cancellation_policy", 1, "Test policy content",
                Instant.now().minusSeconds(3600))
        }

    fun rateRule(tenant: Tenant, pool: ResourcePool, amount: BigDecimal): RateRule =
        db.inTenantTransaction(tenant.tenantId) { c ->
            rateRuleRepo.insert(c, tenant.tenantId,
                pool.resourcePoolId, "{\"type\":\"flat\",\"amount\":" + amount.toPlainString() + "}",
                Instant.now().minusSeconds(3600))
        }

    /** The calendar rule: the four bands a typical cinema publishes, plus whatever the ticket
     * includes. Written through [RatePlan] rather than as a JSON literal so a test cannot
     * pin a shape the application does not actually write. */
    fun calendarRateRule(tenant: Tenant, pool: ResourcePool, monWed: BigDecimal,
                         thuFri: BigDecimal, satSun: BigDecimal, holiday: BigDecimal,
                         inclusions: List<String>?): RateRule {
        val bands: MutableMap<RatePlan.Band, BigDecimal> = EnumMap(RatePlan.Band::class.java)
        bands[RatePlan.Band.MON_WED] = monWed
        bands[RatePlan.Band.THU_FRI] = thuFri
        bands[RatePlan.Band.SAT_SUN] = satSun
        bands[RatePlan.Band.HOLIDAY] = holiday
        val json = RatePlan.byCalendar(bands, inclusions).json()
        return db.inTenantTransaction(tenant.tenantId) { c ->
            rateRuleRepo.insert(c, tenant.tenantId,
                pool.resourcePoolId, json, Instant.now().minusSeconds(3600))
        }
    }

    /** One consent row — the age attestation is what writes these for real. */
    fun consent(tenant: Tenant, customer: Customer, purpose: String?): ConsentRecord =
        db.inTenantTransaction(tenant.tenantId) { c ->
            consentRepo.insert(c, tenant.tenantId,
                customer.customerId, purpose, AgeClassification.LEGAL_BASIS,
                AgeClassification.hashOf("test statement"))
        }

    /** One day on the tenant's hari libur nasional list. */
    fun holiday(tenant: Tenant, date: LocalDate?, name: String?): PublicHoliday =
        db.inTenantTransaction(tenant.tenantId) { c ->
            holidayRepo.upsert(c, tenant.tenantId, date, name)
        }

    fun group(tenant: Tenant, pool: ResourcePool, customer: Customer, unitCount: Int): ReservationGroup =
        db.inTenantTransaction(tenant.tenantId) { c ->
            groupRepo.insert(c, tenant.tenantId, pool.resourcePoolId, customer.customerId, unitCount)
        }

    fun reservation(tenant: Tenant, group: ReservationGroup, unit: ResourceUnit,
                    session: ResourcePoolSession, policy: PolicyDocument, price: BigDecimal?): Reservation {
        val start = session.startsAt
        val end = session.endsAt
        return db.inTenantTransaction(tenant.tenantId) { c ->
            reservationRepo.insertHold(
                c, tenant.tenantId, group.reservationGroupId, unit.resourceUnitId,
                session.resourcePoolSessionId, policy.policyDocumentId, start, end, price, "IDR",
                Instant.now().plus(Duration.ofMinutes(10)), "customer", UUID.randomUUID())
        }
    }
}
