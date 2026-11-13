@file:UseSerializers(UuidSerializer::class)

package com.protek.booking.service

import com.protek.booking.db.Db
import com.protek.booking.domain.Tenant
import com.protek.booking.isJavaBlank
import com.protek.booking.json.UuidSerializer
import com.protek.booking.repository.AppUserRepository
import com.protek.booking.repository.CustomerRepository
import com.protek.booking.repository.PlatformAgreementRepository
import com.protek.booking.repository.PolicyDocumentRepository
import com.protek.booking.repository.TenantAgreementAcceptanceRepository
import com.protek.booking.repository.TenantRepository
import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.ws.rs.BadRequestException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.jboss.logging.Logger
import java.time.Instant
import java.util.UUID

/**
 * "One scripted action from day one": onboarding a tenant on any axis combination is
 * a single call, not a checklist someone works through by hand.
 *
 * The reason this is automated early rather than later is to build the
 * tenant-count-independent parts first — provisioning automation
 * stress-tests solo-operator capacity and needs nothing from a real tenant conversation.
 * Manual onboarding is fine at one tenant and is exactly the thing that quietly stops being
 * fine at five, by which point the half-provisioned tenants are already in production.
 *
 * **What this does and deliberately does not do.** Everything inside the database it
 * does: the `tenant` row with its three axes, the walk-in placeholder customer that
 * counter sales require, a default cancellation policy, the platform-agreement acceptance,
 * the first staff login, and the opening billing period. What it does not do is provision
 * Google Cloud infrastructure — the dedicated Cloud SQL instance for
 * `data_mode = 'dedicated'`, the dedicated Cloud Run service for
 * `compute_mode = 'dedicated'`, the Certificate Manager entry for a custom domain.
 * Those are real provisioning steps, and they are shell/gcloud work that
 * belongs in a script where it can be reviewed and run with the
 * operator's own credentials, not API calls issued by the running application with ambient
 * cloud permissions it otherwise never needs.
 */
@ApplicationScoped
class TenantProvisioningService {

    /** Flat monthly tiers, priced to cover typical cloud cost plus margin. This
     * ships the tier names only — no feature gating — because tier contents are
     * what a first real tenant conversation should inform rather than precede. */
    enum class PlanTier {
        STARTER, BUSINESS, ENTERPRISE
    }

    /**
     * The body of `POST /platform/tenants`.
     *
     * Every field is nullable and defaults to null, and unknown keys are ignored: that is how
     * Jackson bound the Java record this was until 2026-10-08, and [validate] — not the JSON
     * reader — is what is meant to say which field is missing. Without the defaults a request
     * leaving out `customDomain` would fail inside kotlinx with a message naming no rule.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonIgnoreUnknownKeys
    @JvmRecord
    data class ProvisionRequest(
        val slug: String? = null,
        val displayName: String? = null,
        val vertical: String? = null,
        val computeMode: String? = null,
        val dataMode: String? = null,
        val domainMode: String? = null,
        val customDomain: String? = null,
        val dedicatedDbHost: String? = null,
        val dedicatedDbName: String? = null,
        val jurisdiction: String? = null,
        val complaintPhone: String? = null,
        val staffEmail: String? = null,
        val acceptedByName: String? = null,
        val acceptedByEmail: String? = null,
        val acceptedFromIp: String? = null,
        val planTier: PlanTier? = null,
    )

    @Serializable
    @JvmRecord
    data class ProvisionResult(
        val tenant: Tenant,
        val walkInCustomerId: UUID?,
        val staffAppUserId: UUID?,
        val agreementAcceptanceId: UUID?,
        val manualFollowUp: List<String>,
    )

    @Inject
    lateinit var db: Db

    @Inject
    lateinit var tenantRepo: TenantRepository

    @Inject
    lateinit var customerRepo: CustomerRepository

    @Inject
    lateinit var policyRepo: PolicyDocumentRepository

    @Inject
    lateinit var appUserRepo: AppUserRepository

    @Inject
    lateinit var agreementRepo: PlatformAgreementRepository

    @Inject
    lateinit var acceptanceRepo: TenantAgreementAcceptanceRepository

    @Inject
    lateinit var billingService: BillingService

    /**
     * Provisions a tenant. Not idempotent by design: `tenant.slug` is UNIQUE, so a
     * re-run for the same slug fails on the insert rather than half-updating an existing
     * tenant. A failed provisioning should be inspected and cleaned up deliberately, since
     * the failure modes here (a tenant with no walk-in customer, an unaccepted agreement) are
     * exactly the ones that look fine until a counter sale or an invoice is attempted.
     */
    fun provision(request: ProvisionRequest): ProvisionResult {
        validate(request)

        // Read the current agreement outside tenant context: platform_agreement_version is
        // platform-level and carries no RLS policy.
        val agreement = db.inTransaction(agreementRepo::findCurrent)
            .orElseThrow {
                IllegalStateException(
                    "No platform agreement version exists; cannot onboard a tenant without one to accept")
            }

        // The tenant row itself must exist before anything tenant-scoped can be written, and
        // it lives in the non-RLS control plane, so it gets its own transaction.
        val tenant = db.inTransaction { c ->
            tenantRepo.insert(c,
                request.slug, request.displayName, request.vertical,
                request.computeMode, request.dataMode, request.domainMode,
                request.customDomain, request.dedicatedDbHost, request.dedicatedDbName,
                request.jurisdiction, request.complaintPhone)
        }

        // Everything tenant-scoped in one transaction: a tenant that exists but has no
        // walk-in placeholder can take online bookings and then fail every counter sale,
        // which is worse than not existing.
        val walkInCustomerId = db.inTenantTransaction(tenant.tenantId) { c ->
            val walkIn = customerRepo.insertWalkInPlaceholder(c, tenant.tenantId)

            // A cancellation policy has to exist before the first booking: reservation
            // .policy_document_id freezes which version was in effect at booking time,
            // and a null there means a booking nobody can later show terms for.
            policyRepo.insert(c, tenant.tenantId, "cancellation_policy", 1,
                "Reservations may be cancelled free of charge up to 1 hour before the showing. " +
                    "This is a provisioning default — the tenant should replace it with their own policy.",
                Instant.now())

            acceptanceRepo.insert(c, tenant.tenantId, agreement.platformAgreementVersionId,
                request.acceptedByName, request.acceptedByEmail, request.acceptedFromIp)
            walkIn.customerId
        }

        val acceptanceId = db.inTenantTransaction(tenant.tenantId) { c ->
            acceptanceRepo.find(c, tenant.tenantId, agreement.platformAgreementVersionId)
                .orElseThrow().tenantAgreementAcceptanceId
        }

        // The first staff login, provisioned by email because that is the only identifier
        // that exists before they have ever signed in (the OIDC subject is bound on first
        // login — see AppUserRepository.bindSubjectOnFirstLogin).
        //
        // They are the tenant's first MANAGER, not merely its first staff member: this
        // is the address the tenant nominated at onboarding, and somebody has to be able to
        // add the second person without coming back to us.
        //
        // inTenantTransaction rather than inTransaction, and the difference is not cosmetic:
        // The privilege-audit trigger audits staff inserts, and that trigger writes to audit_log, which is under FORCE
        // ROW LEVEL SECURITY — with no tenant context the audit insert is refused by the
        // policy and takes this statement down with it.
        val staff = db.inTenantTransaction(tenant.tenantId) { c ->
            appUserRepo.insertStaff(c, request.staffEmail, tenant.tenantId, true)
        }

        billingService.openCurrentPeriod(tenant, request.planTier)

        val followUp = manualFollowUp(tenant)
        LOG.infof("Provisioned tenant %s (%s) on %s/%s/%s; %d manual follow-up step(s)",
            tenant.slug, tenant.tenantId, tenant.computeMode, tenant.dataMode,
            tenant.domainMode, followUp.size)
        return ProvisionResult(tenant, walkInCustomerId, staff.appUserId, acceptanceId, followUp)
    }

    /**
     * The infrastructure steps this method cannot take, returned rather than logged and
     * forgotten. Backup verification is the task most likely to be
     * skipped under time pressure and most likely to matter during a real incident — so it
     * appears here for every tenant, not only dedicated ones.
     */
    private fun manualFollowUp(tenant: Tenant): List<String> {
        val steps = ArrayList<String>()
        if (tenant.hasDedicatedDatabase()) {
            steps.add("Provision the dedicated Cloud SQL instance for " + tenant.slug +
                " and run the Flyway migrations against it so its schema matches the pool database.")
            steps.add("Label the instance with tenant_id=" + tenant.tenantId +
                " so Billing Export attribution works.")
            steps.add("Set channelBinding=require in that tenant's JDBC URL (dedicated tenants specifically).")
        }
        if (tenant.hasDedicatedCompute()) {
            steps.add("Deploy a dedicated Cloud Run service for " + tenant.slug +
                ", labelled tenant_id=" + tenant.tenantId + ", with a deliberate max-instance ceiling.")
        }
        if ("custom" == tenant.domainMode) {
            steps.add("Add " + tenant.customDomain + " to the Certificate Manager certificate map " +
                "behind the external HTTPS Load Balancer (not Cloud Run domain mapping).")
        }
        // Says what to do, where, and where the number goes, not merely that one is needed — a step
        // that named only the field sat unactioned. PSE Lingkup Privat is filed on OSS under
        // PB-UMKU and gates on the registrant's NIB carrying a matching KBLI, not on an akta
        // pendirian. Whether the registrable Penyelenggara is this tenant or the platform is still
        // open — this is phrased per-tenant because that is the reading the column already
        // encodes, not because the question is settled.
        steps.add("Register this tenant as Penyelenggara Sistem Elektronik (PSE Lingkup Privat) at " +
            "https://oss.go.id/id — PB-UMKU menu, gated on the tenant's NIB, not an akta " +
            "pendirian — then record the number via POST /platform/tenants/" + tenant.tenantId +
            "/pse-registration. Registration is a go-live gate.")
        steps.add("Run a restore test against this tenant's backups before go-live.")
        return steps
    }

    // A missing vertical or mode now fails its own check below with a 400. In Java it was a 500:
    // List.of(...).contains(null) throws NullPointerException, which nobody had meant, since the
    // whole point of this method is a message naming the field. Changed on purpose, 2026-10-08.
    private fun validate(request: ProvisionRequest) {
        require(request.slug.isPresent(), "slug is required")
        require(request.displayName.isPresent(), "displayName is required")
        require(request.vertical in listOf("hospitality", "live_entertainment"),
            "vertical must be hospitality or live_entertainment")
        require(request.computeMode in listOf(Tenant.MODE_SHARED, Tenant.MODE_DEDICATED),
            "computeMode must be shared or dedicated")
        require(request.dataMode in listOf(Tenant.MODE_SHARED, Tenant.MODE_DEDICATED),
            "dataMode must be shared or dedicated")
        require(request.domainMode in listOf("subdomain", "custom"),
            "domainMode must be subdomain or custom")
        // complaint_phone is mandatory rather than optional (PP 71/2019's
        // pre-transaction disclosure list), and the DDL enforces NOT NULL — checking here
        // gives a usable message instead of a constraint violation.
        require(request.complaintPhone.isPresent(),
            "complaintPhone is required (PP 71/2019 pre-transaction disclosure)")
        require(request.staffEmail.isPresent(),
            "staffEmail is required — a tenant with no staff login cannot manage its own halls")
        require(request.acceptedByName.isPresent() && request.acceptedByEmail.isPresent(),
            "the platform agreement must be accepted by a named person")
        require(request.planTier != null, "planTier is required")
        // The dedicated_fields_consistent CHECK enforces this too; failing here names which
        // field is missing instead of surfacing a constraint name.
        if (Tenant.MODE_DEDICATED == request.dataMode) {
            require(request.dedicatedDbHost != null && request.dedicatedDbName != null,
                "dataMode=dedicated requires dedicatedDbHost and dedicatedDbName")
        } else {
            require(request.dedicatedDbHost == null && request.dedicatedDbName == null,
                "dataMode=shared must not set dedicatedDbHost/dedicatedDbName")
        }
        if ("custom" == request.domainMode) {
            require(request.customDomain.isPresent(), "domainMode=custom requires customDomain")
        }
    }

    /** Java's `s != null && !s.isBlank()`, which every text field above was checked with. */
    private fun String?.isPresent(): Boolean = this != null && !isJavaBlank()

    /** A member, so it is what `require` means in this class: a 400 naming the rule, where the
     * standard library's would be an `IllegalArgumentException` and a 500. */
    private fun require(condition: Boolean, message: String) {
        if (!condition) {
            throw BadRequestException(message)
        }
    }

    companion object {
        private val LOG: Logger = Logger.getLogger(TenantProvisioningService::class.java)
    }
}
