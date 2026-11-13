-- The whole schema, and the demo seed, in one file. Every query in the Kotlin code is verified
-- against a real, migrated instance of exactly this DDL before being written.
--
-- PostgreSQL 18 or later, by use rather than by a version check: uuidv7() (18), UNIQUE NULLS
-- NOT DISTINCT (15), lz4 column compression and a SQL-standard function body (14), a generated
-- column and a nondeterministic ICU collation (12). Each is used where it saves doing the same
-- thing by hand, and says so.
--
-- Concurrency is audited against Adya's anomaly taxonomy rather than an isolation level, and
-- the table of which anomaly each write path (hold, confirm, cancel) can produce is
-- BookingService's class comment. Everything runs at READ COMMITTED; what closes the anomalies
-- lives here: reservation_no_overlap turns two claims on one seat into a write-time conflict
-- (G2-item), and the rows the service locks FOR UPDATE close the predicate cases.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
-- What the reservation_no_overlap EXCLUDE constraint needs; a managed PostgreSQL that does not
-- allow it cannot host this schema.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Equal ignoring case, so a UNIQUE column under it refuses Foo@x beside foo@x. ICU level 2
-- ("ks-level2") folds case but not accents. The server must be built --with-icu; PGDG and the
-- postgres image both are.
CREATE COLLATION case_insensitive (provider = icu, locale = 'und-u-ks-level2', deterministic = false);

-- ===========================================================================================
-- Functions the tables below use as defaults
-- ===========================================================================================

-- reservation_group.booking_code. The alphabet is the design. 0/O, 1/I/L, 5/S, 2/Z and 8/B are
-- the pairs people mishear and mistype, and every one of them is excluded — 8 digits (2-9) and
-- 19 letters, 27 symbols. A code that has to survive a phone line in a cinema foyer cannot
-- contain a character whose reading depends on the listener's font.
--
-- Eight characters: 27^8 ~= 2.8e11 (see idx_reservation_group_booking_code for what that buys).
-- gen_random_bytes rather than random() only because pgcrypto is already a dependency and one
-- source of randomness in a schema beats two; this value is NOT a credential, so the modulo
-- bias (the first 13 symbols are drawn 10 times in 256 rather than 9) is deliberately not
-- corrected.
--
-- VOLATILE, and load-bearing that it is: a STABLE function in a column DEFAULT would be
-- evaluated once per statement, so a multi-row INSERT would give every row the same code.
--
-- A SQL-standard body rather than a quoted string: it is parsed here, so a typo fails the
-- migration instead of the first booking, and its use of pgcrypto is recorded as a dependency.
CREATE FUNCTION generate_booking_code() RETURNS text
    LANGUAGE sql VOLATILE
    RETURN (SELECT string_agg(
                substr('23456789ACDEFGHJKMNPQRTVWXY',
                       1 + (get_byte(gen_random_bytes(1), 0) % 27), 1), '')
            FROM generate_series(1, 8));

COMMENT ON FUNCTION generate_booking_code() IS
    'Eight characters from a 27-symbol alphabet with every look-alike pair removed. A quotable '
    'reference, never a credential: see reservation_group.booking_code.';

-- ===========================================================================================
-- Control plane: not tenant-isolated
-- ===========================================================================================

-- Shared regardless of a tenant's own data_mode. Deliberately NOT under RLS, because it's what
-- implements isolation for everything else.
CREATE TABLE tenant (
    tenant_id            uuid PRIMARY KEY DEFAULT uuidv7(),
    slug                 text NOT NULL UNIQUE,
    display_name         text NOT NULL,
    vertical             text NOT NULL CHECK (vertical IN ('hospitality','live_entertainment')),
    compute_mode         text NOT NULL DEFAULT 'shared'    CHECK (compute_mode IN ('shared','dedicated')),
    data_mode            text NOT NULL DEFAULT 'shared'    CHECK (data_mode    IN ('shared','dedicated')),
    domain_mode          text NOT NULL DEFAULT 'subdomain' CHECK (domain_mode  IN ('subdomain','custom')),
    custom_domain        text UNIQUE,
    dedicated_db_host    text,
    dedicated_db_name    text,
    jurisdiction         text NOT NULL DEFAULT 'ID',
    complaint_phone      text NOT NULL,
    pse_registration_no  text,
    status               text NOT NULL DEFAULT 'active' CHECK (status IN ('active','suspended','offboarding')),
    created_at           timestamptz NOT NULL DEFAULT now(),
    -- The parallel-sales-channel liability boundary. The platform guarantees no
    -- double-booking of inventory managed exclusively through this system and cannot guarantee
    -- anything about a walk-in paper ledger or a second SaaS it has no visibility into. That
    -- boundary is contractual, but whether such a channel exists is what a conflict
    -- investigation would need to establish first, so it is held here and not only in a PDF.
    other_sales_channels_disclosed boolean NOT NULL DEFAULT false,
    other_sales_channels_detail    text,
    CONSTRAINT dedicated_fields_consistent CHECK (
        (data_mode = 'dedicated' AND dedicated_db_host IS NOT NULL AND dedicated_db_name IS NOT NULL)
        OR (data_mode = 'shared' AND dedicated_db_host IS NULL AND dedicated_db_name IS NULL)
    ),
    -- The flag and its detail can't disagree: a disclosed channel with no description is not a
    -- disclosure.
    CONSTRAINT other_sales_channels_consistent CHECK (
        (other_sales_channels_disclosed AND other_sales_channels_detail IS NOT NULL
            AND length(btrim(other_sales_channels_detail)) > 0)
        OR (NOT other_sales_channels_disclosed AND other_sales_channels_detail IS NULL)
    )
);

-- The tenant-platform agreement. Its own table rather than terms_version or policy_document:
-- those govern the END CUSTOMER's terms for a booking, this governs the TENANT's relationship
-- with the platform. Same versioning shape, different party, different lifecycle.
--
-- Platform-level, so NOT tenant-scoped and NOT under RLS: it is the platform's own document,
-- identical for every tenant, in the same control-plane class as `tenant`.
CREATE TABLE platform_agreement_version (
    platform_agreement_version_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    version_label                  text NOT NULL UNIQUE,
    content                        text NOT NULL,
    -- Generated, so the hash and the text it names cannot disagree: no INSERT can supply one.
    content_hash                   text NOT NULL
        GENERATED ALWAYS AS (encode(digest(content, 'sha256'), 'hex')) STORED,
    effective_from                timestamptz NOT NULL DEFAULT now(),
    created_at                     timestamptz NOT NULL DEFAULT now()
);

-- ===========================================================================================
-- Venues: branch, hall, seat, film, showing
-- ===========================================================================================

-- A branch. `resource_pool` is a single auditorium, and a chain of 38 branches
-- at 2-6 studios each is 100-200 of them, most called some variant of "Studio 1"; this is what
-- groups them, and what the browse path city -> cinema -> film/showtime starts from.
CREATE TABLE site (
    site_id     uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id   uuid NOT NULL REFERENCES tenant(tenant_id),
    name        text NOT NULL,
    -- city is NOT NULL because it is the first level of the browse path, and a branch
    -- that cannot be found by city is a branch that cannot be found. province is
    -- nullable: it groups cities in a chain-wide list but nothing navigates by it alone.
    city        text NOT NULL,
    province    text,
    address     text,
    -- One timezone does not survive a real chain: an estate spanning
    -- Kalimantan and Java is not one timezone. resource_pool.timezone stays as a per-hall
    -- override.
    timezone    text NOT NULL DEFAULT 'Asia/Jakarta',
    -- For "nearest city" (SiteRepository.findCities ranks a city by the mean of its branches).
    -- numeric rather than a PostGIS type: this is display and ordering data, and a PostGIS
    -- dependency for two numbers would be a real operational cost for a self-hosted database.
    latitude    numeric(9,6),
    longitude   numeric(9,6),
    is_active   boolean NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    -- The public URL (/cinemas/{slug}): the query that matters is a local one — "bioskop
    -- Rekaan" — and the URL is one of the few places on a thin branch page where those words
    -- can appear. Slug.of() derives it.
    slug        text NOT NULL,
    -- Unique per tenant, not globally: two tenants may both run a "Grand Cinema". It is also
    -- the mechanism rather than a nicety — CatalogService picks a free suffix before inserting,
    -- but that pre-check races, and this constraint is what makes the race a failed insert
    -- instead of two branches sharing a URL. (Not the rule about the EXCLUDE constraint:
    -- that one forbids a pre-check because losing the race there sells one seat twice. Losing
    -- this one shows an operator an error on a form they are looking at.)
    CONSTRAINT site_slug_unique_per_tenant UNIQUE (tenant_id, slug)
);
-- The browse path's only query shape: the cities a tenant operates in, then that city's
-- branches by name.
CREATE INDEX idx_site_city ON site (tenant_id, city, name);

CREATE TABLE resource_pool (
    resource_pool_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL REFERENCES tenant(tenant_id),
    name              text NOT NULL,
    address           text,
    timezone          text NOT NULL DEFAULT 'Asia/Jakarta',
    block_claim_mode  text NOT NULL DEFAULT 'none' CHECK (block_claim_mode IN ('none','all_units','contiguous_n')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- Nullable on purpose: a hall with no branch is still a hall. The application treats NULL
    -- as "not yet assigned to a branch" and keeps rendering it rather than hiding it, because a
    -- hall that silently disappeared from the catalogue would be a far worse failure than an
    -- ungrouped one.
    site_id           uuid REFERENCES site(site_id)
);
CREATE INDEX idx_resource_pool_site ON resource_pool (site_id);

-- A seat. Retiring one is is_active = false, never a DELETE: reservation references the row.
CREATE TABLE resource_unit (
    resource_unit_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL REFERENCES tenant(tenant_id),
    resource_pool_id  uuid NOT NULL REFERENCES resource_pool(resource_pool_id),
    label             text NOT NULL,
    group_key         text,
    sequence_no       integer,
    attributes        jsonb NOT NULL DEFAULT '{}',
    is_active         boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_resource_unit_pool ON resource_unit(resource_pool_id);
CREATE INDEX idx_resource_unit_adjacency ON resource_unit(resource_pool_id, group_key, sequence_no);

-- The film a showing is showing: the film-first browse path ("pick a title, see
-- where and when it plays"), the poster grid, and reporting by title all hang off it.
CREATE TABLE film (
    film_id           uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL REFERENCES tenant(tenant_id),
    title             text NOT NULL,
    duration_minutes  integer CHECK (duration_minutes IS NULL OR duration_minutes > 0),
    -- LSF age classification. Constrained rather than free text because it is regulatory in
    -- Indonesia and a typo'd rating is worse than a missing one. Nullable, and that is the
    -- honest state: a film with no rating takes the unknown-rating age attestation
    -- (AgeClassification), not an invented one.
    classification    text CHECK (classification IS NULL OR classification IN ('SU', '13+', '17+', '21+')),
    synopsis          text,
    poster_url        text,
    trailer_url       text,
    release_date      date,
    is_active         boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    -- /films/{slug}, for the same reason as site.slug, and unique per tenant for the same reason.
    slug              text NOT NULL,
    CONSTRAINT film_slug_unique_per_tenant UNIQUE (tenant_id, slug)
);
CREATE INDEX idx_film_title ON film (tenant_id, title);

-- A showing: in the schema's vertical-neutral terms, a period during which a pool's units can be
-- claimed — a cinema showing here, a table sitting or a room night elsewhere.
CREATE TABLE resource_pool_session (
    resource_pool_session_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid NOT NULL REFERENCES tenant(tenant_id),
    resource_pool_id          uuid NOT NULL REFERENCES resource_pool(resource_pool_id),
    label                     text NOT NULL,
    starts_at                 timestamptz NOT NULL,
    ends_at                   timestamptz NOT NULL,
    sales_cutoff_at           timestamptz,
    status                    text NOT NULL DEFAULT 'scheduled' CHECK (status IN ('scheduled','on_sale','closed','cancelled')),
    created_at                timestamptz NOT NULL DEFAULT now(),
    -- Nullable, and it stays nullable: a label-only session is what the hospitality mapping and
    -- every non-film event uses, so `label` is kept rather than replaced by the film.
    film_id                   uuid REFERENCES film(film_id)
);
-- The film-first browse path's query: this film's upcoming showings.
CREATE INDEX idx_session_film ON resource_pool_session (tenant_id, film_id, starts_at);

-- ===========================================================================================
-- People: customers and logins
-- ===========================================================================================

CREATE TABLE terms_version (
    terms_version_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL REFERENCES tenant(tenant_id),
    version_label     text NOT NULL,
    content_hash      text NOT NULL,
    effective_from    timestamptz NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE customer (
    customer_id                uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                  uuid NOT NULL REFERENCES tenant(tenant_id),
    full_name                  text NOT NULL,
    phone                      text,
    email                      text,
    date_of_birth_attested     boolean NOT NULL DEFAULT false,
    terms_accepted_version_id  uuid REFERENCES terms_version(terms_version_id),
    terms_accepted_at          timestamptz,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    -- The walk-in placeholder. A cash walk-in has no identity, but
    -- reservation_group.customer_id is NOT NULL. Making it nullable would push a null branch
    -- into every query that joins through customer, forever; one placeholder row per tenant
    -- keeps the FK graph intact, and counter sales appear in exactly the same queries as
    -- self-service bookings.
    is_walk_in_placeholder     boolean NOT NULL DEFAULT false,
    -- The age the profile page shows. Nullable, and expected to be NULL on this deployment:
    -- Google's `birthdate` claim is not in the `openid email profile` scopes this app requests,
    -- so it is filled only when a provider supplies it (AppUserAugmentor.birthdateClaim), and
    -- the page says "not provided" rather than inventing an age.
    date_of_birth              date
);
-- Exactly one placeholder per tenant — a second would silently split counter-sale history in
-- two, so the constraint is the enforcement, not provisioning discipline.
CREATE UNIQUE INDEX idx_customer_walk_in_per_tenant
    ON customer(tenant_id) WHERE is_walk_in_placeholder;

-- Authenticated identity to role and tenant: the three roles, with tenant_staff scoped to
-- one tenant, never global.
--
-- DELIBERATELY NOT UNDER RLS, and this is the one exemption that needs justifying: RLS scopes
-- rows by app.current_tenant_id, and this is the table consulted to DISCOVER it. Under FORCE ROW
-- LEVEL SECURITY with no context set, every lookup here would return zero rows and no request
-- could ever authenticate. A table cannot be protected by the mechanism it bootstraps.
--
-- The safety property that replaces RLS here: the lookup key is the OIDC subject (or verified
-- email) out of a signature-validated ID token, which the caller cannot forge. Every read of
-- this table is by that key alone — never by a tenant_id the caller supplied.
CREATE TABLE app_user (
    app_user_id   uuid PRIMARY KEY DEFAULT uuidv7(),
    subject       text UNIQUE,
        -- the OIDC 'sub' claim, NULL until this user's first actual login: staff and
        -- admins are provisioned by email (the only identifier known before they've ever
        -- signed in), and the subject is bound on first login and preferred thereafter.
        -- Google's 'sub' is stable where an email address is reassignable, so once it's
        -- known it, not the email, is the identity.
    -- case_insensitive: the lookups were always lower(email) = lower(?), and now the
    -- uniqueness agrees with them instead of admitting a second row that differs by case.
    email         text COLLATE case_insensitive NOT NULL UNIQUE,
    role          text NOT NULL CHECK (role IN ('customer','tenant_staff','platform_admin')),
    tenant_id     uuid REFERENCES tenant(tenant_id),
    customer_id   uuid REFERENCES customer(customer_id),
        -- set for role='customer' once they've booked, linking the login to the
        -- tenant-scoped customer row that owns their reservation_groups
    is_active     boolean NOT NULL DEFAULT true,
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_login_at timestamptz,
    -- What /admin/staff is gated on. A column rather than a fourth role: a manager IS staff plus
    -- one permission — they still sell at the counter and scan at the door — and a new role
    -- would mean revisiting every @RolesAllowed(TENANT_STAFF), where a role nobody carries
    -- silently matches nobody.
    can_manage_staff boolean NOT NULL DEFAULT false,
    -- platform_admin is the only global role; a tenant_staff or customer identity with
    -- no tenant is meaningless, and a platform_admin pinned to one tenant would be a
    -- quieter, more confusing bug than a rejected insert.
    CONSTRAINT app_user_role_tenant_scope CHECK (
        (role = 'platform_admin' AND tenant_id IS NULL)
        OR (role IN ('customer','tenant_staff') AND tenant_id IS NOT NULL)
    ),
    CONSTRAINT app_user_customer_only_for_customer_role CHECK (
        customer_id IS NULL OR role = 'customer'
    ),
    -- A customer with the flag is meaningless, and a platform_admin already reaches every tenant.
    CONSTRAINT app_user_manage_staff_is_staff_only CHECK (
        NOT can_manage_staff OR role = 'tenant_staff'
    )
);
CREATE INDEX idx_app_user_tenant ON app_user(tenant_id) WHERE tenant_id IS NOT NULL;

-- The acceptance IS tenant-scoped: it's a fact about one tenant.
CREATE TABLE tenant_agreement_acceptance (
    tenant_agreement_acceptance_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                       uuid NOT NULL REFERENCES tenant(tenant_id),
    platform_agreement_version_id   uuid NOT NULL REFERENCES platform_agreement_version(platform_agreement_version_id),
    accepted_at                     timestamptz NOT NULL DEFAULT now(),
    accepted_by_name                text NOT NULL,
    accepted_by_email               text NOT NULL,
    ip_address                      inet,
    UNIQUE (tenant_id, platform_agreement_version_id)
);

-- ===========================================================================================
-- Pricing and policy
-- ===========================================================================================

-- Immutable, versioned booking-time policy snapshot. reservation.policy_document_id
-- points at the exact version in effect when that booking was made.
CREATE TABLE policy_document (
    policy_document_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id           uuid NOT NULL REFERENCES tenant(tenant_id),
    doc_type            text NOT NULL CHECK (doc_type IN ('terms','cancellation_policy','privacy')),
    version             integer NOT NULL,
    content             text NOT NULL,
    effective_from      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, doc_type, version)
);

-- rule_definition is jsonb so a vertical-specific shape (`flat`, calendar pricing's
-- `by_calendar` day bands) needs no migration.
CREATE TABLE rate_rule (
    rate_rule_id      uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id         uuid NOT NULL REFERENCES tenant(tenant_id),
    resource_pool_id  uuid NOT NULL REFERENCES resource_pool(resource_pool_id),
    rule_definition   jsonb NOT NULL,
    effective_from    timestamptz NOT NULL DEFAULT now(),
    effective_to      timestamptz
);

-- The public-holiday list calendar pricing prices from. "The day before a public
-- holiday" is not derivable from a date: Indonesia's national holidays are set annually by SKB 3
-- Menteri and most are lunar, so it has to be a maintained list. Deliberately not a third-party
-- calendar API: this server must keep selling tickets when its uplink is down, and a pricing
-- input that can time out will one day silently fall back to the wrong band.
--
-- Tenant-scoped, which duplicates the national days across tenants: regional days are not
-- national days (Nyepi effectively closes Bali and nowhere else), and a tenant must be able to
-- add its own closure.
CREATE TABLE public_holiday (
    public_holiday_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id          uuid NOT NULL REFERENCES tenant(tenant_id),
    -- A `date`, not a timestamptz, and that is the point rather than a shortcut. A national
    -- holiday is a calendar day, not an instant: 17 Agustus is 17 Agustus in Jayapura and in
    -- Medan, at different absolute times. The band lookup turns the showing's start into a
    -- local date in the branch's timezone first (CalendarPricing) and compares dates to dates,
    -- so no timezone conversion is ever applied to this column.
    holiday_date       date NOT NULL,
    -- What it is, for the admin list and for the "why is this priced at the top band" answer
    -- staff get asked at the counter. 'Idul Fitri', 'Cuti Bersama Idul Fitri', 'Nyepi'.
    name               text NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    -- One row per date, so "is this date a holiday" is one bit rather than a count. Two
    -- holidays can fall on one day (Nyepi and Idul Fitri collided in 2025), and the answer is
    -- to name both in one row. This UNIQUE is also the index findHolidayOrEve uses.
    UNIQUE (tenant_id, holiday_date)
);

-- ===========================================================================================
-- Bookings
-- ===========================================================================================

CREATE TABLE reservation_group (
    reservation_group_id     uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid NOT NULL REFERENCES tenant(tenant_id),
    resource_pool_id         uuid NOT NULL REFERENCES resource_pool(resource_pool_id),
    customer_id              uuid NOT NULL REFERENCES customer(customer_id),
    requested_unit_count     integer NOT NULL DEFAULT 1,
    status                   text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','confirmed','failed','cancelled')),
    failed_resource_unit_id  uuid REFERENCES resource_unit(resource_unit_id),
    created_at               timestamptz NOT NULL DEFAULT now(),
    confirmed_at             timestamptz,
    -- The code a customer reads down the phone. A different credential from
    -- reservation.admission_token, with opposite requirements:
    --
    --   admission_token   96 unguessable bits, per SEAT, single-use, grants ENTRY.
    --   booking_code      short, per GROUP, quotable, grants NOTHING — "I am calling about
    --                     K7M2QPRT" is safe to say in a queue because it opens no door.
    --
    -- One value cannot be both unguessable and dictatable. A column DEFAULT rather than an
    -- application-side mint, so there is no mint path to forget: a group has a code from birth.
    booking_code             text NOT NULL DEFAULT generate_booking_code()
);
COMMENT ON COLUMN reservation_group.booking_code IS
    'Short human-readable reference for this booking, unique per tenant. Safe to disclose: it '
    'grants nothing. The entry credential is reservation.admission_token, which is per seat.';

-- Unique PER TENANT, the deliberate opposite of idx_reservation_admission_token: a code only has
-- to be unambiguous to the cinema whose staff look it up, and every query that reads it carries
-- a tenant_id. It is the sole arbiter of uniqueness — nothing pre-checks the code — so a
-- duplicate surfaces as a 23505 here and ReservationGroupRepository.insert re-mints and
-- retries. At 100k bookings for one tenant the chance any one insert collides is about 1 in 2.8
-- million.
CREATE UNIQUE INDEX idx_reservation_group_booking_code
    ON reservation_group (tenant_id, booking_code);
-- "Show me my bookings", newest first — the only order the page asks for, so it is in the index
-- rather than in a sort.
CREATE INDEX idx_reservation_group_customer_history
    ON reservation_group (tenant_id, customer_id, created_at DESC);

-- THE CORE: the exclusion constraint that makes double-booking structurally
-- impossible, not merely checked-for.
--
-- Keep reservation_no_overlap the last item in the table, with nothing but whitespace between it
-- and the comma before it: SeatBookingConcurrencyDifferentialTest strips it out of this file with
-- a regex anchored on that comma (a comment there once made the regex miss, and the test failed
-- because the constraint was still doing its job).
CREATE TABLE reservation (
    reservation_id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid NOT NULL REFERENCES tenant(tenant_id),
    reservation_group_id      uuid NOT NULL REFERENCES reservation_group(reservation_group_id),
    resource_unit_id          uuid NOT NULL REFERENCES resource_unit(resource_unit_id),
    resource_pool_session_id  uuid REFERENCES resource_pool_session(resource_pool_session_id),
        -- also load-bearing for the realtime seat-freed notification, which needs this to know
        -- which session's watchers to look up
    policy_document_id        uuid REFERENCES policy_document(policy_document_id),
    period                    tstzrange NOT NULL,
    status                    text NOT NULL DEFAULT 'held' CHECK (status IN ('held','confirmed','cancelled','expired','completed')),
    price_amount              numeric(14,2) NOT NULL,
    price_currency            text NOT NULL DEFAULT 'IDR',
    hold_expires_at           timestamptz,
    idempotency_key           uuid,
    created_by_actor          text NOT NULL DEFAULT 'customer' CHECK (created_by_actor IN ('customer','tenant_staff','system')),
    confirmed_at              timestamptz,
    cancelled_at              timestamptz,
    cancellation_reason       text,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    -- What a customer presents at the door: a DISTINCT credential, not reservation_group_id. An
    -- admission credential that is also a URL path segment is guessable-adjacent, and a group id
    -- is a durable handle where this is a one-shot entry credential. Per seat, because a party
    -- does not necessarily arrive together — and it is the shape Google Wallet wants, one
    -- eventTicketObject per seat.
    --
    -- 12 random bytes = 96 bits: every character is ~11 modules of barcode width, it has to stay
    -- legible on a phone and on counter-printer paper, and 96 bits is far past guessable for a
    -- credential valid for one showing. BPT- makes a scan self-describing in a log line, where it
    -- and a BP- payment reference would otherwise look alike. A column DEFAULT, so every
    -- reservation has one from birth and no confirm path can forget to mint it; holding a token
    -- is not being admissible — the state below decides that.
    admission_token           text NOT NULL
        DEFAULT ('BPT-' || upper(encode(gen_random_bytes(12), 'hex'))),
    -- Admission is NOT a `status` value, which is the obvious first design and is wrong in a way
    -- that costs a seat: status = 'admitted' would fall outside reservation_no_overlap's
    -- predicate below, and the seat someone is sitting in would become bookable again (the seat
    -- map filters on the same two values). So a reservation stays `confirmed` and gains an
    -- admitted_at; the door's UPDATE carries `AND admitted_at IS NULL`, which is what makes
    -- double entry structurally impossible rather than checked-for.
    admitted_at               timestamptz,
    -- Who scanned it — the answer to "who let this person in" is a column on the row.
    admitted_by_app_user_id   uuid REFERENCES app_user(app_user_id),
    CONSTRAINT reservation_no_overlap
        EXCLUDE USING gist (resource_unit_id WITH =, period WITH &&)
        WHERE (status IN ('held','confirmed'))
);
CREATE INDEX idx_reservation_group ON reservation(reservation_group_id);
CREATE INDEX idx_reservation_status_expiry ON reservation(status, hold_expires_at) WHERE status = 'held';
CREATE UNIQUE INDEX idx_reservation_idempotency ON reservation(tenant_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
    -- this index is what actually enforces the guarantee an idempotency key is supposed to
    -- provide; the column alone doesn't stop a retried request from double-booking
-- The confirmed -> completed sweep (the lifecycle's "showing date has passed"). upper(period)
-- rather than a separate ends_at column: the range IS the source of truth for when a claim runs,
-- and a duplicated end timestamp could drift from it. Partial, so it stays small as completed
-- history accumulates in a table that is never pruned (a ten-year retention floor).
CREATE INDEX idx_reservation_confirmed_completion
    ON reservation (tenant_id, upper(period))
    WHERE status = 'confirmed';
-- Globally unique rather than per tenant: the token is an unambiguous identifier of one seat,
-- and tenant isolation is RLS's job, so a scanner in tenant A presenting tenant B's token reads
-- as an unknown code (zero rows) rather than as someone else's ticket. Every door scan is a
-- lookup by token, so this is also its only index — there is deliberately none on admitted_at.
CREATE UNIQUE INDEX idx_reservation_admission_token ON reservation (admission_token);

-- The `booking_ref` in /booking?booking_ref=<uuid>. A table rather than the
-- showing id in the URL: the page carries DATE and TIME fields, and changing either has to keep
-- the customer on the same booking — with the session id in the URL, changing the time would
-- change the reference. The film, branch and hall are deliberately NOT here: all three are
-- derivable from the showing, and a copy could disagree with it.
--
-- KEYED ON app_user, NOT ON customer: a customer row is created at hold time
-- (BookingService.resolveBookingCustomer), so a customer who has just signed in and never booked
-- has none — and that is exactly the person opening their first draft.
--
-- RLS isolates tenants, not customers within a tenant, so the policy does NOT stop one customer
-- opening another's booking_ref; the ownership check in BookingDraftService does. A uuid in a
-- query string looks like a capability and is not one.
CREATE TABLE booking_draft (
    booking_ref              uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                uuid NOT NULL REFERENCES tenant(tenant_id),
    app_user_id              uuid NOT NULL REFERENCES app_user(app_user_id),
    resource_pool_session_id uuid NOT NULL REFERENCES resource_pool_session(resource_pool_session_id),
    created_at               timestamptz NOT NULL DEFAULT now(),
    -- Moves when the date/time fields move it. created_at stays put, so "when did this
    -- customer start choosing" and "when did they last touch it" remain separate facts.
    updated_at               timestamptz NOT NULL DEFAULT now(),
    -- A draft is a browsing position, not a claim on anything: it holds no seat, blocks
    -- nothing, and expiring it costs the customer a click. It exists so the sweep has a
    -- rule to prune by, the same shape resource_pool_watch uses.
    expires_at               timestamptz NOT NULL
);
-- The two query shapes: "render this ref" (the primary key) and "prune what has expired".
CREATE INDEX idx_booking_draft_expiry ON booking_draft (tenant_id, expires_at);
-- And the third: "this customer already has a draft for this showing", which is what stops
-- a double-tap on a showtime minting two refs for one intention.
CREATE UNIQUE INDEX idx_booking_draft_one_per_showing
    ON booking_draft (tenant_id, app_user_id, resource_pool_session_id);

-- Realtime subscription registry — replaces what an in-memory registry or LISTEN/NOTIFY
-- would otherwise need; neither fits a scale-to-zero, multi-instance model.
CREATE TABLE resource_pool_watch (
    resource_pool_watch_id    uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid NOT NULL REFERENCES tenant(tenant_id),
    resource_pool_id          uuid NOT NULL REFERENCES resource_pool(resource_pool_id),
    resource_pool_session_id  uuid REFERENCES resource_pool_session(resource_pool_session_id),
    customer_id               uuid REFERENCES customer(customer_id),
    fcm_registration_token    text NOT NULL,
    registered_at             timestamptz NOT NULL DEFAULT now(),
    expires_at                timestamptz NOT NULL DEFAULT (now() + interval '20 minutes')
        -- a safety net, not the primary cleanup path: the client deletes its own watch
        -- when the seat-map page closes; this only catches crashes and dropped connections
);
-- NULLS NOT DISTINCT: a pool-wide watch (no session) is one row per token, not one per insert.
CREATE UNIQUE INDEX idx_resource_pool_watch_dedupe ON resource_pool_watch
    (resource_pool_id, resource_pool_session_id, fcm_registration_token) NULLS NOT DISTINCT;
CREATE INDEX idx_resource_pool_watch_lookup ON resource_pool_watch (resource_pool_id, resource_pool_session_id, expires_at);

-- The buyer's own push channel, opted into on the order page (web and app), for the three events
-- the buyer is also mailed: showing cancelled, refund sent, refund refused.
--
-- Its own table, not resource_pool_watch: that one is keyed by pool and session, expires after 20
-- minutes and is what seat-map watchers are sent "seats free / taken" from. An order's watch must
-- outlive the page by days and reach only this order's buyer, so sharing the table would either
-- send seat news to buyers or order news to bystanders.
--
-- language is what the subscriber read at opt-in (the /id prefix, the cookie or Accept-Language),
-- so the push goes out in one language from the bundle rather than in two like the mail.
--
-- No expires_at: the row lives until the buyer turns it off, FCM reports the token dead (pruned as
-- SeatAvailabilityNotifier.deliver prunes), or the order is deleted.
CREATE TABLE order_watch (
    order_watch_id          uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id               uuid NOT NULL REFERENCES tenant(tenant_id),
    reservation_group_id    uuid NOT NULL REFERENCES reservation_group(reservation_group_id) ON DELETE CASCADE,
    fcm_registration_token  text NOT NULL,
    language                text NOT NULL CHECK (language IN ('en', 'id')),
    registered_at           timestamptz NOT NULL DEFAULT now(),
    -- A second tap, a resend or a re-registered FID is the same watch: an upsert, never two pushes.
    UNIQUE (reservation_group_id, fcm_registration_token)
);

-- ===========================================================================================
-- Payment (QRIS through Midtrans, and cash at the counter)
-- ===========================================================================================

CREATE TABLE payment (
    payment_id                  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                   uuid NOT NULL REFERENCES tenant(tenant_id),
    reservation_group_id        uuid NOT NULL REFERENCES reservation_group(reservation_group_id),
    amount                      numeric(14,2) NOT NULL,
    currency                    text NOT NULL DEFAULT 'IDR',
    qris_merchant_id            text NOT NULL,
    -- Nullable: NULL is an honest statement that no terminal id was recorded for this charge.
    qris_terminal_id            text,
    -- Our order_id. UNIQUE is load-bearing rather than hygiene: it is what lets webhook handling
    -- and reconciliation find exactly one row per notification.
    qris_partner_reference_no   text NOT NULL UNIQUE,
    qris_content                text,
    qris_reference_no           text,
    status                      text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','paid','expired','failed','refunded')),
    expires_at                  timestamptz NOT NULL,
    paid_at                     timestamptz,
    webhook_received_at         timestamptz,
    webhook_signature_verified  boolean NOT NULL DEFAULT false,
    raw_webhook_payload         jsonb COMPRESSION lz4,  -- as audit_log's state columns
    created_at                  timestamptz NOT NULL DEFAULT now(),
    payment_channel             text NOT NULL DEFAULT 'qris'
        CHECK (payment_channel IN ('qris', 'cash')),
    settled_by_actor            text
        CHECK (settled_by_actor IS NULL OR settled_by_actor IN ('customer','tenant_staff','system','agen_elektronik')),
    last_reconciled_at          timestamptz,
    -- What a settled payment owes back. NOT status = 'refunded': that says the money went back,
    -- and nothing in this codebase moves money back, so it would turn a visible
    -- debt into a false settlement. Not a 'refund_due' status either: a payment awaiting refund
    -- is still, factually, paid, and a non-terminal status would put it back in front of the
    -- reconciliation scan's `status = 'pending'` filter — the only thing between it and
    -- re-charging a settled order.
    --
    -- An amount that accumulates, because seats are cancelled one at a time: a partial
    -- cancellation of a 3-seat booking owes back one seat's price_amount (the price that seat
    -- was sold at, pinned at hold time), not the group total.
    refund_due_amount           numeric(14,2) NOT NULL DEFAULT 0,
    refund_due_at               timestamptz,
    refund_reason               text,
    trigger_point               text NOT NULL,
    -- When Midtrans first reported capture + fraud_status=challenge. A timestamp, not a
    -- fraud_status column: a copy of 'challenge'/'accept'/'deny' would invite deciding
    -- settlement from our copy of a state Midtrans owns, which is how a stale row becomes a
    -- confirmed seat. It is also what stops the reconciliation scan expiring a row whose money is
    -- already captured. Nothing clears it: 'accept' moves the row to paid and 'deny' to failed.
    fraud_review_at             timestamptz,
    -- Cash is settled at the moment it is taken; a pending cash payment would sit in a state
    -- nothing ever advances.
    CONSTRAINT cash_payments_are_never_pending CHECK (
        payment_channel <> 'cash' OR status <> 'pending'
    ),
    -- Whether a human or the engine confirmed a payment is what an operator-liability
    -- carve-out for electronic agents (PP 71/2019) turns on in a dispute.
    CONSTRAINT paid_payments_have_a_settling_actor CHECK (
        status <> 'paid' OR settled_by_actor IS NOT NULL
    ),
    CONSTRAINT refund_due_amount_within_payment
        CHECK (refund_due_amount >= 0 AND refund_due_amount <= amount),
    -- The timestamp and the amount move together or not at all: this catches an accumulation
    -- path that forgot to stamp the row, which would make the obligation invisible again.
    CONSTRAINT refund_due_at_set_with_amount
        CHECK ((refund_due_amount = 0 AND refund_due_at IS NULL)
            OR (refund_due_amount > 0 AND refund_due_at IS NOT NULL)),
    -- Beside payment_channel rather than folded into it: qris/online, qris/counter and
    -- cash/counter are all real.
    CONSTRAINT trigger_point_is_known
        CHECK (trigger_point IN ('online', 'counter'))
);

COMMENT ON COLUMN payment.qris_merchant_id IS
    'Our Midtrans merchant id, from configuration — an aggregator integration has no '
    'per-tenant acquirer merchant id.';
COMMENT ON COLUMN payment.qris_terminal_id IS
    'BI-SNAP terminalId for this charge. NULL until the charge request sends one — '
    'SnapMidtransGateway does not today — and NULL for every legacy-API row, which had no '
    'terminal to name. Which device started the charge is trigger_point, not this column.';
COMMENT ON COLUMN payment.payment_channel IS
    'qris = Midtrans charge with a webhook to await; cash = staff marked it received at the '
    'counter. Keeps cash sales out of the reconciliation scan, where they would otherwise '
    'look like QRIS charges that never settled.';
COMMENT ON COLUMN payment.settled_by_actor IS
    'Who moved this payment to paid: agen_elektronik for an automated webhook settlement '
    '(the PP 71/2019 term — an engine confirming without human review), '
    'tenant_staff for a manual cash confirmation. Same vocabulary as audit_log.actor_type.';
COMMENT ON COLUMN payment.last_reconciled_at IS
    'When the reconciliation job last asked Midtrans about this payment, so a repeatedly '
    'unresolvable charge is visible as such rather than being re-queried forever silently.';
COMMENT ON COLUMN payment.trigger_point IS
    'Which trigger point created this charge: online (the customer''s own device) or counter '
    '(staff acting for a walk-in).';
COMMENT ON COLUMN payment.fraud_review_at IS
    'When Midtrans first reported capture+fraud_status=challenge for this order. First write wins '
    'so the age stays honest across re-queries; never cleared, so a settled row keeps the history.';

-- The reconciliation job's scan for missed webhooks. Partial on 'pending' because that is the
-- only population it looks at, and a partial index stays small as settled payments accumulate.
CREATE INDEX idx_payment_pending_reconciliation
    ON payment (tenant_id, expires_at)
    WHERE status = 'pending';
-- Every render of a reservation page shows its payment.
CREATE INDEX idx_payment_reservation_group ON payment (reservation_group_id);
-- "What is still owed", oldest debt first.
CREATE INDEX idx_payment_refund_outstanding
    ON payment (tenant_id, refund_due_at)
    WHERE refund_due_amount > 0;
-- "What is a human still owed a decision on", longest-waiting first.
CREATE INDEX idx_payment_fraud_review_open
    ON payment (tenant_id, fraud_review_at)
    WHERE fraud_review_at IS NOT NULL AND status = 'pending';
-- One paid cash payment per order: the database's half of the double-cash-settlement fix.
-- PaymentService.settleCash locks the order's seats and refuses a second settlement, and maps a
-- violation of this index, by name, to ALREADY_PAID; the index still holds if a future path
-- inserts a cash row without the lock. CASH ONLY, ON PURPOSE: a QRIS payment that lands after
-- cash is real money Midtrans already holds, and the webhook must be able to record it (to be
-- refunded), not have it refused here.
CREATE UNIQUE INDEX idx_payment_one_cash_per_order
    ON payment (tenant_id, reservation_group_id)
    WHERE payment_channel = 'cash' AND status = 'paid';

CREATE TABLE tenant_billing_period (
    tenant_billing_period_id  uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id                 uuid NOT NULL REFERENCES tenant(tenant_id),
    period_start              date NOT NULL,
    period_end                date NOT NULL,
    plan_tier                 text NOT NULL,
    amount_due                numeric(12,2) NOT NULL,
    currency                  text NOT NULL DEFAULT 'IDR',
    status                    text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','invoiced','paid','overdue')),
    invoiced_at                timestamptz,
    paid_at                    timestamptz,
    payment_reference          text,
    UNIQUE (tenant_id, period_start, period_end)
);

-- ===========================================================================================
-- Consent, PII and the audit chain
-- ===========================================================================================

CREATE TABLE consent_record (
    consent_id            uuid PRIMARY KEY DEFAULT uuidv7(),
    tenant_id             uuid NOT NULL REFERENCES tenant(tenant_id),
    customer_id           uuid NOT NULL REFERENCES customer(customer_id),
    purpose               text NOT NULL,
    legal_basis           text NOT NULL CHECK (legal_basis IN ('contract','consent','legal_obligation','vital_interest','public_task')),
    consent_given_at      timestamptz,
    consent_withdrawn_at  timestamptz,
    policy_text_hash      text NOT NULL,
    ip_address            inet,
    created_at            timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE customer_pii (
    customer_id      uuid PRIMARY KEY REFERENCES customer(customer_id),
    tenant_id        uuid NOT NULL REFERENCES tenant(tenant_id),
    full_address     text,
    id_document_ref  text,
    deleted_at       timestamptz
);

-- Append-only for app_runtime (see the grants below), and per tenant a hash chain.
CREATE TABLE audit_log (
    -- ALWAYS, not BY DEFAULT: a hand-picked audit_id would put a row out of chain order.
    audit_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenant(tenant_id),
    entity_type     text NOT NULL,
    entity_id       uuid NOT NULL,
    action          text NOT NULL,
    actor_type      text NOT NULL CHECK (actor_type IN ('customer','tenant_staff','system','agen_elektronik')),
    actor_id        uuid,
    occurred_at     timestamptz NOT NULL DEFAULT clock_timestamp(),
    -- lz4 over the default pglz: a whole row per write, read back rarely, so cheap compression
    -- on the write path is the trade. Needs a server built --with-lz4 (PGDG and the image are).
    previous_state  jsonb COMPRESSION lz4,
    new_state       jsonb COMPRESSION lz4,
    request_id      uuid,
    row_hash        text NOT NULL
        -- a hash chain, not a per-row hash — see the trigger function below for why
        -- that distinction matters and how it's computed
);
CREATE INDEX idx_audit_entity ON audit_log(tenant_id, entity_type, entity_id, occurred_at);

-- A hash of a row's own content proves that row wasn't altered — it does NOT prove nothing was
-- deleted or reordered, since nothing links one row to the next. Folding the previous row's hash
-- into each new row's hash input turns that into a chain: altering or removing any historical
-- row breaks verification for everything after it. Chained per tenant, not globally: a
-- dedicated-tenant database only holds its own rows, and a global chain would leak cross-tenant
-- ordering.
--
-- THE HASH INPUT: row_hash = sha256_hex(prior row_hash || sha256_hex(audit_content(row))), with
-- '' as the prior of a tenant's first row. Two consequences, both deliberate:
--   - The chain links content HASHES, not content. Proving one order's rows to a court means
--     walking from them to an anchor (audit_anchor below) through every row of the tenant in
--     between, which are other customers' bookings; with this shape those rows are handed over
--     as their content hash alone, and the walk still verifies. Hashing the content straight
--     into the chain would have made disclosing them the price of proving anything.
--   - audit_content covers every column but audit_id (the chain fixes order) and row_hash:
--     who acted and when are what Pasal 6's attributability rests on, so they are hashed too.
--     occurred_at is hashed as UTC text, so the walk does not depend on the session's TimeZone.
-- The input is fixed from the first real booking on: changing it after that makes every earlier
-- row unverifiable, the failure the chain exists to detect, caused by us. It changed once, on
-- 2026-11-06, before any real booking.
--
-- Gaps in audit_id are normal and prove nothing: an identity value is spent by any transaction
-- that rolls back after an audited write (a refused hold, a 40P01 retry). Deletion is detected by
-- the prior-hash link, never by counting ids.
--
-- actor_type and actor_id come from GUCs Db sets per transaction (app.current_actor_type and
-- app.current_actor_id), never from NEW.created_by_actor: the party who created a reservation
-- is not the sweep that completes it, the webhook that confirms it, the usher who admits it or
-- the staff member who cancels it over the phone. A write that sets no GUC — a sweep,
-- provisioning, a webhook, psql — audits as 'system', which is the true answer for all of them.
-- created_by_actor is still in new_state, so "a self-service booking, completed by the
-- platform" stays recoverable. The NULLIFs: an unauthenticated request audits with actor_id NULL
-- rather than failing the write.
--
-- THE ADVISORY LOCK. Under READ COMMITTED an uncommitted audit row is invisible, so two
-- transactions of one tenant appending at once would both link to the same predecessor and the
-- walk would report the second as tampered with — permanently, since audit_log is append-only.
-- So each appender takes a transaction-scoped lock per tenant before reading; the second waits
-- for the first to commit, and its audit_id comes from the sequence after that, so audit_id
-- order is chain order (AuditChainConcurrencyTest). Two-key form, namespaced, so no other
-- advisory lock collides; a hashtext collision between two tenants only makes them take turns.
--
-- THE RULE IT ADDS, for callers: take every row lock a transaction needs BEFORE its first
-- audited write. A transaction that holds this lock and then waits on a row lock can deadlock
-- against one that holds the row and waits here. Hence findByGroupForUpdate in the confirm paths
-- and SKIP LOCKED in the sweeps. The one path that still waits after it (a hold's later inserts,
-- on the EXCLUDE index and on foreign keys) re-runs on 40P01 (BookingService.holdTransaction).
-- The text a row's content hash is taken over, and what a verifier is handed for a row it may
-- see. jsonb_build_object, so the key order is jsonb's own (fixed) and not this list's. The walk
-- calls it on stored rows, so it must only ever read `a`.
CREATE FUNCTION audit_content(a audit_log) RETURNS text AS $$
    SELECT jsonb_build_object(
        'tenant_id', a.tenant_id, 'entity_type', a.entity_type, 'entity_id', a.entity_id,
        'action', a.action, 'actor_type', a.actor_type, 'actor_id', a.actor_id,
        'occurred_at', to_char(a.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'previous_state', a.previous_state, 'new_state', a.new_state, 'request_id', a.request_id
    )::text
$$ LANGUAGE sql STABLE;

-- The one writer of audit_log, shared by both triggers so the lock, the predecessor read and the
-- hash cannot drift apart. occurred_at is taken after the lock, so time order is chain order.
CREATE FUNCTION audit_append(a audit_log) RETURNS void AS $$
DECLARE
    prior_hash text;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('audit_log'), hashtext(a.tenant_id::text));

    SELECT row_hash INTO prior_hash
    FROM audit_log
    WHERE tenant_id = a.tenant_id
    ORDER BY audit_id DESC
    LIMIT 1;

    a.actor_type := COALESCE(NULLIF(current_setting('app.current_actor_type', true), ''), 'system');
    a.actor_id := NULLIF(current_setting('app.current_actor_id', true), '')::uuid;
    a.occurred_at := clock_timestamp();
    a.row_hash := encode(digest(
        COALESCE(prior_hash, '') || encode(digest(audit_content(a), 'sha256'), 'hex'),
        'sha256'), 'hex');

    INSERT INTO audit_log(tenant_id, entity_type, entity_id, action, actor_type, actor_id,
                          occurred_at, previous_state, new_state, request_id, row_hash)
    VALUES (a.tenant_id, a.entity_type, a.entity_id, a.action, a.actor_type, a.actor_id,
            a.occurred_at, a.previous_state, a.new_state, a.request_id, a.row_hash);
END;
$$ LANGUAGE plpgsql;

CREATE FUNCTION reservation_audit() RETURNS trigger AS $$
DECLARE
    a audit_log;
BEGIN
    a.tenant_id := COALESCE(NEW.tenant_id, OLD.tenant_id);
    a.entity_type := 'reservation';
    a.entity_id := COALESCE(NEW.reservation_id, OLD.reservation_id);
    a.action := TG_OP;
    a.previous_state := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END;
    a.new_state := CASE WHEN TG_OP <> 'DELETE' THEN to_jsonb(NEW) END;
    PERFORM audit_append(a);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER reservation_audit
AFTER INSERT OR UPDATE OR DELETE ON reservation
FOR EACH ROW EXECUTE FUNCTION reservation_audit();

-- Privilege changes on app_user go into the same per-tenant chain: /admin/staff hands the grant
-- to tenants, so the grant has to leave a record — attributability applies to who was
-- given power at least as much as to who took a seat. A trigger rather than a service-layer
-- INSERT, because the break-glass psql UPDATE is precisely what needs recording.
--
-- THREE SCOPING RULES (the WHEN clauses on the triggers, created after the seed below), each
-- load-bearing:
--
--   (a) Only when a privilege column actually changes. app_user is written on EVERY login
--       (bindSubjectOnFirstLogin, touchLastLogin) with NO tenant context, and audit_log's
--       policy refuses an INSERT with app.current_tenant_id unset. Firing on every UPDATE would
--       break sign-in for everybody.
--   (b) Only for rows that have a tenant: platform_admin rows carry none, so there is no chain
--       for one to belong to.
--   (c) INSERT only for tenant_staff: AppUserAugmentor auto-provisions a customer identity on
--       first sign-in, also with no tenant context.
--
-- The consequence for callers: anything that changes role, can_manage_staff or is_active, or
-- inserts a tenant_staff row, MUST run inside Db.inTenantTransaction so the GUCs are set.
-- AppUserRepository's javadoc repeats it at each method.
CREATE FUNCTION app_user_privilege_audit() RETURNS trigger AS $$
DECLARE
    a audit_log;
BEGIN
    -- audit_append takes reservation_audit's lock: the two share one chain per tenant.
    a.tenant_id := NEW.tenant_id;
    a.entity_type := 'app_user';
    a.entity_id := NEW.app_user_id;
    a.action := TG_OP;
    a.previous_state := CASE WHEN TG_OP <> 'INSERT' THEN to_jsonb(OLD) END;
    a.new_state := to_jsonb(NEW);
    PERFORM audit_append(a);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- RFC 3161 timestamps of each tenant's chain head, taken hourly by AuditAnchorService. The chain
-- alone proves integrity only against someone who cannot recompute all of it, and the operator
-- can. A reply signed by a timestamp authority outside the operator pins every row up to audit_id
-- as it stood at the reply's time:
--   openssl ts -verify -digest <that row's row_hash> -in reply.tsr -CAfile <the TSA's root>
-- Only a hash leaves the server, which is no personal data. Append-only for app_runtime, as
-- audit_log: deleting an anchor is destroying evidence.
CREATE TABLE audit_anchor (
    tenant_id    uuid NOT NULL REFERENCES tenant(tenant_id),
    audit_id     bigint NOT NULL REFERENCES audit_log(audit_id),
    tsa_reply    bytea NOT NULL,   -- the whole TimeStampResp, as `openssl ts -verify -in` reads it
    anchored_at  timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (tenant_id, audit_id)
);

-- ===========================================================================================
-- Row-level security
-- ===========================================================================================
-- The same ENABLE/FORCE/POLICY block on every tenant-scoped table. Absent, deliberately:
-- tenant, platform_agreement_version and app_user (see their comments).
--
-- The NULLIF is load-bearing, not defensive noise. current_setting(name, true) returns NULL
-- only while the GUC has NEVER been set on the connection; once anything has set it — even
-- with is_local => true, even after that transaction has ended — it reads back as the empty
-- string '' for the rest of the session. '' :: uuid then raises 22P02 instead of yielding
-- NULL, so the policy ERRORS rather than simply matching no rows.
--
-- Measured on PostgreSQL 18.4: unset -> NULL; after one SET LOCAL and a COMMIT -> ''.
-- Since Agroal pools and reuses connections, and every tenant-scoped transaction sets this
-- GUC, '' is the steady state of a warm pool — so without NULLIF any query that reaches an
-- RLS table without establishing tenant context fails loudly at the policy instead of
-- returning zero rows. A superuser or BYPASSRLS role (Dev Services' Testcontainers role is
-- created SUPERUSER) never evaluates the policy at all, which is how its absence once went
-- unnoticed.
--
-- No separate WITH CHECK: for INSERT, Postgres reuses USING, which is NULL (not true) with the
-- GUC unset, so a write with no tenant context is refused too.
ALTER TABLE site ENABLE ROW LEVEL SECURITY;
ALTER TABLE site FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON site
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE resource_pool ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_pool FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON resource_pool
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE resource_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_unit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON resource_unit
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE film ENABLE ROW LEVEL SECURITY;
ALTER TABLE film FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON film
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE resource_pool_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_pool_session FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON resource_pool_session
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE terms_version ENABLE ROW LEVEL SECURITY;
ALTER TABLE terms_version FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON terms_version
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE tenant_agreement_acceptance ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_agreement_acceptance FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_agreement_acceptance
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE policy_document ENABLE ROW LEVEL SECURITY;
ALTER TABLE policy_document FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON policy_document
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE rate_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE rate_rule FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rate_rule
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE public_holiday ENABLE ROW LEVEL SECURITY;
ALTER TABLE public_holiday FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON public_holiday
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE reservation_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservation_group FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reservation_group
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE reservation FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON reservation
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE booking_draft ENABLE ROW LEVEL SECURITY;
ALTER TABLE booking_draft FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON booking_draft
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE resource_pool_watch ENABLE ROW LEVEL SECURITY;
ALTER TABLE resource_pool_watch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON resource_pool_watch
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE order_watch ENABLE ROW LEVEL SECURITY;
ALTER TABLE order_watch FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON order_watch
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE tenant_billing_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_billing_period FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_billing_period
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE consent_record ENABLE ROW LEVEL SECURITY;
ALTER TABLE consent_record FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON consent_record
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE customer_pii ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer_pii FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_pii
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

ALTER TABLE audit_anchor ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_anchor FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_anchor
    USING (tenant_id = NULLIF(current_setting('app.current_tenant_id', true), '')::uuid);

-- ===========================================================================================
-- The non-owning application role
-- ===========================================================================================
-- Two things must both be true for pool-tier RLS to actually protect data: (1) a non-owning,
-- non-BYPASSRLS role, and (2) SET LOCAL tenant context per transaction. This is (1).
-- FORCE ROW LEVEL SECURITY already subjects even the table owner to the policies; what this role
-- adds is no BYPASSRLS, no ability to ALTER the policies away, and — the part that matters most
-- for an audit trail — no UPDATE/DELETE/TRUNCATE on audit_log at all, so the chain is not merely
-- detectably-alterable but unalterable through the application's own credentials.
--
-- Created NOLOGIN with no password on purpose: a password in a committed migration is not a
-- secret. The operator grants login separately (ALTER ROLE app_runtime LOGIN PASSWORD '...')
-- and points the runtime datasource at it (DATABASE_USER), while migrations keep running as the
-- owner (DATABASE_MIGRATION_USER).
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app_runtime') THEN
        CREATE ROLE app_runtime NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    ELSE
        -- Idempotent for a re-provisioned database in a cluster where the role already
        -- exists (roles are cluster-wide, databases are not).
        ALTER ROLE app_runtime NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
EXCEPTION
    WHEN insufficient_privilege THEN
        -- Managed Postgres offerings differ on whether the migration user may create
        -- roles. Warn loudly rather than failing the whole migration: the schema and
        -- RLS policies are still correct without this role, they're just being used by
        -- a more privileged account than ideal.
        RAISE WARNING 'Could not create/alter role app_runtime (insufficient privilege). '
            'RLS still applies via FORCE ROW LEVEL SECURITY, but the non-owning-role '
            'hardening is NOT active. Create the role manually.';
END
$$;

DO $$
BEGIN
    GRANT USAGE ON SCHEMA public TO app_runtime;
    GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO app_runtime;
    GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO app_runtime;

    -- The audit_log exception, and the whole point of having a separate role: append
    -- and read only. An application compromised at the SQL level still cannot rewrite
    -- or truncate history through this credential.
    REVOKE UPDATE, DELETE, TRUNCATE ON audit_log, audit_anchor FROM app_runtime;

    -- Tables created by later migrations land with the same grants, rather than needing
    -- this block repeated in each.
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public '
        'GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime';
    EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public '
        'GRANT USAGE, SELECT ON SEQUENCES TO app_runtime';
EXCEPTION
    WHEN undefined_object OR insufficient_privilege THEN
        RAISE WARNING 'Could not grant privileges to app_runtime; see the preceding warning.';
END
$$;

-- ===========================================================================================
-- Demo seed
-- ===========================================================================================
-- One tenant with a branch in two cities, one hall with a seat grid, a film with one on-sale
-- showing, a flat rate rule, a cancellation policy, the fixed public holidays, the draft
-- platform agreement, and one identity per role — enough to click through end to end on a
-- fresh clone without a separate seeding step (README).
--
-- Every insert below except `tenant` and platform_agreement_version targets a table under FORCE
-- ROW LEVEL SECURITY, so the tenant context is set explicitly. Whoever runs Flyway may be a
-- superuser or hold BYPASSRLS and get away without it; that is a property of the role, not of
-- this file. Granting the migrating or runtime role BYPASSRLS must never be the fix: the same
-- role serves runtime queries, so it would silently disable tenant isolation everywhere.
-- is_local => true scopes it to Flyway's own transaction, the same SET LOCAL semantics the
-- application uses.
SELECT set_config('app.current_tenant_id', '00000000-0000-0000-0000-000000000001', true);

INSERT INTO tenant (tenant_id, slug, display_name, vertical, complaint_phone)
VALUES ('00000000-0000-0000-0000-000000000001', 'demo-cinema', 'Demo Cinema', 'live_entertainment', '+62-21-5550100');

-- Rekaan (a made-up town, at a real point ~350 km from Jakarta) is the second branch: a "pick
-- your city" control with one option demonstrates nothing, and the distance is large enough that
-- a wrong nearest-city answer is obvious on sight. It has no halls — the branch page says so,
-- which is the truthful rendering of a branch nobody has configured yet.
INSERT INTO site (site_id, tenant_id, name, city, province, address, timezone,
                  latitude, longitude, slug)
VALUES ('00000000-0000-0000-0000-000000000004', '00000000-0000-0000-0000-000000000001',
        'Demo Cinema Menteng', 'Jakarta Pusat', 'DKI Jakarta',
        'Jl. Merdeka No. 1, Jakarta', 'Asia/Jakarta',
        -6.195700, 106.836700, 'demo-cinema-menteng'),
       ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
        'Demo Cinema Rekaan', 'Rekaan', 'Jawa Tengah',
        'Jl. Pemuda No. 2, Rekaan', 'Asia/Jakarta',
        -7.086600, 110.914400, 'demo-cinema-rekaan');

INSERT INTO resource_pool (resource_pool_id, tenant_id, name, address, timezone, block_claim_mode, site_id)
VALUES ('00000000-0000-0000-0000-000000000002', '00000000-0000-0000-0000-000000000001',
        'Studio 1', 'Jl. Merdeka No. 1, Jakarta', 'Asia/Jakarta', 'contiguous_n',
        '00000000-0000-0000-0000-000000000004');

-- 5 rows (A-E) x 8 seats
INSERT INTO resource_unit (tenant_id, resource_pool_id, label, group_key, sequence_no)
SELECT '00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
       chr(64 + row_no) || col_no, chr(64 + row_no), col_no
FROM generate_series(1, 5) AS row_no, generate_series(1, 8) AS col_no;

INSERT INTO film (film_id, tenant_id, title, duration_minutes, classification, synopsis, release_date, slug)
VALUES ('00000000-0000-0000-0000-000000000005', '00000000-0000-0000-0000-000000000001',
        'Demo Film', 118, '13+',
        'A placeholder title so the film-first browse path has something to show on a fresh clone.',
        current_date - interval '7 days', 'demo-film');

INSERT INTO rate_rule (tenant_id, resource_pool_id, rule_definition, effective_from)
VALUES ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002',
        '{"type":"flat","amount":50000}'::jsonb, now() - interval '1 day');

INSERT INTO policy_document (tenant_id, doc_type, version, content, effective_from)
VALUES ('00000000-0000-0000-0000-000000000001', 'cancellation_policy', 1,
        'Reservations may be cancelled free of charge up to 1 hour before showtime.', now() - interval '1 day');

-- One showing, reachable both venue-first and film-first.
INSERT INTO resource_pool_session (resource_pool_session_id, tenant_id, resource_pool_id, label,
                                    starts_at, ends_at, sales_cutoff_at, status, film_id)
VALUES ('00000000-0000-0000-0000-000000000003', '00000000-0000-0000-0000-000000000001',
        '00000000-0000-0000-0000-000000000002', 'Demo Film — Tonight 19:00',
        now() + interval '1 day', now() + interval '1 day 2 hours',
        now() + interval '1 day' - interval '15 minutes', 'on_sale',
        '00000000-0000-0000-0000-000000000005');

-- Only the five holidays that are actually FIXED. The moveable ones (Idul Fitri, Idul Adha,
-- Nyepi, Waisak, Imlek, Isra Mikraj, Maulid, Wafat and Kenaikan Isa Almasih) and every cuti
-- bersama are announced per year by decree, so a literal list of them here would be wrong the
-- year after it ships — and a wrong date prices an ordinary Tuesday at the top band where nobody
-- notices, while a missing one is noticed at the counter. Generated for the current year and the
-- next, so a clone made in 2029 seeds 2029 and 2030.
INSERT INTO public_holiday (tenant_id, holiday_date, name)
SELECT '00000000-0000-0000-0000-000000000001', make_date(y, m, d), n
FROM generate_series(
        extract(year FROM current_date)::int,
        extract(year FROM current_date)::int + 1) AS y,
     (VALUES (1, 1,  'Tahun Baru Masehi'),
             (5, 1,  'Hari Buruh Internasional'),
             (6, 1,  'Hari Lahir Pancasila'),
             (8, 17, 'Hari Kemerdekaan Republik Indonesia'),
             (12, 25, 'Hari Raya Natal')) AS fixed(m, d, n);

-- The agreement text is the substance of the liability boundary above, and still needs real legal review
-- before a real tenant signs it, which the text itself says so the draft status travels with
-- the document. content_hash is a generated column, so the two cannot disagree. Dollar-quoted,
-- because adjacent E'' literals do not concatenate and dollar quoting carries real newlines
-- without escaping anything.
INSERT INTO platform_agreement_version (version_label, content, effective_from)
SELECT 'v1-draft',
       agreement.content,
       now() - interval '1 day'
FROM (SELECT $agreement$PLATFORM-TENANT AGREEMENT (DRAFT — PENDING LEGAL REVIEW)

1. Inventory exclusivity and the double-booking guarantee.
The Platform guarantees that inventory managed exclusively through this system cannot be
double-booked: a PostgreSQL exclusion constraint makes two overlapping claims on the same
resource unit structurally impossible, not merely checked for.

2. Tenant disclosure of other sales channels.
The Tenant is responsible for disclosing any other channel through which the same
inventory is sold — a walk-in paper ledger, a second booking system, another SaaS
product — and for either keeping that channel synchronized with this system or not
running it concurrently against the same resource_pool.

3. Limitation of the guarantee.
The guarantee in clause 1 extends only to inventory this system can see. The Platform is
not liable for booking conflicts arising from a sales channel that was not disclosed
under clause 2, because no technical mechanism can prevent conflicts in inventory the
system has no visibility into. This is a narrow allocation of a specific risk, not a
general disclaimer of the guarantee in clause 1.

4. Status of this document.
DRAFT. Requires review by Indonesian counsel before execution, alongside the open
question of who is the registrable Penyelenggara Sistem Elektronik in a multi-tenant
white-label structure.
$agreement$::text AS content) AS agreement;

-- The demo tenant's walk-in placeholder. Real tenants get theirs from TenantProvisioningService.
INSERT INTO customer (tenant_id, full_name, is_walk_in_placeholder)
VALUES ('00000000-0000-0000-0000-000000000001', 'Walk-in Customer', true);

-- The demo tenant has accepted the draft agreement, so it reads as a fully provisioned tenant
-- rather than one mid-onboarding.
INSERT INTO tenant_agreement_acceptance (tenant_id, platform_agreement_version_id,
                                          accepted_by_name, accepted_by_email)
SELECT '00000000-0000-0000-0000-000000000001', platform_agreement_version_id,
       'Demo Cinema Owner', 'owner@demo-cinema.example'
FROM platform_agreement_version WHERE version_label = 'v1-draft';

-- One identity per role, at RFC 2606 @*.example addresses: the authorization tests exercise all
-- three roles without committing anyone's real email, and nobody can sign in as one. The staff
-- identity is the tenant's first manager, as TenantProvisioningService makes the staff email
-- named at onboarding. A real platform admin is bootstrapped from configuration at startup
-- (booking.bootstrap.platform-admin-email), not seeded here.
INSERT INTO app_user (email, role, tenant_id, can_manage_staff)
VALUES ('staff@demo-cinema.example', 'tenant_staff', '00000000-0000-0000-0000-000000000001', true),
       ('customer@demo-cinema.example', 'customer', '00000000-0000-0000-0000-000000000001', false),
       ('admin@platform.example', 'platform_admin', NULL, false);

-- Back to the fail-closed state: '' is what an unset GUC reads as, and the NULLIF in every
-- policy turns it into "match nothing" rather than a 22P02.
SELECT set_config('app.current_tenant_id', '', true);

-- After the seed, so the seed's manager grant is attributed by being in this file rather than
-- by an audit row with no actor behind it.
CREATE TRIGGER app_user_privilege_audit_update
    AFTER UPDATE ON app_user
    FOR EACH ROW
    WHEN (NEW.tenant_id IS NOT NULL
          AND (OLD.role IS DISTINCT FROM NEW.role
               OR OLD.can_manage_staff IS DISTINCT FROM NEW.can_manage_staff
               OR OLD.is_active IS DISTINCT FROM NEW.is_active))
    EXECUTE FUNCTION app_user_privilege_audit();

CREATE TRIGGER app_user_privilege_audit_insert
    AFTER INSERT ON app_user
    FOR EACH ROW
    WHEN (NEW.tenant_id IS NOT NULL AND NEW.role = 'tenant_staff')
    EXECUTE FUNCTION app_user_privilege_audit();
