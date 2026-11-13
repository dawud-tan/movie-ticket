# PSE registration on OSS — the step tenant onboarding never wrote down

> Where, and in what order, a platform operator registers as an electronic-system operator (PSE) in Indonesia. Not legal advice; research dated 2026-08-29.
> Written for the original deployment and published as written on 2026-11-04, apart from the platform operator's own entity details and references to private documents, which are removed.

Operational companion to [plan](plan.md) **§I.6**, which states the obligation, and to
[`TenantProvisioningService.manualFollowUp`](../src/main/java/com/protek/booking/service/TenantProvisioningService.kt#L206), which emits a
follow-up step to register each provisioned tenant as a PSE and record the number. Until this
file, that step never said where a number is obtained. Started 2026-08-29.

*Not a substitute for review by Indonesian counsel before launch — §I.6's own caveat, and it
applies hardest to the "who registers" question in §2 below, which this file deliberately does not
close.*

---

## Why this file exists

Before it, the code carried the PSE obligation as **plumbing for a number and nothing else**:

- `tenant.pse_registration_no` — the column ([`V1__init_schema.sql:75`](../src/main/resources/db/migration/V1__init_schema.sql#L75))
- `POST /platform/tenants/{tenantId}/pse-registration` — the way to fill it
  ([`PlatformAdminResource.kt:131`](../src/main/java/com/protek/booking/web/platform/PlatformAdminResource.kt#L131))
- one follow-up string per provisioned tenant ([`TenantProvisioningService.kt:229`](../src/main/java/com/protek/booking/service/TenantProvisioningService.kt#L229)) — which
  named the obligation and the endpoint and stopped there, without a portal; it names
  `https://oss.go.id/id`, the PB-UMKU menu and the NIB gate now, from this file
- the open question, recorded in the seeded tenant-agreement draft
  ([`V1__init_schema.sql:1158`](../src/main/resources/db/migration/V1__init_schema.sql#L1158))

What was missing is the only part a person actually has to *do*: the portal, the prerequisite that
gates it, and the order. `https://oss.go.id/id` appeared nowhere in the code. §I.6 says
"Kominfo's OSS-integrated service"; that URL is the service.

**Naming drift to expect.** Kominfo is now **Komdigi** (Kementerian Komunikasi dan Digital), and
the public register lives at `pse.komdigi.go.id`. The plan still says "Kominfo". Not worth renaming — `pse_registration_no` holds the same number either way —
but the portal will not say Kominfo back to you.

---

## 1. The gate is the NIB, not the akta — and this is where the assumption goes wrong

The natural assumption is that OSS/PSE and Midtrans share a prerequisite in the **akta pendirian
perusahaan**, so one document run serves both. Only half of that is true, and the half that is
false is the expensive one.

| Track | Akta pendirian required? | What it actually gates on |
|---|---|---|
| **PSE Lingkup Privat**, via OSS | **Not directly** | An **NIB** that already carries a **KBLI** code matching the electronic system being registered. Filed under OSS's **PB-UMKU** menu. |
| **Midtrans**, badan usaha (PT / CV / PMA) | **Yes** | "SK Menkumham Akta Perubahan/Pendirian", Akta Perusahaan Terbaru, KTP/Paspor Direktur, NPWP Direktur, NPWP Perusahaan, NIB/SIUP/TDP |
| **Midtrans**, individu | **No** | KTP + NPWP only |

So the genuine overlap is **NIB and NPWP**. The akta sits upstream of the NIB for an ordinary PT or
CV — which is why the two *look* like they share it — but OSS asks for the NIB, not the deed.

**The case where the difference bites.** §I.6 chose the **sole-founder PT** (*Perseroan Perorangan*)
for the platform's own entity, and its defining simplification is that **no notarial deed is required at all**: AHU issues
a *sertifikat pendaftaran* electronically instead. A sole-founder PT has an NIB and an NPWP, and has
nothing to upload where Midtrans's badan-usaha list says "Akta Pendirian". That is a real gap in
the document list, not a paperwork oversight — **ask Midtrans which of the two tracks a
sole-founder PT is onboarded on before assembling either folder.** It is cheap to ask now and expensive
to discover during the production cutover.

---

## 2. Who registers — still open, and this file does not close it

§I.6's one genuinely-open legal question, restated here because it is what decides the shape of
this step rather than something to look up separately:

> in a multi-tenant white-label structure, who is the registrable Penyelenggara Sistem Elektronik —
> the platform operator, or each tenant as the business actually forming contracts with its own
> customers?

Both readings and what each implies:

- **Platform is the Penyelenggara** — one registration, filed once, against the platform's own
  NIB. `tenant.pse_registration_no` then holds the same number on every row, which is a sign it
  belongs on a platform-level record instead of a per-tenant column.
- **Each tenant is the Penyelenggara** — one registration per tenant, filed by the tenant against
  *its* NIB, and the per-tenant column is exactly right. **This is what the code presumes today**:
  the follow-up step says "this tenant's".

**Do not resolve this by writing code.** The column already supports the second reading and
tolerates the first; nothing needs to change until counsel answers. Registration is a precondition
to lawfully operating, so the answer is needed before the first tenant goes live — not before the
next commit.

**For a multi-branch chain** (the first real prospect): an established chain has both an
NIB and an akta already, so nothing here blocks a sales conversation. What to expect is **one registration for
the company**, not one per branch — an NIB is per legal entity, not per branch. If branches are modelled as
separate tenants, the same number lands on every one of those rows.

---

## 3. The sequence

0. **The entity exists.** §I.6 — a sole-founder PT for the platform; the tenant's own PT/CV for a
   tenant. Everything below presumes a registrant already exists.
1. **NIB via OSS**, carrying a KBLI code that matches the electronic system. Confirm the code on
   OSS at filing time — §I.6 deliberately declines to hard-code one, on the same reasoning as the
   UMK thresholds: these move, and a figure written down here would be believed after it stopped
   being true.
2. **File the PSE registration** — <https://oss.go.id/id>, menu **PB-UMKU** (Perizinan Berusaha
   untuk Menunjang Kegiatan Usaha). Registration is *through OSS*; Komdigi is not a second filing.
3. **Verify it landed** — <https://pse.komdigi.go.id>, which is the published register and the
   place to check status.
4. **Record the number in the system** — not a manual `UPDATE`:

   ```bash
   curl -X POST "$PLATFORM_URL/platform/tenants/$TENANT_ID/pse-registration" \
        -H "Authorization: Bearer $ACCESS_TOKEN" \
        -H 'Content-Type: application/json' \
        -d '{"registrationNo":"..."}'
   ```

   The endpoint is `@RolesAllowed(PLATFORM_ADMIN)` and writes the tenant row; a hand-written
   `UPDATE` bypasses that and, on a tenant-scoped table, has to carry its own tenant context to
   get past RLS at all.

---

## 4. Midtrans is a parallel track — do not serialize them

The only thing the two share is a document folder (§1). Neither gates the other, and treating them
as a chain is the mistake this section exists to prevent:

- **PSE does not block Midtrans.** The BI-SNAP client was proven end to end against the sandbox on
  2026-08-15, and nothing in the credential exchange waits on a PSE number.
- **Midtrans does not block PSE.** Filing on OSS needs the NIB, not a payment gateway.

They converge only at **go-live**, where both must be done: PSE because §I.6 makes it an
operational gate, Midtrans because the production keys are what make a real payment possible.

---

## Status

| Step | State |
|---|---|
| Portal and prerequisite chain identified (OSS → PB-UMKU; NIB + matching KBLI) | **done** 2026-08-29 — this file |
| Midtrans document list confirmed against Midtrans's own docs | **done** 2026-08-29 — see Sources |
| Who the registrable Penyelenggara is (platform vs. tenant) | **open** — counsel, before the first tenant goes live (§I.6, §2 above) |
| Whether Midtrans onboards a **sole-founder PT** as badan usaha or individu | **open** — ask Midtrans; the akta gap in §1 is real |
| Platform entity (sole-founder PT) incorporated | not started — §I.6 |
| Platform NIB + KBLI for software/platform services | not started |
| Platform PSE registration filed | not started |
| Per-tenant PSE numbers recorded via the endpoint | none yet — no live tenant |

---

## Sources

Checked 2026-08-29. Regulatory portals move; re-check rather than trusting this list if a step
does not match what the screen says.

- Registration is through OSS, and the PSE register is published by Komdigi —
  <https://pse.komdigi.go.id/panduan/persyaratan-pendaftaran-pse-lingkup-privat-domestik>
  ("Daftar Sekarang di Aplikasi OSS", linking `oss.go.id`)
- NIB + related permit + matching KBLI as the prerequisite, filed under PB-UMKU —
  <https://pse.komdigi.go.id/panduan/tahapan-pendaftaran-pse-lingkup-privat-domestik>
- Midtrans's own legal-document list, per business type —
  <https://docs.midtrans.com/docs/apa-saja-dokumen-legalitas-yang-diperlukan-untuk-registrasi-akun-midtrans>
- A sole-founder PT needing no notarial deed: [plan](plan.md) §I.6, on Pasal 153A of Law 6/2023
