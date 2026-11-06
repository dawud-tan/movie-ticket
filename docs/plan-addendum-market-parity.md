# Addendum A — Market parity for a multi-branch cinema chain

> An addendum to [plan.md](plan.md): what a multi-branch cinema chain needs beyond the core, how each item was scoped, and what was built.
> Written in August 2026 for the original deployment and published as written on 2026-11-04, apart from names (the prospect and the incumbent chain are pseudonyms; the prospect's branch list and per-branch prices are left out, and amounts are rounded) and references to private documents, which are removed.

**Status: partially delivered.** This is an addendum to
[`plan.md`](plan.md), written 2026-08-16 ahead of a sales conversation with a **prospective
multi-branch tenant**. It amends the scope line in §I.0 and adds a **Phase 4B** to Part II. It does
not change any Phase 1–4 decision, and it does **not** depend on Phase 5, which stays out of scope.

**Delivered 2026-08-17** (approved scope: A.7d, A.1, A.2, plus the A.5.1
policy decision — enough that the demo reads as a chain's site), with A.7's per-showtime cap
(2026-08-20), A.4's redemption artifact (2026-08-20), A.6 (2026-08-21), A.7's per-day cap
(2026-08-21), the rest of A.4 — order history, booking code, audit attribution (2026-08-21) — and
A.3's attestation (2026-08-22) since:

| Item | State | Schema change |
|---|---|---|
| A.7d bulk seat-map entry | **built in full** — generator, irregular layouts, clone, preview; **CSV import** 2026-08-22 | none needed |
| A.1 `site`/branch hierarchy | **built** — city → branch → hall browse | `site` table, `resource_pool.site_id` |
| A.2 `film` + film-first browse | **built** — "now playing", where-it-plays | `film` table, `resource_pool_session.film_id` |
| A.5.1 who may cancel a paid booking | **decided: staff only**, provisional — see A.5.1 | none |
| A.3 classification | **built in full** — the `film.classification` column plus the hold-time attestation, `consent_record` and `date_of_birth_attested` writes, 2026-08-22; the self-declared **age advisory** on top of it, 2026-08-27 — it warns and the tick still refuses | none needed |
| A.7 purchase caps | **built in full** — per-showtime (8) 2026-08-20, per-day (10) 2026-08-21 | none needed |
| A.4 order history / redemption | **built in full** — artifact + staff scan 2026-08-20; `GET /reservations`, booking code, staff lookup, audit attribution 2026-08-21. The `completed`-on-scan half was **decided against**: see below | `reservation.admitted_at`, `reservation_group.booking_code`, `app.current_actor_type` |
| A.6 calendar pricing | **built** 2026-08-21 — `by_calendar` bands, holiday-and-eve list, branch-timezone resolution, `inclusions` | `public_holiday` table |

Everything not named "built" above is unchanged and still scoped-only. **Nothing in this document
is outstanding**: CSV seat-map import, the last item, had been deferred until a condition arrived,
and was built on 2026-08-22 when it did — a tenant whose studio layouts already exist and are to be
imported under the names they already carry.

Read [`plan.md`](plan.md) §I.0 (MVP use cases) and §I.1 (data model) alongside this.

---

## A.0 Why this exists

Phases 1–4 prove the thing that is genuinely hard: an exclusive claim on a resource for a period,
enforced by the database rather than by application code. That is the right thing to have built
first and it is not in question here.

But the demo is currently shaped like *one hall, one showing*, and the prospect is a chain
operating in **dozens of cities**. Two of the gaps below are not features at all — they are structural
assumptions that make a chain of that shape unrepresentable in the current model. Those are the
ones that matter before a sales conversation; the rest are ranked after them.

The comparison target is **Chain A**, the largest incumbent chain, whose flows an Indonesian
cinema owner will take as the baseline of what a ticketing site does.

**Sourcing caveat, recorded so nobody re-derives it:** the public sites of Chain A and of the
prospect both return **HTTP 403 to automated fetches**. The Chain A behaviour below comes from its
public FAQ and corporate site (and, in A.7c, a later direct read of the live site), not from its
booking application. A.7b has its own sourcing note. Anything here described as Chain A's *stated
policy* is from the FAQ; nothing here is from reading their application.

---

## A.1 Structural gap 1 — there is no branch. `resource_pool` is a studio.

**BUILT 2026-08-17.** `site` exists (tenant-scoped, `city`,
`province`, `timezone`, optional geo, RLS with the `NULLIF` guard); `resource_pool.site_id` is
nullable and **stays** nullable, so every hall that predates branches is still a hall. Browse is city → branch →
hall, with `/halls/{poolId}` unchanged. Two behaviours worth knowing before reading the scope
below: a tenant with **no** branches keeps the original flat hall list (forcing a one-cinema
operator through a city step would be a worse page), and a hall with no branch is listed as
unassigned rather than hidden. `resource_pool.timezone` was kept as a per-hall override, as
scoped. Covered by `SiteBrowseTest` and a `site` case in `RowLevelSecurityTest`.

**This is the finding that matters most for a multi-branch prospect.**

`resource_pool` is a single auditorium, not a cinema: `resource_unit` (seats) references it
directly, `resource_pool_session` (showings) happens in it, and the seed row is named
`'Studio 1'`. `CatalogService.listHalls()` returns every pool for the tenant as a flat list, and
`customer/halls.html` renders that list under "Choose a venue".

For a single-site tenant this is invisible. For a chain it is disqualifying: a few dozen branches at roughly
2–6 studios each is on the order of **100–200 `resource_pool` rows in one flat, ungrouped list**,
most of them named some variant of "Studio 1". A customer landing on `/` gets an unusable page,
and there is no query that answers "what is playing at the branch in city X" because nothing in the
schema knows that a set of studios constitutes a branch.

`resource_pool.address` is free text and there is no city column, so the browse structure Chain A uses
— **city → cinema → film/showtime** — cannot be built on the current model at any level.

**Scope:**
- A `site` table (branch): tenant-scoped, `name`, `city`, `province`, `address`, `timezone`,
  optional geo. `resource_pool` gains `site_id`.
- Customer browse becomes city → site → showings. Staff hall admin gains a site selector.
- `timezone` moves to `site` in practice (a chain spanning Kalimantan and Java is not one
  timezone), with `resource_pool.timezone` kept as an override rather than removed.

**Cost:** one table, one FK, one column on the browse path, and a rework of `ShowtimeResource` and
two templates. No change to the reservation path, the `EXCLUDE` constraint, or RLS beyond adding
`tenant_id` to the new table per §I.1's blanket rule.

**Note for the sales conversation:** this is worth raising *before* the owner discovers it, not after. It is
cheap, and volunteering it is more credible than being asked "so how do I list my branches?"

---

## A.2 Structural gap 2 — there is no film

**BUILT 2026-08-17.** `film` exists (title, runtime,
classification, synopsis, poster, trailer, release date; RLS with the `NULLIF` guard), and
`resource_pool_session.film_id` is nullable — `label` is kept and stays `NOT NULL`, filled from
the film's title when a film is picked, so a label-only session remains the first-class case
§I.1 requires. `/films` lists what is playing (an `EXISTS`, so a title in forty studios appears
once) and `/films/{id}` answers where and when, naming the branch. Staff can create films and
pick one when creating a showing. Covered by `FilmBrowseTest` and a `film` case in
`RowLevelSecurityTest`.

`resource_pool_session.label` is free text. The seed value is literally
`'Demo Film — Tonight 19:00'`. There is no film entity anywhere in the schema or the Java.

Consequences, all of them visible in a demo:
- Every browse path is venue-first (`/` → halls → that hall's showings). A film-first path —
  "pick a title, see where and when it plays" — is Chain A's primary structure and cannot be built.
- Nothing can answer "when is film X playing?" across a branch, let alone across a chain.
- No poster, no synopsis, no runtime, no trailer. A cinema owner's first impression of a ticketing
  site is a grid of posters; ours is a text list.
- A film playing in three studios is three unrelated strings, so a chain-wide "now playing" list
  cannot be assembled and no reporting can group by title.

**Scope:**
- A `film` table: `title`, `duration_minutes`, `classification` (see A.3), `synopsis`,
  `poster_url`, optional `trailer_url`, `release_date`. Tenant-scoped like everything else.
- `resource_pool_session` gains a nullable `film_id`, keeping `label` for non-film sessions —
  the schema is deliberately vertical-neutral per §I.1, and a `label`-only session is what the
  hospitality mapping and any non-film event uses.
- A film-first browse path alongside the existing venue-first one.
- Staff: film CRUD, and the showing form selects a film instead of typing a label.

**Cost:** one table, one nullable FK, one new browse path, one admin screen. Nullable `film_id`
keeps every existing row and test valid.

**This is a prerequisite for A.3** and for any convincing version of A.4.

---

## A.3 Age classification — and a column that already exists and is never read

**BUILT in full 2026-08-22; the scoping text below is kept as written and is
now historical.** No migration: both mechanisms this asked for were
already in the schema, which is what "small, once A.2 exists" turned out to mean. Three things it
did not predict. The gate had to be asked **every time** rather than remembered, because
`date_of_birth_attested` is one boolean over three thresholds and a remembered 13+ attestation
would otherwise clear a 21+ film — so the flag is a record and never a permission. The **counter is
exempt**, for the same structural reason it is exempt from A.7's caps: every walk-in shares one
`customer` row, so a tick there would claim an attestation on behalf of everybody who buys at the
desk afterwards, and the customer is standing in front of staff anyway. And the wording of the
sentence is generated server-side rather than written into the template, because
`consent_record.policy_text_hash` has to hash the string the customer actually read.

Chain A enforces LSF classification (SU / 13+ / 17+ / 21+), children under 2 free, ticket required
from age 2. This is **regulatory in Indonesia**, not a nicety, and it is the kind of thing an
operator will assume is present.

`customer.date_of_birth_attested boolean NOT NULL DEFAULT false` exists in
`V1__init_schema.sql` — and **no Java code reads or writes it**. It is currently a column that
promises a control the application does not implement.

**Scope:**
- `film.classification` (enum-checked text), displayed on every showing and seat map.
- An attestation step at hold time for restricted classifications, writing
  `customer.date_of_birth_attested` and a `consent_record` row — both mechanisms already exist and
  are already wired for consent capture.
- Deliberately **not** identity verification. The attestation is the same control Chain A applies
  online; enforcement at the door stays a staff function.

**Cost:** small, once A.2 exists. Largest value per line in this addendum, because it converts a
dead column into an implemented control and answers a compliance question directly.

---

## A.4 Order history and e-ticket

**Delivered in full (2026-08-20 and 2026-08-21) — the scoping text below is kept as written
and is now historical.** Two things it predicted turned out differently and are worth carrying:
the barcode is Code 128 rather than a QR (verifiability — there is no decoder in this repository
to check a QR encoder against), and the booking code is *not* what the customer presents at the
door. Those are two separate values with opposite requirements; see the closing note under this
section.

`ReservationResource` is `@Path("/reservations/{groupId}")` — **detail only, no list endpoint**. A
returning customer cannot see their bookings. After confirming, there is no redeemable artifact:
no booking code, no QR, nothing to present at the door.

Chain A's stated flow is order history → QR shown at a self-service terminal in the lobby → printed ticket for studio entry.

Note what already exists: `reservation.status` includes `completed`, and
an index already exists for it. So the **lifecycle terminus is built and
the redemption event is not** — today only a scheduled sweep moves anything to `completed`.

**Scope:**
- `GET /reservations` — the customer's own groups, current and past.
- A short human-readable booking code on `reservation_group` (unique per tenant), plus a QR
  encoding it. The group is already the right unit: §I.1 fixed payment to `reservation_group_id`
  precisely to avoid a polymorphic FK, and a ticket is a group-level artifact for the same reason.
- A staff redemption screen: look up by code, mark `confirmed → completed`, with the existing
  audit trigger recording it.

**Known wrinkle, already documented:** the audit trigger attributes
`confirmed → completed` to `NEW.created_by_actor`, so a system sweep is recorded as the customer.
A staff-driven redemption makes that misattribution more visible and more wrong. It is still the
separate, larger change it is — flagged here as a dependency to decide on, not
silently inherited.

**Decided 2026-08-17: fix the attribution as part of A.4, not before and not after.** A.4 is what
makes it wrong enough to matter — a staff redemption recorded as the customer is a false entry in
a tamper-evident log — and fixing it while nothing writes that transition would be churn. Whoever
picks up A.4 owns it; it is not optional scope within A.4.

**Delivered 2026-08-21, and one item of the scope above was decided against rather than
built.** The attribution is fixed at its root: the trigger reads a new `app.current_actor_type`
GUC and falls back to `'system'`, so it records who *acted* rather than who created the row — the
sweep, the payment webhook, a phone cancellation and an usher's scan were all previously logged as
the customer. What was **not** built is the "mark `confirmed → completed`" half of the staff
redemption screen, and the reason is the one the admission work already paid for: `'completed'` falls outside the
`EXCLUDE` constraint's `status IN ('held','confirmed')` predicate, so completing a booking at the
door would make the seat its holder is sitting in bookable again. Admission is recorded on
`reservation.admitted_at`; the scheduled sweep remains the only writer of `completed`, because it
is the only one that waits for the showing to end.

---

## A.5 Two defects found while scoping this — not features

Recording these here because they were found during the comparison, but they are **corrections to
shipped behaviour** and should be judged separately from any new scope.

**A.5.1 Cancelling a paid booking strands the money. — FIXED 2026-08-17
** The resolution records the **obligation** without pretending to discharge it:
`payment.refund_due_amount` / `refund_due_at` / `refund_reason` accumulate per cancelled seat
inside the cancelling transaction, `status` stays `paid` because nothing moves money back yet, and
`PaymentRepository.findRefundsDue` lists what is owed. Chain A's "customers cannot cancel at all"
policy was **not** adopted — that stays a live business decision, and the fix is deliberately
compatible with either answer. The original description is kept below as
the record of what was wrong.

**The open half is now decided, 2026-08-17: adopt the incumbent's policy — a customer cannot
cancel a booking they have paid for; staff can.** Enforced in
`BookingService.assertMayCancelSettled`, covered by `PaidCancellationPolicyTest`. Deliberately
narrow: it triggers only on a **settled** payment, so cancelling an unpaid hold — and a
created-but-unpaid QRIS charge, which expires by itself — stays a customer action (§I.0 use case
5). The customer-facing cancel button is hidden in that state, but the button is UX and the
service check is the boundary. A staff cancellation still raises the refund obligation described above.

**This decision is provisional and is to be confirmed with the prospect's own staff**, who may run their
counter differently. Refusing is the safer default while it is open: a refused cancellation is a
phone call, an over-permissive one is money out of the door with nobody having decided to send
it. Reversing it later is a one-method change.

`payment.status` accepts `'refunded'` and `Payment.kt` defines `STATUS_REFUNDED` — and **nothing
in the codebase ever sets it**. `BookingService.cancelReservation` updates the reservation and
notifies watchers; it does not look at `payment` at all. So §I.0's customer use case 5 ("cancel a
held or confirmed reservation") against a settled QRIS payment leaves a `paid` payment attached to
a `cancelled` reservation, with no refund path and no flag that one is owed.

Two honest resolutions, and the choice is a business decision, not a technical one:
- **Adopt Chain A's policy:** the customer cannot cancel a paid booking at all; only staff can, and a
  staff cancellation raises a refund obligation. Chain A states 24 hours to app-wallet credit, 14 business
  days for card. This is cheaper and matches Indonesian cinema norms.
- **Build a refund path** through Midtrans, with `refunded` actually set.

Either way the current state is that two documented behaviours contradict each other, and the
`refunded` status is a promise the code does not keep. **This should be resolved regardless of
whether the rest of this addendum is approved.**

**A.5.2 Seat-category pricing is half-built and inert.**
`resource_unit.attributes jsonb` is read by **no Java code at all**. `rate_rule.rule_definition`
is seeded as `{"type":"flat","amount":50000}`, but `BookingService.resolvePrice` reads
`node.get("amount")` and **never inspects `type`** — so the discriminator exists in the data and
is ignored by the only code that reads it.

§I.0 lists "seat-category pricing tiers" as explicitly out of scope, so this is a scope decision
to revisit rather than an oversight. But see A.6: for the prospect it is not optional, and both the storage
and the dispatch point are already sitting there unused.

---

## A.6 Amendment to §I.0's out-of-scope line

**Built 2026-08-21.** The amendment below stands as written; what shipped is the
`by_calendar` variant with Mon–Wed / Thu–Fri / Sat–Sun bands plus a holiday-and-eve override, a
tenant-editable `public_holiday` table, day-of-week resolved in the branch's timezone, and the
`rule_definition.inclusions` line. `by_category` remains scoped and unbuilt, as this section
asks. Two things this scoping got wrong, both found while building it: the timezone precedence is the *branch's*, not the hall's (the hall column is
`NOT NULL DEFAULT`, so "overridden" and "untouched" are indistinguishable), and the worked
example of the UTC trap is inverted — every Indonesian zone is ahead of UTC, so it is the first
hours of a local day that fall in the previous UTC day, not the last.

§I.0 currently reads:

> **Explicitly out of scope:** refund-policy variants, seat-category pricing tiers, loyalty
> programs, native apps for Android or iOS, EDC/card-terminal integration.

**Proposed amendment — two items move, three stay.**

**Moves in scope (Phase 4B):**

- **Calendar-based pricing — and it is the day, not the seat.** A.7b measured the prospect's published
  price list across all its branches: the axis they actually price on is **day-of-week plus a
  public-holiday rule**, uniform in *structure* chain-wide, with per-branch amounts. A flat price
  per pool cannot express it. Concretely: honour `rate_rule.rule_definition.type` with a
  `by_calendar` variant carrying Mon–Wed / Thu–Fri / Sat–Sun bands plus a holiday-and-eve
  override. Keep a `by_category` variant scoped but unbuilt — Chain A's ladder of premium studio
  formats needs it and `resource_unit.attributes` already exists to hold the category, but **A.7b
  found no evidence the prospect prices by seat**, so building it for this prospect would be speculative.
  §I.1's reasoning — that pricing is the vertical-specific part and is allowed to live in jsonb
  while the claim itself stays in strict relational columns — anticipated exactly this, so this
  completes a design decision rather than reversing one.
- **An Indonesian public-holiday calendar becomes a real dependency.** The prospect's top price band is
  "weekend, public holiday, **or the day before one**". That is not derivable from a date alone;
  it needs a maintained `hari libur nasional` table (a tenant-editable list is sufficient — do not
  take a dependency on a third-party calendar API for this). Scoping this was the main surprise of
  A.7b and it is easy to miss until a tenant's first Lebaran.
- **Refund-policy variants — narrowed to "a refund obligation is recorded".** Not a full policy
  engine. A.5.1 has to be resolved because the current state is inconsistent, and the minimum is
  that a staff cancellation of a paid booking is visible as owing a refund.

**Stays out of scope, and worth being able to say why in a sales conversation:**

- **F&B *ordering* — split into two things after A.7b.** An ordering/inventory system stays firmly
  out: no exclusivity constraint, so it reuses none of the `EXCLUDE` spine that is this platform's
  actual value, and Chain A runs a real one (food ordering in its app, a dining service, three delivery aggregators) that we
  would be pretending to match. **But the prospect bundles a fixed drink voucher into every ticket price**,
  and that is not an ordering system — it is one more line on the rate rule. A
  `rule_definition.inclusions` entry that prints on the ticket is **in scope** and nearly free;
  anything with stock levels is not.
- **A stored-value customer wallet (top-up and balance, as in Chain A's app).** Newly identified in A.7c and
  deliberately declined: it would mean the tenant holds customer funds, which changes the legal
  posture §I.6 and §I.7 both rest on. Scoped in A.7c, not built, and worth *saying out loud* in a
  sales conversation rather than leaving as an apparent oversight.
- **Loyalty programs.** Stays out. Note that **discount/promo codes are a different thing** —
  bank-partnership promos are how Indonesian cinemas actually discount, and Chain A's app lists vouchers
  and partner loyalty rewards as two separate menu items. Vouchers are the commercially relevant
  half and are worth revisiting after Phase 4B rather than folding into it.
- **Native apps, EDC/card terminals.** Unchanged, for the reasons already in §I.0 and §I.7.

---

## A.7 Lower-priority parity items

Scoped for completeness; none of these should delay a first sales conversation.

- **Purchase caps — built, both halves.** Chain A states 10 tickets/day, 8 per showtime, 30 with
  food. The 8 is `booking.max-seats-per-hold` and the 10/day is `booking.max-seats-per-day`, both
  enforced on the self-service path and both exempt at the counter. The 10/day was
  indeed **not** a few more lines, as this entry predicted: it is a per-customer count over a
  calendar day, so it took A.6's branch-timezone resolution, a row lock to survive one customer's
  two open tabs, and its own race test. The "30 with food" band presupposes F&B ordering, which is
  declined below.
- **Whole-hall / private hire.** Chain A takes private studio bookings via WhatsApp with ~5 working
  days' notice. `resource_pool.block_claim_mode = 'all_units'` **already models exactly this** —
  it is in the schema for the hospitality vertical and is currently unused by the ticketing UI.
  High margin for the tenant, and the mechanism exists. Cheap once A.1 lands.
- **Customer identity.** Ours is Google SSO; Chain A's is phone + PIN, which is the Indonesian norm and
  matters for a walk-in-dominant chain. Not a Phase 4B item, but the mismatch is worth naming
  before an owner names it.

---

## A.7b The prospect's actual shape

Gathered 2026-08-16 for a sales conversation, **read directly from the prospect's public site** and
parsed: its branch list and each branch's published price list. Those lists are not reproduced
here, because a branch list and per-branch prices would identify the prospect; the structural
findings are, because they are what changed this addendum.

**Sourcing note, and it matters for anyone repeating this.** Both the prospect's site and Chain A's
returned **HTTP 403 to the agent's own `WebFetch`**, which egresses from a US datacenter ASN. The
same URLs returned **200 to a plain `curl` from an Indonesian residential connection** — the
default `curl/8.x` user-agent was enough, so this is **CDN IP/ASN reputation, not header or
fingerprint detection**. Two further traps: the `www` host 301-redirected to the apex **dropping
the path**, so a deep link on `www` silently lands on the homepage and looks like a dead page; and
the WordPress sitemap listed a section that the site's own indexed pages no longer resolved. Fetch
the apex, with the trailing slash, from a local machine.

**Company:** one of Indonesia's larger independent cinema chains, with a few dozen branches, most of
them in Java's smaller cities. The estate moves — branches open, close and relocate — so re-read
the site before quoting a branch count.

**The price list is the finding that changes this addendum.** Every branch publishes the same
*structure*, with per-branch amounts (rounded here: tickets sit in the Rp 20,000–40,000 range
across bands and branches):

| Band | Relative to the band above |
|---|---|
| Mon–Wed | lowest |
| Thu–Fri | one step up (about Rp 5,000) |
| Sat–Sun | one step further up (about Rp 5,000) |
| Public holiday, **and the day before one** | the same as Sat–Sun |

Plus a bundled drink voucher added to every band — roughly Rp 8,000–15,000 depending on the branch,
with one branch publishing no voucher line at all.

**Five observations that bear directly on this addendum:**

1. **The prospect's branch sells tickets on-site only — there is no online booking system at all.
   CONFIRMED 2026-08-17 by a visit to a branch: staff stated it directly.** Corroborated two more
   ways — the branch's own social account never mentions online booking, and the chain's site
   publishes schedules and prices but has no basket, no seat picker, and no account. This is no
   longer an inference.

   So the sales message is not "replace your ticketing system", it is **"you do not have one"**. That also
   means §I.0's **counter-sale flow is the primary channel for this prospect, not a secondary
   one** — and it is already built and shipped. That is the strongest single fact in this
   document. It also reframes the whole addendum: every parity item here is a *second* priority
   behind simply being able to sell a seat online at all, which we can already do.
2. **Pricing is by calendar, not by seat.** Every branch prices on day-of-week plus a holiday
   rule; **no branch publishes a seat-category price**. This *corrects* an earlier assumption in
   this addendum: brand tier does not drive price either — a branch under the chain's premium
   sub-brand charges the same as an ordinary branch, while the real variation is geographic, by a
   few thousand rupiah between towns. A.6 is rewritten accordingly: build `by_calendar`, keep
   `by_category` scoped but unbuilt.
3. **"The day before a public holiday" is a pricing input**, which no date arithmetic can derive.
   This needs a maintained Indonesian `hari libur nasional` list. See A.6.
4. **The ticket price is bundled with an F&B voucher.** This is not the food-ordering problem — it
   is a fixed inclusion in the ticket price, per branch. It changes the F&B recommendation in A.6
   from a flat "out of scope" to "a bundled-inclusion line on the rate rule is in scope; an F&B
   ordering system is not".
5. **Per-branch price overrides are mandatory, and our schema already allows them** — `rate_rule`
   is keyed by `resource_pool_id`, so once A.1 introduces `site`, the natural key becomes the site.
   Worth confirming that a chain sets prices per branch rather than centrally; the published data
   says per branch.

   **Decided 2026-08-17, for whoever builds A.6:** keep the FK on `resource_pool_id` as it is and
   add an "apply to every hall in this branch" admin action instead of re-keying to `site`. No
   schema change, and — the actual reason — no precedence question about which rule wins when a
   hall-level and a branch-level rule both match. Now that A.1 has shipped, the branch is
   available to the admin UI to drive that bulk write.

**Studio count is not published anywhere and can only be obtained offline.** The site has no page
listing screens per branch — confirmed by reading the site, not assumed. From the 2026-08-17 branch
visit: **the visited branch has 3 studios.** Everything below that line is still open, and a branch
visit is the only way to get it:

- **Seat count and layout per studio — still unknown, and it is the number that matters** (see
  A.7d). Worth a second visit, or a photograph of the seat chart at the counter.
- Studio counts at the other branches. If 3 is typical, the chain is on the order of
  **100–150 studios** — but that is an extrapolation from a single branch and should be labelled as
  one, not quoted to the owner as fact.
- How the counter actually works today: paper chart, whiteboard, or a local POS; whether seats are
  even assigned; who reconciles cash at close.
- Who at the chain decides. Headquarters is in another city; the visited branch is the introduction
  route, not the decision-maker.

---

## A.7c Chain A re-analysed from the live site

The first pass of this addendum used Chain A's FAQ and corporate site, because its public site also
403s the agent's fetcher. Read directly (same local-`curl` method as A.7b), the live site shows
**three things the FAQ did not**, one of which is a genuine miss.

**Confirmed navigation:** Now Playing · Upcoming · Theaters · Promotions · News · Search · a
selector of several dozen cities · premium studio formats. A city list that long, set against the
prospect's, reinforces A.1 — city-first browse is the shape of this market, not a detail.

**1. Chain A's app is a stored-value wallet, not just an account. This is the real miss.**

The signed-in menu is: top up · balance · top-up history · purchase history · vouchers · partner
loyalty rewards · inbox. So the dominant Indonesian cinema payment pattern is **prepay a balance,
then spend it on tickets** — not a fresh payment per transaction, which is the only model we
implement. Our Phase 4 QRIS integration generates a per-booking dynamic QR; Chain A's QRIS moment
happens at *top-up*, and the booking itself is a **debit against a balance**.

Why this matters beyond convenience, and why it is not a small feature:
- A customer balance is **money the tenant is holding**, which is a liability on their books and a
  materially different regulatory posture from pass-through payment. §I.6's open Penyelenggara
  question and §I.7's billing model both assume we never hold customer funds. **A wallet would
  change that answer**, and possibly require licensing (uang elektronik) that a demo should not
  wander into casually.
- It interacts with A.5.1's refund gap: Chain A's stated remedy for a cinema-cancelled show is a
  refund **to the app balance within 24 hours**, versus 14 business days to a card. The wallet is
  precisely what makes their fast refund possible.

**Recommendation: scope it, do not build it, and raise it in a sales conversation as a deliberate
omission.** For the prospect — who today take cash at a counter and have no online system at all —
a wallet is not the gap; a working online sale is. But an owner who knows Chain A will ask, and "we
chose not to hold your customers' money, here is what that means for you" is a stronger answer than
being caught unaware. If it is ever built it is its own phase with its own legal review, not a
Phase 4B line item.

**2. Advance ticket sales are a first-class, merchandised section**, distinct from ordinary
showtimes — a labelled block of titles on sale before general release. We are **closer to this than
to anything else in this addendum**: `resource_pool_session.status` already distinguishes
`'scheduled'` from `'on_sale'`, and `sales_cutoff_at` already exists. What is missing is only that
nothing surfaces "on sale ahead of release" as a browsable grouping. Cheap once A.2 lands, and it
is a revenue feature a cinema owner immediately understands.

**3. Purchase History confirms A.4** as a standard expectation rather than a nice-to-have, and
**vouchers** confirm the A.6 note that vouchers are a separate concern from loyalty — Chain A lists
them as two distinct menu items, and the voucher one is the one that matters commercially.

---

## A.7d Onboarding: seat-map entry is the real adoption barrier, and our tooling makes it worse

**BUILT 2026-08-17 — no schema change needed**, because `resource_unit`
already carried `group_key` and `sequence_no`, which is exactly what a generator writes. A hall
is described one line per row (`A: 1-10`, `C: 1-4, 7-12`), which makes every irregular case in
the scope below fall out of the format: an **aisle is a real gap in `sequence_no`** (not a flag,
per §I.1's plain-integer requirement), rows may differ in length, and a skipped row label needs
no syntax. There is a preview before commit, a clone-another-hall path, and generation is
**additive and refuses on label collision** rather than overwriting — `reservation` references
`resource_unit`, so a destructive "regenerate" could orphan a booked seat. (A studio that has
genuinely been re-seated is served by **relayout**, added 2026-08-22: it retires what the new
layout does not name via `resource_unit.is_active`, never deletes, and refuses to retire a seat a
live booking claims.) Batch insert in one
transaction (`Db.executeBatch`). `SeatMapPlanTest` covers the layouts without a database;
`SeatMapGeneratorTest` covers the rest, including that a **generated hall still books through
the untouched `EXCLUDE` path**.

**CSV import is built too, 2026-08-22** — the condition above (a tenant turning up with an
existing seat file) is what arrived. It is a front door onto the same generator rather than a
second one: `SeatMapCsv` parses the file the tenant has — a header naming `row`/`seat`/`label`,
a spreadsheet grid (`A,1,2,3`), or a bare list of labels — and hands the result to the same
collector, the same preview and the same refuse-on-collision insert. A `studio` column is read
as a **filter**, so one sheet covering a whole branch imports one hall at a time; columns with
nowhere to go (a seat class, a price) are named back to the operator rather than dropped
silently; labels are kept exactly as written, because the point of importing rather than
generating is that these seats already have names. The traps are Excel's BOM, its
`;` list separator and its system code page; re-seating an existing hall is deliberately a
separate verb, **relayout**, described above.

The plan already predicted this. [`plan.md`](plan.md) says seat-map entry is *"likely the
tenant's single largest implementation cost"* — in the accounting discussion, as an argument about
what can be capitalised. The prospect's numbers turn that prediction into a blocker, and it deserves to be
read as a delivery risk rather than an accounting footnote.

**The arithmetic.** The visited branch has 3 studios (confirmed). If that is typical, a few dozen
branches is roughly **100–150 studios**. At a plausible 100–200 seats each, that is on the order of **15,000–25,000
`resource_unit` rows** before the chain can sell a single ticket. Both multipliers are estimates —
the seat count per studio is the one still missing, and it is the larger lever.

**Our tooling cannot do this.** `HallAdminResource.addSeat` takes **one seat per HTTP POST**, each
with a form round-trip and a `303` redirect back to the hall page
(`HallAdminResource.addSeat` in
`src/main/java/com/protek/booking/web/admin/HallAdminResource.kt`, as scoped; the generator above has since become the main path). There is no bulk
entry, no row/column generator, no import, and no copy-from-another-hall. Entering a 150-seat
studio means 150 form submissions; the chain means tens of thousands. **Onboarding the prospect with the
current admin UI is not slow, it is infeasible**, and no amount of Phase 4B parity work matters if
a tenant cannot get their inventory into the system.

**Scope — small, and the highest-leverage item in this document:**
- A **row/column generator**: given row labels (A–K) and seats per row, generate `resource_unit`
  rows in one transaction, with `group_key` = row and `sequence_no` = position. This is exactly the
  shape §I.1 already requires for contiguous-block arithmetic, so the generator writes the fields
  the booking path already depends on.
- Support the irregular cases a real hall has: gaps, aisles, rows of differing length, and
  non-sequential labels. §I.1 warns that `sequence_no` must stay a plain integer — a generator is
  the natural place to get that right once rather than per hand-entered seat.
- **Clone a studio's layout to another studio.** Multiplex halls repeat; a chain's halls repeat more.
- CSV import, and a preview before commit.

**For a sales conversation this converts a liability into an offer.** "Getting your seat maps in" is the
first objection a multi-branch owner will raise, and the honest answer today is that the admin UI
cannot do it. With a generator it becomes a one-time setup we can quote, schedule, and even
perform — and per the plan's own accounting note, it is the tenant's expense either way, so being
the one who makes it cheap is worth real goodwill.

---

## A.8 Proposed Phase 4B — market parity for a multi-branch chain

Slots into Part II **after Phase 4, independent of Phase 5**.

- **Ships:** `site`/branch hierarchy with city (A.1); `film` entity and film-first browse (A.2);
  classification display and attestation (A.3); order history, booking code, QR, and staff
  redemption (A.4); **calendar-based pricing** via the existing `rate_rule.type` discriminator,
  with a tenant-maintained public-holiday list and a bundled-inclusion line (A.6); an
  **advance-sales grouping** over the `sales_cutoff_at` / `'on_sale'` fields that already exist
  (A.7c); purchase caps (A.7).
- **DDL:** `site`; `resource_pool.site_id`; `film`; `resource_pool_session.film_id` (nullable);
  `reservation_group.booking_code`; `film.classification`; a per-tenant `public_holiday` list. All
  additive — no existing column changes type or nullability.
- **Resolves:** the browse structure a chain of that shape actually needs; two dead columns
  (`date_of_birth_attested`, `resource_unit.attributes`) and one ignored discriminator
  (`rate_rule.type`); the absence of a redeemable ticket.
- **Explicitly separate:** A.5.1's refund inconsistency, which should be fixed regardless, and the
  `confirmed → completed` audit attribution issue, which A.4 makes more visible.

**Suggested order: A.7d first, then A.1 → A.2 → A.3, then A.4.** A.7d (bulk seat-map entry) jumps
the queue because it is a delivery blocker rather than a parity gap — without it the tenant cannot
be onboarded at all, and it is the smallest item here. A.1–A.3 are then what make a demo look like
a chain's site rather than a prototype; A.4 is the largest and can follow.

**One framing note for whoever picks this up.** The 2026-08-17 branch visit established that the prospect
has *no* online booking. That makes almost everything in this addendum a second-order concern: the
first-order offer is the shipped Phase 1–4 flow plus A.7d so their inventory can actually be
loaded. Do not let the parity list here imply the product is not already sufficient to sell.

**Verification:** `./mvnw test`. New tables need RLS policies written with the `NULLIF` guard; copy
one from `V1__init_schema.sql`.

---

## A.9 What the build settled

Each item is built (see the table at the top); what is kept is the decision a later change must not
undo, and why.

**A.4 order history, booking code, staff redemption**
- **The list was a resource rebuild, not a method.** `{groupId}` was on the *class*, so a
  collection URL could not be spelled from inside. It is now on the five methods and
  `@Path("/reservations")` is the class; a second collection URL under `/reservations` inherits that
  shape for free.
- **The code and the token are two values on purpose, and must stay two.**
  `reservation_group.booking_code` is 8 dictatable characters per group, unique per tenant, and
  grants nothing; `reservation.admission_token` is 96 unguessable bits per seat and grants entry.
  `BookingCodeTest.theCodeIsNotTheAdmissionTokenUnderAnotherName` guards the obvious later
  "simplification" of displaying one value for both.
- **Redemption does NOT complete the reservation.** `'completed'` sits outside the EXCLUDE
  predicate exactly as `'admitted'` would, so a scan writing it would free the seat its holder just
  sat in. The sweep stays the only writer, because it waits for the period to end. The audit
  misattribution A.4 was blocked on (the trigger read `NEW.created_by_actor` and logged a staff
  redemption as the customer) is fixed by the `app.current_actor_type` GUC, so the sweep's
  completion is attributed to `system`.
- A genuine QR, if a phone-camera workflow ever justifies one, is a new dependency and a
  native-build question; `admission/Code128.kt` renders barcodes with neither (A.4 above says why Code 128).

**A.6 calendar pricing**
- **"The day before a public holiday" is not derivable from a date.** The tenant-editable
  `public_holiday` table plus `PublicHolidayRepository.findHolidayOrEve` serve it; no third-party
  calendar API, because the deployment must keep selling with its uplink down. The migration seeds only the five
  *fixed* national days; moveable days and *cuti bersama* come from the SKB each year via
  `/admin/holidays`.
- **Day-of-week is computed in the branch's timezone, not UTC.** `CalendarPricing.zoneFor` is the
  single answer: the **branch's** `site.timezone` when the hall has a branch, the hall's own only
  when it has none, because `resource_pool.timezone` is `NOT NULL DEFAULT` and cannot act as an
  override. Anything needing a local date or offset calls it (A.7's per-day cap was the first
  caller, `ScreeningEvent.startDate` the second).
- `rate_rule`'s FK stays on `resource_pool_id`; `CatalogService.setRate(…, applyToBranch)` is the
  "every hall in this branch" action (A.7b observation 5). No schema change, no precedence question.
- `rule_definition.inclusions` covers the prospect's bundled drink voucher, read back through
  `RateRuleRepository.findActiveAt` so a repricing cannot restate an old ticket. F&B *ordering*
  stays out of scope: no exclusivity constraint, so it reuses none of the `EXCLUDE` spine.
  `by_category` stays scoped and unbuilt: A.7b found no evidence the prospect prices by seat.

**A.7 purchase caps**
- **Per-showtime (8)**, `booking.max-seats-per-hold`, enforced above the transaction beside the
  empty-selection check. **Per hold, never aggregated per customer**: every walk-in shares one
  customer row, so a per-customer count refuses the ninth counter ticket of the evening.
- **Per-day (10)**, `booking.max-seats-per-day`, a customer-scoped count over a local day inside
  the transaction because it reads rows, using `CalendarPricing.zoneFor` and never a UTC
  `date_trunc`. It is *not* a wider `WHERE` on the per-hold check; a policy check is a comparison
  or a query, and these are one of each.
- A cap decided by counting needs a lock to be a cap at all, and the race test needs *different
  showings* to expose it, because holds on one showing are serialised by its `FOR UPDATE`.
- Both numbers are one config value per deployment, not per tenant; per-tenant means a migration,
  RLS and an admin screen, and no tenant has asked.

**A.7d CSV seat-map import**
- **"A second entry path to maintain" was answered by not building one.** Import is a front door
  onto the generator: `SeatMapPlan.Collector` holds the rules both obey, and preview,
  refuse-on-collision and the one-transaction batch insert are the generator's own path. A rule
  added to one is added to both; a private validation path inside the CSV reader would undo the
  reason this was buildable.
- **A studio column in the file is a filter, not an extra column.** A chain hands over one sheet per
  branch, and the failure to design against is importing all of it into whichever hall was open.
- Import stays additive and refuses an existing label. Re-seating is a different verb: tick
  *replace the whole map* on any of the four entry forms; it retires what the new layout does not
  name and never overwrites. A seat a live booking claims cannot be retired.

**A.3 age-classification attestation**
- The attestation is input validation before the insert loop, never a pre-check in front of the
  `EXCLUDE` constraint, and sits above the day cap so a refusal has not taken the customer lock.
- **`film.classification` stays nullable**: `NOT NULL` would claim a control the code did not
  implement. An unrated film and a `label`-only showing both still sell.
- No migration was needed: `customer.date_of_birth_attested` and `consent_record` have been in the
  initial schema.
- **The boolean can never be read back.** One flag, three restricted thresholds: a remembered 13+
  attestation would clear a 21+ film. The customer is asked every time; the flag is a record, not a
  permission, and the per-attestation `consent_record` row carries which rating and when.
- **The counter is exempt**, for the same reason as A.7's caps: every walk-in shares one `customer`
  row.
