# movie-ticket

A multi-tenant seat-booking web application for cinemas: Quarkus on the JVM, plain JDBC on
PostgreSQL, and server-rendered Qute pages with htmx. Two customers can never hold the same seat,
because a PostgreSQL `EXCLUDE` constraint turns the second claim into a write-time conflict, and
each tenant's rows are isolated from every other tenant's by row-level security. An optional
booking assistant (`/chat`) reads showtimes and proposes seats, and it never books: only the
customer's own click on the hold form does.

Licensed under the Apache License 2.0. Each vendored file keeps its own notice: the Qwen
tokenizer (`src/main/resources/qwen3.8-27b.tiktoken.gz`) is Apache-2.0, GeographicLib
(`src/main/java/net/sf/geographiclib/`, apart from the local `UTMUPS.kt`) is MIT, and htmx
(`src/main/resources/META-INF/resources/js/htmx.min.js`, 2.0.10) is 0BSD.

Every platform claim below carries the URL it came from. Where those pages do not say something,
this file says so rather than guessing.

Running this for cinemas, or evaluating someone who does? See [Questions an operator should answer
from the code](#questions-an-operator-should-answer-from-the-code), at the end.

## Documents

- [`docs/plan.md`](docs/plan.md): the build specification: data model, multi-tenancy, stack,
  concurrency analysis, Indonesian legal notes and a phased build plan.
- [`docs/plan-addendum-market-parity.md`](docs/plan-addendum-market-parity.md): what a multi-branch
  chain needs beyond the core, and what was built for it.
- [`docs/pse-oss-registration.md`](docs/pse-oss-registration.md): where and in what order an
  operator registers as an electronic-system operator (PSE).

All three were written for the original deployment. Names are pseudonyms and private references
are removed; nothing else is changed.

## On a development PC

You need:

- **JDK 25.** `.mvn/jvm.config` passes `--sun-misc-unsafe-memory-access=allow`, which no JDK
  before 23 accepts, so an older JDK fails before Maven starts.
- **Docker or podman**, for the tests and for dev mode's database.
- **PostgreSQL 18**, only if you run the packaged app against a database of your own. 18 is the
  floor because the schema's keys default to `uuidv7()`, which PostgreSQL 18 added ("Add UUID
  version 7 generation function uuidv7()",
  https://www.postgresql.org/docs/release/18.0/). The schema also needs the `btree_gist` and
  `pgcrypto` extensions and a server built with ICU (the `case_insensitive` collation on
  `app_user.email`). The PGDG packages and the `postgres` image are built with ICU.

### Dev mode

```bash
./mvnw quarkus:dev
```

With no JDBC URL configured, Quarkus Dev Services starts a database container itself
(https://quarkus.io/guides/databases-dev-services): `postgres:18.6` here
(`application.properties`). Flyway applies the one migration, `V1__init_schema.sql`, schema and
demo seed together, and the app answers on http://localhost:8080. Dev mode does not read
`/etc/movie-ticket.env`, and if you export it into the shell, its database settings are still
ignored, because they are under the `%prod` profile.

### Tests

```bash
./mvnw test                  # JVM tests and the JS checks; the JS step downloads Node once
./mvnw test -DskipJsTests    # JVM tests only, no Node
```

Most test classes are `@QuarkusTest` and get PostgreSQL from a Testcontainers Dev Service, so the
suite needs a container runtime. Without Docker, podman's socket works; these three lines are all
it takes:

```bash
systemctl --user enable --now podman.socket
export DOCKER_HOST=unix:///run/user/$(id -u)/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true   # Ryuk warns loudly under a rootless socket
```

With Ryuk off, a container can outlive a killed run, so check `podman ps` after an interrupted
one. After pulling a change that deletes a migration or a resource, build with `clean`: Maven's
resources step copies into `target/classes` but never deletes from it.

### Against your own PostgreSQL 18

Make a migration owner that is **not** a superuser. `FORCE ROW LEVEL SECURITY` binds a table's
owner, but nothing binds a superuser, so a superuser owner would see every tenant's rows. The owner
needs `CREATEROLE` so that the migration can create `app_runtime`, the role the app runs as:

```sql
-- as postgres
CREATE ROLE booking LOGIN PASSWORD 'change-me-owner' CREATEROLE;
CREATE DATABASE booking OWNER booking;
```

`btree_gist` and `pgcrypto` are both "trusted in a default installation", so the database owner
can create them without being a superuser (https://www.postgresql.org/docs/18/contrib.html).
`docker compose up -d` gives the same thing in a container (`docker-compose.yml`:
`postgres:18.6`, database, user and password all `booking`), but that user is the image's
superuser, so it is fine for trying the app and wrong for checking isolation.

Build, then run with the env file loaded:

```bash
./mvnw package -DskipTests
set -a; . /etc/movie-ticket.env; set +a
java -jar target/quarkus-app/quarkus-run.jar
```

The first start migrates as `DATABASE_MIGRATION_USER`. The migration creates `app_runtime`
`NOLOGIN`, with no password, because a password in a committed migration is not a secret. Give it
one, then point the runtime datasource at it and restart:

```sql
-- as booking
ALTER ROLE app_runtime LOGIN PASSWORD 'change-me-runtime';
```

`app_runtime` has no `BYPASSRLS` and no `UPDATE`, `DELETE` or `TRUNCATE` on `audit_log` or
`audit_anchor`. If the owner may not create roles, the migration warns instead of failing, and the
app then runs with the owner's wider rights. RLS still holds, but neither table is append-only
any more.

### `/etc/movie-ticket.env`

These are the variables the app reads: every `${…}` in `application.properties`, plus three that
are read by other means (marked †). Quarkus also maps any property to an environment variable by
upper-casing it and replacing each non-alphanumeric character with `_`
(https://quarkus.io/guides/config-reference#environment-variables). That is how the † variables
and `QUARKUS_DATASOURCE_JDBC_MAX_SIZE` reach it.

The app boots with none of them set. **Off by default:** sign-in (OIDC), Web Push, Google
Wallet, payments, the buyer's email, and the assistant. Each turns on when its variables are set. Every value below
is a placeholder.

```bash
# --- Database (%prod only; dev mode and tests use Dev Services) ---
DATABASE_HOST=localhost                 # default localhost
DATABASE_PORT=5432                      # default 5432
DATABASE_NAME=booking                   # default booking
DATABASE_SSLMODE=disable                # pgJDBC sslmode; default disable. Use require off-host
DATABASE_URL=                           # a whole JDBC URL; replaces the four above when set
DATABASE_USER=app_runtime               # the runtime role; default booking
DATABASE_PASSWORD=change-me-runtime     # default change-me
DATABASE_MIGRATION_USER=booking         # Flyway's owner; defaults to DATABASE_USER
DATABASE_MIGRATION_PASSWORD=change-me-owner
QUARKUS_DATASOURCE_JDBC_MAX_SIZE=10     # † pool size per instance; default 10 (see the platform questions)

# --- HTTP and public URLs ---
PORT=8080                               # listen port; default 8080. Cloud Run sets it
PUBLIC_HTTPS_BASE_URL=https://tickets.example.com   # canonical, og:url, sitemap. Unset: none
                                        # of these, and /sitemap-showtimes.xml answers 503
BOOKING_CANONICAL_AUTHORITY=tickets.example.com     # † comma list of host[:port]; any other
                                        # Host gets 404. Unset: answers on any host
# The prod profile also sends each tenant's audit-chain head hourly to an RFC 3161 authority,
# http://timestamp.digicert.com (booking.audit-anchor.tsa-url), so outbound port 80 must be open.

# --- Sign-in: Google OIDC (off unless OIDC_ENABLED=true) ---
OIDC_ENABLED=true                       # web sign-in; redirect URI is https://<host>/callback
OIDC_AUTH_SERVER_URL=https://accounts.google.com    # the default
OIDC_CLIENT_ID=your-client-id.apps.googleusercontent.com
OIDC_CLIENT_SECRET=placeholder-secret
CUSTOMER_APP_OIDC_ENABLED=false         # accept Google ID tokens as bearer tokens on /api/app/*
PLATFORM_ADMIN_EMAIL=admin@demo-cinema.example      # made platform_admin at startup; unset: nobody

# --- Web Push through Firebase Cloud Messaging (off while VAPID_PUBLIC_KEY is unset) ---
VAPID_PUBLIC_KEY=placeholder-vapid-public-key       # the browser's subscribe key
FIREBASE_API_KEY=placeholder                        # the six are the browser's Firebase config
FIREBASE_AUTH_DOMAIN=demo-project.firebaseapp.com
FIREBASE_PROJECT_ID=demo-project
FIREBASE_STORAGE_BUCKET=demo-project.appspot.com
FIREBASE_MESSAGING_SENDER_ID=000000000000
FIREBASE_APP_ID=1:000000000000:web:placeholder
GOOGLE_APPLICATION_CREDENTIALS=/etc/movie-ticket/service-account.json  # † the server's
                                        # send. Wallet needs this to be a service-account key file

# --- Google Wallet (off while unset) ---
WALLET_ISSUER_ID=0000000000000000000    # adds "Add to Google Wallet" to the ticket page

# --- Payments: Midtrans (off while the credentials are unset) ---
# BI-SNAP is the build default. The keys are file PATHS, never PEM values in the environment.
MIDTRANS_SNAP_BASE_URL=https://merchants.sbx.midtrans.com   # the default is the sandbox
MIDTRANS_SNAP_CLIENT_ID=placeholder
MIDTRANS_SNAP_CLIENT_SECRET=placeholder
MIDTRANS_SNAP_PARTNER_ID=placeholder    # for a single-merchant account, the merchant id
MIDTRANS_SNAP_CHANNEL_ID=placeholder
MIDTRANS_SNAP_PRIVATE_KEY_PATH=/etc/movie-ticket/snap-private.pem       # yours, PKCS8
MIDTRANS_SNAP_MIDTRANS_PUBLIC_KEY_PATH=/etc/movie-ticket/snap-midtrans-public.pem
MIDTRANS_MERCHANT_ID=placeholder        # sent in every BI-SNAP charge
# The legacy Core API gateway, chosen only at build time with -Dmidtrans.api=legacy:
MIDTRANS_BASE_URL=https://api.sandbox.midtrans.com
MIDTRANS_SERVER_KEY=placeholder

# --- The buyer's email: showing cancelled, refund sent, refund refused (off while SMTP_HOST or MAIL_FROM is unset) ---
SMTP_HOST=smtp.example.com
SMTP_PORT=587                           # default 587 with STARTTLS. For 465: SMTP_TLS=true SMTP_START_TLS=DISABLED
SMTP_USERNAME=tiket@example.com
SMTP_PASSWORD=placeholder               # a password (an app password for Gmail), never an OAuth token
MAIL_FROM=Tickets <tiket@example.com>
SMTP_TLS=false                          # default false
SMTP_START_TLS=REQUIRED                 # default REQUIRED

# --- The booking assistant, /chat (off while the key is unset: /chat 404s, the link hides) ---
BOOKING_ASSISTANT_API_KEY=placeholder   # any OpenAI-compatible endpoint's key
BOOKING_ASSISTANT_BASE_URL=https://api.groq.com/openai/v1   # the default
BOOKING_ASSISTANT_MODEL=qwen/qwen3.8-27b                    # the default
BOOKING_ASSISTANT_HOST=Example Host, Inc. (Country)         # named in /chat's privacy notice;
                                        # it must say where the chat text actually goes
BOOKING_ASSISTANT_DAILY_MESSAGES=30     # per customer per day; default 30
```

`/chat` also needs sign-in: it is for signed-in customers and staff only.

**Mail never goes out on port 25 from a cloud host:** Google Cloud blocks outbound 25 (Cloud Run and
Compute Engine alike) and other clouds restrict it, so use 587 or 465.

## The questions every platform has to answer

The code depends on each of these, so each platform section below answers them in this order.

| # | Question | Why the code cares |
|---|---|---|
| 1 | Does CPU run between requests? | The push for a freed seat is sent after the response is written, so it never fails a committed booking. The `@Scheduled` jobs (the 30 s hold-expiry sweep, the 5-minute payment reconciliation, the daily billing run) run on no request at all |
| 2 | Can one instance stay warm? | Held seats expire only while an instance exists to run the sweep. Each job comes due when time passes with nothing happening, so something has to be awake at the deadline |
| 3 | How do instances × `jdbc.max-size` meet the database's connection limit? | Each instance holds a pool of up to 10 (`quarkus.datasource.jdbc.max-size`), and Flyway takes one more while an instance starts |
| 4 | Does the managed PostgreSQL allow `btree_gist` and `pgcrypto`, a non-superuser owner, and version 18? | The `EXCLUDE` constraint needs `btree_gist`. RLS binds only roles that are neither superuser nor `BYPASSRLS`. The schema needs 18, ICU and lz4 |
| 5 | Where do the secrets go? | In place of `/etc/movie-ticket.env`. Three settings name a **file**: `GOOGLE_APPLICATION_CREDENTIALS` and the two Midtrans key paths |
| 6 | Does TLS end at a proxy that always overwrites `X-Forwarded-*`? | See below |
| 7 | Is there an Indonesian region, for the platform and for its database? | Data residency, and latency to the customers |

**Why this tree trusts `X-Forwarded-*`.** `quarkus.http.proxy.proxy-address-forwarding=true`, because
on these platforms TLS ends at the platform's front end and the container sees only a cleartext
hop. Without the forwarded scheme, every absolute URL the app builds, the OIDC `redirect_uri`
first, would start with `http://` and the container's own address. That is safe only when the
container cannot be reached except through that front end, which sets the headers itself. On a
server that terminates TLS itself and is open to the internet, turn it off: there, the headers
are whatever the caller sent. Even behind a proxy, `X-Forwarded-For` is usually **appended to**,
not replaced, so its first entry is the caller's own claim. No code here reads the client address,
and nothing that decides anything should start to.

## Google Cloud Run

| # | Answer |
|---|---|
| 1 | Only with instance-based billing. "With request-based billing, CPU is only allocated during request processing"; instance-based billing "allocates CPU even outside of request processing" and needs at least 512 MiB. The flag is `--no-cpu-throttling` (https://docs.cloud.google.com/run/docs/configuring/billing-settings) |
| 2 | Yes, as a best-effort floor: `--min-instances=1`. Idle minimum instances are billed, and "Minimum instances can be restarted at any time" (https://docs.cloud.google.com/run/docs/configuring/min-instances) |
| 3 | `--max-instances` caps instances per revision. The default is 100, and the page says the cap "can be exceeded for a brief period" (https://docs.cloud.google.com/run/docs/configuring/max-instances). Cloud SQL's default `max_connections` follows memory: 25 for a tiny (~0.5 GB) instance, 50 for small (~1.7 GB), 100 from 3.75 GB (https://docs.cloud.google.com/sql/docs/postgres/flags). Keep `max-instances × (10 + 1)` well under `max_connections`, with room for that brief excess |
| 4 | PostgreSQL 18 is the default major version, at 18.6 (https://docs.cloud.google.com/sql/docs/db-versions). It has been generally available since 2025-11-20 (https://docs.cloud.google.com/sql/docs/postgres/release-notes). `btree_gist` (1.8 on 18) and `pgcrypto` are listed, and "extensions can only be created by users that are part of the `cloudsqlsuperuser` role" (https://docs.cloud.google.com/sql/docs/postgres/extensions). Nobody gets a superuser: "customers cannot create or have access to users with superuser attributes"; a built-in user has `CREATEROLE`, `CREATEDB` and `LOGIN` (https://docs.cloud.google.com/sql/docs/postgres/users). So a built-in user is a correct migration owner. Whether `cloudsqlsuperuser` carries `BYPASSRLS` is not stated in that page, and it does not matter at runtime, because the app runs as `app_runtime`. LZ4 is supported: "LZ4 TOAST compression for PostgreSQL versions 14 and later" (release notes, 2023-05-19). **ICU is unproven**: the extensions, flags, users and release-notes pages above do not mention it. Run `SELECT count(*) FROM pg_collation WHERE collprovider = 'i';` on the instance first. If it is 0, the first start fails at `CREATE COLLATION case_insensitive` |
| 5 | Secret Manager. `--update-secrets=ENV_VAR=SECRET:VERSION` sets an environment variable, and `--update-secrets=/path/file=SECRET:VERSION` mounts a file. The service identity needs `roles/secretmanager.secretAccessor` (https://docs.cloud.google.com/run/docs/configuring/services/secrets). So the three file settings take mounted files. Wallet signs passes only with a service-account **key file** (`GoogleWalletLinks` accepts nothing else), so for Wallet, mount a key and point `GOOGLE_APPLICATION_CREDENTIALS` at it. Whether the push send works with the service's own identity and no key file has not been tested |
| 6 | "TLS is terminated by Cloud Run", and "The container shouldn't implement any transport layer security directly" (https://docs.cloud.google.com/run/docs/container-contract). Which `X-Forwarded-*` headers Cloud Run sets, and whether it overwrites a caller's, is not stated in that page |
| 7 | Yes for both: Cloud Run lists `asia-southeast2` (Jakarta), at Tier 2 pricing (https://docs.cloud.google.com/run/docs/locations), and so does Cloud SQL (https://docs.cloud.google.com/sql/docs/postgres/locations) |

**Set both flags, and do not engineer around them.** Moving the jobs to Cloud Scheduler, or to a
timer started when a hold is made, does not remove the need. Each job comes due when time passes
with nothing happening, an in-memory timer dies when the instance scales to zero, and an instance
kept warm by a call every minute costs what `--min-instances=1` costs. The push still needs CPU
after the response either way.

**Reaching Cloud SQL.** "Unix sockets are not natively supported in Java"
(https://docs.cloud.google.com/sql/docs/postgres/connect-run), so the two routes are:

- **Private IP over Direct VPC egress**, with the default image: `DATABASE_HOST` is the
  instance's private address, `DATABASE_SSLMODE=require`, and the deploy adds
  `--network`, `--subnet` and `--vpc-egress=private-ranges-only`
  (https://docs.cloud.google.com/run/docs/configuring/vpc-direct-vpc). This is the route the
  commands below take.
- **The Cloud SQL Java connector**, through the `cloudsql-connector` Maven profile in `pom.xml`.
  Build with `-Dcloudsql.connector` (change the package line in `src/main/docker/Dockerfile.jvm`),
  then set `DATABASE_URL=jdbc:postgresql:///booking?cloudSqlInstance=PROJECT:REGION:INSTANCE&socketFactory=com.google.cloud.sql.postgres.SocketFactory`
  (the URL form is in connect-run). **No image has ever been built with this profile**, and no
  test exercises it.

**Instance size.** Nothing below creates the Cloud SQL instance, so no tier is set here. The
[cost page](https://dawud-tan.github.io/on-premise-vs-cloud.html) prices `db-g1-small` with 10 GB
of SSD: the smallest tier it treats as reasonable for one JVM. It is a floor for the comparison,
and the page says that no managed database of that size was ever load-tested.

**Deploy.** Build the image and push it to Artifact Registry
(https://docs.cloud.google.com/artifact-registry/docs/docker/pushing-and-pulling):

```bash
IMAGE=asia-southeast2-docker.pkg.dev/PROJECT_ID/REPOSITORY/movie-ticket:1
gcloud auth configure-docker asia-southeast2-docker.pkg.dev
docker build -f src/main/docker/Dockerfile.jvm -t "$IMAGE" .
docker push "$IMAGE"
```

Put the non-secret lines of `/etc/movie-ticket.env` in a file of their own. `--env-vars-file`
takes a `.env` file (https://docs.cloud.google.com/run/docs/configuring/services/environment-variables).
Put the passwords and keys in Secret Manager:

```bash
gcloud run deploy movie-ticket \
  --image="$IMAGE" \
  --region=asia-southeast2 \
  --no-cpu-throttling \
  --min-instances=1 \
  --max-instances=3 \
  --memory=1Gi \
  --network=NETWORK --subnet=SUBNET --vpc-egress=private-ranges-only \
  --env-vars-file=cloudrun.env \
  --update-secrets=DATABASE_PASSWORD=db-owner-password:latest \
  --update-secrets=DATABASE_MIGRATION_PASSWORD=db-owner-password:latest
```

`cloudrun.env` holds at least `DATABASE_HOST`, `DATABASE_SSLMODE=require`, `DATABASE_USER`,
`DATABASE_MIGRATION_USER` and `PUBLIC_HTTPS_BASE_URL`. Leave `PORT` out, because Cloud Run
injects it (container contract). Three instances × (10 + 1) is 33 connections, inside a small
instance's 50 with room for a brief excess. On the first deploy, set `DATABASE_USER` to the owner. Once the migration has
run, give `app_runtime` its password (the `ALTER ROLE` above, from any SQL client that reaches the
instance), and switch the runtime datasource to it:

```bash
gcloud run services update movie-ticket \
  --update-env-vars=DATABASE_USER=app_runtime \
  --update-secrets=DATABASE_PASSWORD=db-runtime-password:latest
```

## Amazon ECS Express Mode

Express Mode creates a Fargate service behind an Application Load Balancer, with an HTTPS
listener, an ACM certificate, target-tracking auto scaling and "a service with canary
deployment" (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-work.html).
Two of those cannot be changed: "deployment strategy can not be updated on Express Mode
services", and "load balancer configurations can not be updated" (same page). Several things
follow from that.

- **Two versions share the database during every deploy.** The new revision starts beside the
  old one, takes 5% of traffic, and takes 100% after a 3-minute bake. The old tasks stop only after
  another 3 minutes
  (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-update-full.html).
  The new tasks run Flyway as they start, so the old version serves on the migrated schema.
  AWS's advice is to "Ensure database schema changes and data migrations are backward compatible"
  (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/canary-deployment.html). Write each
  new migration so that the previous release still works on it. A rollback moves traffic back to
  the old tasks. It does not undo a migration.
- **The connection peak is double.** During `SCALE_UP` "the green service revision scales up to
  100%" while blue still runs (canary page), so a deploy briefly needs `2 × tasks × (10 + 1)`
  connections.
- **Ordinary 404s can roll a deploy back.** The rollback alarm fires "if sum of 4xx and 5xx
  errors percentage is > 1 for 2 datapoints within 3 minutes" (update page). This app answers 404
  to `/chat` while the assistant is off, and to any unknown path, so a crawler during the 5%
  phase counts against the deploy.
- **The listener routes by host header** (`rule-type: host-header`, work page), so only the
  service's hostname reaches the app. Leave `BOOKING_CANONICAL_AUTHORITY` unset. The page does
  not say which `Host` the target-group health check (path `/` by default) sends, and under any
  host but the configured one the app answers 404.

| # | Answer |
|---|---|
| 1 | A Fargate task is billed "from the time you start to download your container image until the Amazon ECS Task … terminates" (https://aws.amazon.com/fargate/pricing/). No page read here describes CPU being throttled between requests, so the push and the jobs run, but that is not stated as a guarantee either |
| 2 | Yes. `desiredMinTaskCount` defaults to 1 (work page). Keep `minTaskCount` at 1 or more. Whether Express Mode scales a service to zero is not stated in that page |
| 3 | `desiredMaxTaskCount` defaults to **20** (work page). That is 220 connections at the default pool, and 440 during a deploy. RDS for PostgreSQL's default `max_connections` is `LEAST({DBInstanceClassMemory/9531392}, 5000)` (https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/CHAP_Limits.html#RDS_Limits.MaxConnections), roughly one connection per 9 MiB of instance memory. Set `--scaling-target '{"minTaskCount":1,"maxTaskCount":N}'` so that `2 × N × 11` fits |
| 4 | RDS for PostgreSQL 18 is generally available, up to 18.6 (https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-versions.html). `btree_gist` 1.8 and `pgcrypto` 1.4 are listed for every 18.x (https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-extensions.html). The master user is `CREATE ROLE postgres WITH LOGIN NOSUPERUSER INHERIT CREATEDB CREATEROLE …`, and through `rds_superuser` it can add extensions and create roles (https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Appendix.PostgreSQL.CommonDBATasks.Roles.rds_superuser.html). So it is a correct migration owner, and since that statement grants no `BYPASSRLS`, PostgreSQL's default `NOBYPASSRLS` applies. **ICU and lz4 are unproven**: RDS "uses the glibc collation" by default, and the collations page does not say whether the ICU provider is available (https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL-Collations.html). No official page found says whether lz4 is available. Run the `pg_collation` check from the Cloud Run section, then let the first start's migration settle lz4: it fails loudly if lz4 is missing |
| 5 | Secrets Manager. Express Mode's container takes `environment` and `secrets`, and lists no volume (https://docs.aws.amazon.com/AmazonECS/latest/APIReference/API_ExpressGatewayContainer.html). A secret is "injected into your container when the container is initially started", and a rotated value needs a new deployment. The task execution role needs the Secrets Manager permissions (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html). **The three file settings have nowhere to go** in Express Mode's own container settings. Leave push's server send, Wallet and payments off, or bring your own task definition (`--task-definition-arn`, https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-getting-started.html) with a volume for them. This file does not cover that |
| 6 | TLS ends at the ALB, which listens on HTTPS 443 and forwards HTTP to the target (work page). `X-Forwarded-For` defaults to `append`: "the load balancer appends the client IP address to the existing header". The ALB "stores the protocol used between the client and the load balancer in the `X-Forwarded-Proto` request header" (https://docs.aws.amazon.com/elasticloadbalancing/latest/application/x-forwarded-headers.html). Whether it replaces a `X-Forwarded-Proto` the caller sent is not stated there. Express Mode creates the service security group with "minimal required ingress", and "When you provide a security group you are providing an additional ingress path to your service" (work page). Do not open the container port any other way, or the forwarded headers become the caller's |
| 7 | Yes for both: Fargate lists Asia Pacific (Jakarta) `ap-southeast-3` (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate-Regions.html), Express Mode "is available in all AWS Regions where Amazon ECS and Fargate are supported" (https://docs.aws.amazon.com/AmazonECS/latest/developerguide/express-service-overview.html), and RDS lists `ap-southeast-3` (https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.RegionsAndAvailabilityZones.html) |

**Deploy.** Push the image to Amazon ECR
(https://docs.aws.amazon.com/AmazonECR/latest/userguide/docker-push-ecr-image.html):

```bash
REGISTRY=123456789012.dkr.ecr.ap-southeast-3.amazonaws.com
IMAGE=$REGISTRY/movie-ticket:1
aws ecr get-login-password --region ap-southeast-3 | docker login --username AWS --password-stdin "$REGISTRY"
docker build -f src/main/docker/Dockerfile.jvm -t "$IMAGE" .
docker push "$IMAGE"
```

Create the RDS instance with `booking` as its initial database. Its master user (`postgres` by
default) is the migration owner. Create the two IAM roles that the CLI walkthrough names
(`ecsTaskExecutionRole` and `ecsInfrastructureRoleForExpressServices`, getting-started page), and
allow the service security group into the database's security group on 5432. Then create the service. The container
listens on 8080 (the Dockerfile's `EXPOSE`), not Express Mode's default of 80:

```bash
aws ecs create-express-gateway-service \
  --region ap-southeast-3 \
  --service-name movie-ticket \
  --execution-role-arn arn:aws:iam::123456789012:role/ecsTaskExecutionRole \
  --infrastructure-role-arn arn:aws:iam::123456789012:role/ecsInfrastructureRoleForExpressServices \
  --primary-container '{
    "image": "'"$IMAGE"'",
    "containerPort": 8080,
    "environment": [
      {"name": "DATABASE_HOST", "value": "movie-ticket.xxxxxxxx.ap-southeast-3.rds.amazonaws.com"},
      {"name": "DATABASE_SSLMODE", "value": "require"},
      {"name": "DATABASE_USER", "value": "postgres"},
      {"name": "DATABASE_MIGRATION_USER", "value": "postgres"},
      {"name": "PUBLIC_HTTPS_BASE_URL", "value": "https://movie-ticket.ecs.ap-southeast-3.on.aws"}
    ],
    "secrets": [
      {"name": "DATABASE_PASSWORD", "valueFrom": "arn:aws:secretsmanager:ap-southeast-3:123456789012:secret:db-owner-password-AbCdEf"},
      {"name": "DATABASE_MIGRATION_PASSWORD", "valueFrom": "arn:aws:secretsmanager:ap-southeast-3:123456789012:secret:db-owner-password-AbCdEf"}
    ]}' \
  --scaling-target '{"minTaskCount":1,"maxTaskCount":2}' \
  --monitor-resources
```

The service's URL has the form `https://<service-name>.ecs.<region>.on.aws/` (getting-started
page). Use it for `PUBLIC_HTTPS_BASE_URL` until you map a domain of your own. The first deploy runs
as the owner, like the first Cloud Run deploy. After the migration, give `app_runtime` its
password and point `DATABASE_USER` and `DATABASE_PASSWORD` at it with
`aws ecs update-express-gateway-service` (update page). That update is itself a canary deploy.

## Azure Container Apps

A container app runs in an *environment*, which is its network boundary. Ingress terminates TLS,
answers on 80 and 443, and "By default, HTTP requests to port 80 are automatically redirected to
HTTPS on 443"
(https://learn.microsoft.com/en-us/azure/container-apps/ingress-overview). Two things follow for
this app:

- **A request is cut at 240 seconds** ("Request time out is 240 seconds", ingress page). A
  `/chat` turn that waits longer than that on the model fails at the ingress, not in the app.
- **Other apps in the same environment can reach this one.** External ingress "makes the app
  reachable from other container apps in the same environment" (ingress page), and they reach it
  without passing through the ingress that sets the forwarded headers. Give this app an
  environment of its own.

| # | Answer |
|---|---|
| 1 | Replicas on the Consumption plan are charged "for the amount of resources allocated to each replica while it's running". A replica at the minimum count that serves no request and uses "less than 0.01 vCPU cores" is billed at a lower *idle* rate (https://learn.microsoft.com/en-us/azure/container-apps/billing). That page describes a billing state, not CPU withheld between requests, and no page read here describes such throttling. So the push and the jobs run, but that is not stated as a guarantee |
| 2 | Yes. The minimum defaults to **0**, and "If you want to ensure that an instance of your revision is always running, set the minimum number of replicas to 1 or higher". `--min-replicas 1`. The same page warns that "Replica quantities are a target amount, not a guarantee" (https://learn.microsoft.com/en-us/azure/container-apps/scale-app) |
| 3 | The maximum defaults to **10**, and "During platform upgrades or maintenance, you might temporarily see more replicas than expected" (scale page). Flexible Server's default `max_connections` follows the SKU, and 15 are reserved: Burstable B1ms (2 GiB) allows 35 user connections, B2s (4 GiB) 414, General Purpose D2s (8 GiB) 844 (https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-limits). Ten replicas × (10 + 1) is 110, past a B1ms. On a B1ms keep `--max-replicas 1`. A B2s holds the default ten, even doubled |
| 4 | PostgreSQL 18 is supported, at 18.6 (https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/concepts-supported-versions). `btree_gist` 1.8 and `pgcrypto` 1.4 are listed for 18 (https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-by-engine?pivots=postgresql-18). **Both must be allow-listed first**, in the server parameter `azure.extensions` (https://learn.microsoft.com/en-us/azure/postgresql/extensions/how-to-allow-extensions). Without that, "any attempt to execute `CREATE EXTENSION` … fails", and the first start fails with it. On Azure Linux 3.0, pgcrypto loses OpenSSL's legacy algorithms, SHA-1 among them (https://learn.microsoft.com/en-us/azure/postgresql/extensions/concepts-extensions-considerations). The schema calls only `digest(…, 'sha256')` and `gen_random_bytes`, so that does not touch it. The server admin is granted "NOSUPERUSER, INHERIT, CREATEDB, CREATEROLE", and "only Microsoft is part of the super user role" (https://learn.microsoft.com/en-us/azure/postgresql/security/security-manage-database-users). So the admin is a correct migration owner. Whether `azure_pg_admin` carries `BYPASSRLS` is not stated there, and the app runs as `app_runtime` anyway. **ICU and lz4 are unproven**: none of these pages mention either. Run the `pg_collation` check from the Cloud Run section, and let the first migration settle lz4 |
| 5 | Container Apps secrets, each "specified directly or as a reference to a secret stored in Azure Key Vault". An environment variable takes one as `secretref:<name>`. `--secret-volume-mount <path>` mounts every secret as a file whose "file name is the name of the secret" (https://learn.microsoft.com/en-us/azure/container-apps/manage-secrets). **So the three file settings work here**: point each at `/mnt/secrets/<secret-name>`. A Key Vault reference needs a managed identity with **Key Vault Secrets User**, and at create time it must be user-assigned. A changed secret reaches a running revision only after a new revision or a restart (same page) |
| 6 | Yes for the scheme: `X-Forwarded-Proto` is "`http` or `https`. This value is overwritten if sent by the client". `X-Forwarded-For` is appended to, and "Only the rightmost IP is provided by Azure Container Apps" (ingress page). Keep the app alone in its environment (above), so ingress stays the only way in |
| 7 | Yes for both. Azure lists Indonesia Central, programmatic name `indonesiacentral`, in Jakarta, with three availability zones and no paired region (https://learn.microsoft.com/en-us/azure/reliability/regions-list). Container Apps, Container Registry, Key Vault and Flexible Server are all GA there (https://azure.microsoft.com/en-us/explore/global-infrastructure/products-by-region/table). Flexible Server's own table lists it with zone-redundant HA and **without geo-redundant backup** (https://learn.microsoft.com/en-us/azure/postgresql/overview) |

**Deploy.** Create a registry and push the image
(https://learn.microsoft.com/en-us/azure/container-registry/container-registry-get-started-azure-cli).
The registry name is 5–50 lowercase letters and digits, and `az acr create` prints the
`loginServer` to push to:

```bash
az login
az extension add --name containerapp --upgrade        # the az containerapp commands
az provider register --namespace Microsoft.App
az provider register --namespace Microsoft.OperationalInsights
RG=movie-ticket LOCATION=indonesiacentral ACR=movieticketregistry
az group create --name $RG --location $LOCATION
az acr create --resource-group $RG --name $ACR --sku Standard
az acr login --name $ACR
LOGIN_SERVER=$(az acr show --name $ACR --query loginServer --output tsv)
IMAGE=$LOGIN_SERVER/movie-ticket:1
docker build -f src/main/docker/Dockerfile.jvm -t "$IMAGE" .
docker push "$IMAGE"
```

The command below uses `Standard_B2s`, but the [cost page](https://dawud-tan.github.io/on-premise-vs-cloud.html)
prices the smaller `B1ms` (32 GB). It shows `B2s` as the
README's own choice and adds about US$51 a month for it; it gives no measurement that `B1ms` is
too small, so the command stays at `B2s`.

Create the database server, allow the two extensions, and create the database
(https://learn.microsoft.com/en-us/azure/postgresql/configure-maintain/quickstart-create-server).
`--public-access 0.0.0.0` admits "all Azure services", which is every Azure customer's address
too, not only yours. Use the private-access route on that page if that is too wide. Your own
machine is not an Azure service, so `psql` from it needs a firewall rule of its own (same page):

```bash
az postgres flexible-server create \
  --resource-group $RG --name movie-ticket-db --location $LOCATION \
  --version 18 --tier Burstable --sku-name Standard_B2s \
  --admin-user booking --admin-password 'change-me-owner' \
  --public-access 0.0.0.0
az postgres flexible-server parameter set \
  --resource-group $RG --server-name movie-ticket-db \
  --name azure.extensions --value "btree_gist,pgcrypto"
az postgres flexible-server firewall-rule create \
  --resource-group $RG --name movie-ticket-db --rule-name AllowMyIP \
  --start-ip-address YOUR.PUBLIC.IP --end-ip-address YOUR.PUBLIC.IP
psql "host=movie-ticket-db.postgres.database.azure.com port=5432 dbname=postgres user=booking sslmode=require" \
  -c 'CREATE DATABASE booking'
```

Create the environment, then the app. The image is pulled with a user-assigned identity, and the
CLI grants it `acrpull` itself
(https://learn.microsoft.com/en-us/azure/container-apps/managed-identity-image-pull). The
secrets here are plain values. For Key Vault references, use the `keyvaultref:` form from the
secrets page instead:

```bash
az containerapp env create --name movie-ticket-env --resource-group $RG --location $LOCATION
az identity create --name movie-ticket-id --resource-group $RG
IDENTITY_ID=$(az identity show --name movie-ticket-id --resource-group $RG --query id --output tsv)
az containerapp create \
  --name movie-ticket --resource-group $RG --environment movie-ticket-env \
  --user-assigned "$IDENTITY_ID" --registry-identity "$IDENTITY_ID" \
  --registry-server "$LOGIN_SERVER" --image "$IMAGE" \
  --target-port 8080 --ingress external \
  --min-replicas 1 --max-replicas 2 \
  --secrets "db-owner-password=change-me-owner" \
  --env-vars "DATABASE_HOST=movie-ticket-db.postgres.database.azure.com" \
             "DATABASE_SSLMODE=require" \
             "DATABASE_USER=booking" "DATABASE_PASSWORD=secretref:db-owner-password" \
             "DATABASE_MIGRATION_USER=booking" "DATABASE_MIGRATION_PASSWORD=secretref:db-owner-password" \
  --query properties.configuration.ingress.fqdn
```

The last line prints the app's hostname (https://learn.microsoft.com/en-us/azure/container-apps/get-started-existing-container-image).
As on the other platforms, the first start runs as the owner. Once it has migrated, give
`app_runtime` its password (the `ALTER ROLE` above, through the same `psql`), then add that
password as a second secret and switch the runtime datasource to it. A secret's name may not be
longer than 20 characters (https://learn.microsoft.com/en-us/cli/azure/containerapp/secret).
`--set-env-vars` adds or updates variables and leaves the others as they are
(https://learn.microsoft.com/en-us/cli/azure/containerapp):

```bash
az containerapp secret set --name movie-ticket --resource-group $RG \
  --secrets "db-runtime-password=change-me-runtime"
az containerapp update --name movie-ticket --resource-group $RG \
  --set-env-vars "DATABASE_USER=app_runtime" "DATABASE_PASSWORD=secretref:db-runtime-password" \
                 "PUBLIC_HTTPS_BASE_URL=https://<the hostname printed above>"
```

The secret alone changes nothing until a new revision or a restart (secrets page).

## Cloudflare Containers

A container here is reached only through a Worker and a Durable Object: "A Container can only be
accessed through its Durable Object", and "all Container requests are passed through a Worker"
(https://developers.cloudflare.com/containers/concepts/architecture/). Wrangler builds the image
with a local Docker, pushes it to Cloudflare's registry, and deploys both
(https://developers.cloudflare.com/containers/get-started/). The image must run on
`linux/amd64`, which `Dockerfile.jvm` does. Containers are billed on the Workers Paid plan
(https://developers.cloudflare.com/containers/platform/pricing/).

What follows for this app:

- **Run exactly one instance, by name.** Every request goes to `getByName("app")`, so there is one
  Durable Object and one container. "Is built-in autoscaling for stateless applications
  available? Not today" (https://developers.cloudflare.com/containers/faq/). Spreading load means
  picking one of N named instances yourself, and each extra instance runs the `@Scheduled` jobs
  again.
- **The default instance is too small.** `instance_type` defaults to `"lite"`, which is 1/16 vCPU
  and 256 MiB (https://developers.cloudflare.com/containers/platform/limits/). Use
  `standard-1` (1/2 vCPU, 4 GiB) or larger for the JVM.
- **A deploy replaces the instance, not beside it.** With `max_instances` below 2 the rollout is
  one 100% step. The old process gets `SIGTERM` and up to 15 minutes to exit, and only then does
  the new one start (https://developers.cloudflare.com/containers/configuration/rollouts/). So two
  versions never share the database, and the site is down while the new JVM starts.
- **The disk is thrown away** whenever the instance sleeps or is replaced: "All disk is ephemeral
  by default" (architecture page). The app keeps nothing on disk, so this costs nothing.

**Where the database lives.** Cloudflare has no managed PostgreSQL, so it lives elsewhere: any
PostgreSQL 18 above that allows `btree_gist`, `pgcrypto` and a non-superuser owner. Hyperdrive is
not a route for this app. It is reached through a binding, from Worker code
(https://developers.cloudflare.com/hyperdrive/), and a container reaches bindings only through
outbound handlers, which intercept "HTTP and HTTPS traffic" only
(https://developers.cloudflare.com/containers/configuration/workers-connections/,
https://developers.cloudflare.com/containers/configuration/outbound-traffic/). JDBC is neither.
Its query cache would also be wrong here: Hyperdrive returns a cached response "When your Worker
sends a later query that matches the original"
(https://developers.cloudflare.com/hyperdrive/concepts/how-hyperdrive-works/), and this app's
tenant is set by `set_config`, not written into the query text. So the container connects to the
database directly. With `enableInternet` at its default of `true`, ports other than 80 and 443
are not blocked (outbound-traffic page). No page read here names the addresses a container
connects from, so the database has to accept connections from the internet. Use a long password
and `DATABASE_SSLMODE=require`.

| # | Answer |
|---|---|
| 1 | Yes while the instance runs. CPU "is based on *active usage* only", and "Charges stop after the container instance goes to sleep" (pricing page). Nothing describes CPU being withheld from a running instance between requests. The thing to prevent is the sleep (row 2) |
| 2 | Yes, but only by keeping it awake yourself. The `Container` class sleeps after `sleepAfter`, default `"10m"`. Its `onActivityExpired()` calls `stop()`, and "If you override `onActivityExpired()`, call `await this.stop()` … Otherwise, the container does not go to sleep" (https://developers.cloudflare.com/containers/api/container-class/). The override below does exactly that. A host restart still stops it: "Cloudflare does not guarantee that any container instance will run for a set period" (FAQ). So a Cron Trigger calls it every minute, and that call starts it again |
| 3 | One named instance: 10 + 1 connections. `max_instances` defaults to 20, but it caps instances, it does not create them (https://developers.cloudflare.com/workers/wrangler/configuration/#containers). The connection limit is the external database's own |
| 4 | That of the external PostgreSQL you choose |
| 5 | Worker secrets (`npx wrangler secret put NAME`), handed to the container as environment variables when it starts. "A running process keeps its startup environment", so a changed secret reaches the app at its next start (https://developers.cloudflare.com/containers/examples/env-vars-and-secrets/). **The three file settings have nowhere to go**: no page read here mounts a file into a container, and the same page says to keep secrets out of images. Leave push's server send, Wallet and payments off |
| 6 | **Only if your Worker makes it so.** The container gets the request the Worker forwards, and no page read here says Cloudflare sets or strips `X-Forwarded-*` on that hop. The Worker below deletes `Forwarded` and every `X-Forwarded-*` the caller sent, then sets `X-Forwarded-Proto: https`. Whether the container sees your public `Host` is not stated either. Sign in once, and check the `redirect_uri` on Google's sign-in page |
| 7 | No Indonesian region. The narrowest constraint is `regions: ["APAC"]`, and the jurisdictions are `eu`, `us` and `fedramp` (https://developers.cloudflare.com/containers/concepts/placement/). Within APAC, the container starts at "the nearest location with a pre-fetched image" (architecture page), and nothing pins it to Jakarta. The database's region is the database provider's answer |

**Deploy.** Migrate the database from your own machine first, by the steps under "Against your
own PostgreSQL 18" pointed at the external database, and give `app_runtime` its password there.
The container then starts as `app_runtime` from its first start. Switching later would not work
here: the instance never sleeps, and a deploy that changes only `vars` "starts no rollout"
(rollouts page), so the running JVM would keep its old settings.

Put these two files beside `pom.xml`, so the image builds from the repository root,
then install Wrangler and the `Container` class (`npm i -D wrangler` and `npm i
@cloudflare/containers`, as the get-started template does). `wrangler.jsonc`:

```jsonc
{
  "name": "movie-ticket",
  "main": "worker.js",
  "compatibility_date": "2026-10-31",   // the day you first deploy, as the docs say
  "containers": [
    {
      "class_name": "MovieTicket",
      "image": "./src/main/docker/Dockerfile.jvm",
      "image_build_context": ".",
      "instance_type": "standard-1",
      "max_instances": 1,
      "constraints": { "regions": ["APAC"] }
    }
  ],
  "durable_objects": {
    "bindings": [{ "name": "MOVIE_TICKET", "class_name": "MovieTicket" }]
  },
  "exports": {
    "MovieTicket": { "type": "durable-object", "storage": "sqlite" }
  },
  "triggers": { "crons": ["* * * * *"] },
  "vars": {
    "DATABASE_HOST": "db.example.com",
    "DATABASE_SSLMODE": "require",
    "DATABASE_USER": "app_runtime",
    "DATABASE_MIGRATION_USER": "booking",
    "PUBLIC_HTTPS_BASE_URL": "https://movie-ticket.example.workers.dev"
  }
}
```

`worker.js`:

```js
import { Container, getContainer } from "@cloudflare/containers";

// Every setting the JVM reads, from `vars` above or from `wrangler secret put`.
const SETTINGS = ["DATABASE_HOST", "DATABASE_SSLMODE", "DATABASE_USER", "DATABASE_PASSWORD",
  "DATABASE_MIGRATION_USER", "DATABASE_MIGRATION_PASSWORD", "PUBLIC_HTTPS_BASE_URL"];

export class MovieTicket extends Container {
  defaultPort = 8080;

  constructor(ctx, env) {
    super(ctx, env);
    this.envVars = Object.fromEntries(
      SETTINGS.filter((k) => env[k] !== undefined).map((k) => [k, env[k]]));
  }

  // Never sleep: the hold-expiry sweep and the payment reconciliation run on no request.
  async onActivityExpired() {}
}

export default {
  async fetch(request, env) {
    // The app trusts X-Forwarded-*, so the caller's copies never reach it.
    const headers = new Headers(request.headers);
    for (const name of [...headers.keys()]) {
      if (name === "forwarded" || name.startsWith("x-forwarded-")) headers.delete(name);
    }
    headers.set("x-forwarded-proto", "https");
    return getContainer(env.MOVIE_TICKET, "app").fetch(new Request(request, { headers }));
  },

  // Starts the one instance again after a host restart or a rollout, with no visitor needed.
  async scheduled(_controller, env) {
    await getContainer(env.MOVIE_TICKET, "app").fetch(new Request("https://movie-ticket.invalid/"));
  },
};
```

Setting `envVars` in the constructor uses a documented property. The documented examples set it
as a class field, which cannot read `env`. Then set the passwords and deploy:

```bash
npx wrangler login
npx wrangler secret put DATABASE_PASSWORD
npx wrangler secret put DATABASE_MIGRATION_PASSWORD
npx wrangler deploy
npx wrangler containers list
```

"After you deploy your Worker for the first time, wait several minutes before you expect
container requests to succeed" (get-started page). The Worker's URL has the form
`https://<worker-name>.<your-subdomain>.workers.dev` (same page). `DATABASE_PASSWORD` is
`app_runtime`'s password, and `DATABASE_MIGRATION_PASSWORD` is the owner's: Flyway still runs as
the owner on every start.

## Alibaba Cloud Serverless App Engine (SAE)

SAE deploys an image from Container Registry (ACR). "The SAE application and the ACR instance
must be in the same region", and an Enterprise Edition registry also needs the application's VPC
added to its access control. A Personal Edition registry needs no extra network setup
(https://www.alibabacloud.com/help/en/sae/deploy-applications-using-acr-images-of-your-account).
The image's `ENTRYPOINT` is the start command. Two things follow for this app:

- **It has no internet until you give it some.** "Enable outbound access by configuring a NAT
  Gateway or binding an EIP to an application instance" (same page). Sign-in, push, payments and
  the assistant all call out, so without one only the booking pages work.
- **A rolling update keeps old instances serving.** "Minimum available instances specifies the
  minimum number of instances that remain available during a rolling update"
  (https://www.alibabacloud.com/help/en/sae/configure-auto-scaling-new). At 1 or more, the old
  version serves on the schema the new one migrated, as on ECS. Write each migration so the
  previous release still runs on it.

| # | Answer |
|---|---|
| 1 | Not stated. Idle mode bills an idle vCPU at a lower coefficient, "0.2 for idle and 1 for active" on the standard edition, and it is switched under **Auto Scaling > Idle Mode** (https://www.alibabacloud.com/help/en/sae/idle-mode-new). That page does not say whether an idle instance's CPU is throttled, so **turn idle mode off**: then "all instances are billed at the active rate", and nothing depends on the unstated part |
| 2 | Yes: set **Number of Instances** to 1 or more when you create the application (deploy page). The auto-scaling page does not say whether its minimum can be 0, or whether SAE scales to zero |
| 3 | "The maximum number of instances for a single application is 50" (auto-scaling page). RDS's connection limit is fixed by the instance type: the 1-core, 2 GB `pg.n2e.1c.1m` allows 50, the 2-core, 4 GB `pg.n2.2c.2m` allows 400 (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/primary-apsaradb-rds-for-postgresql-instance-types). To raise it, "upgrade to a larger instance type" (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/how-to-handle-excessive-connections-of-an-apsaradb-rds-for-postgresql-instance). On 50, keep 2 instances: 2 × 11, and a rolling update's extra one, fit |
| 4 | PostgreSQL 18 is "Available", released on RDS on 2025-12-12 (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/lifecycles-of-major-engine-versions). `btree_gist` 1.8 and `pgcrypto` 1.4 are listed for 18, and neither needs `shared_preload_libraries` (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/extensions-supported-by-apsaradb-rds-for-postgresql). "ApsaraDB RDS for PostgreSQL does not provide superusers" (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/configure-user-permissions-for-an-apsaradb-rds-for-postgresql-instance). So a privileged account cannot bypass RLS as a superuser. **Whether it has `CREATEROLE` is not stated** in those pages. Check with `SELECT rolcreaterole, rolbypassrls FROM pg_roles WHERE rolname = current_user;`. Without it, the migration warns and the app runs as the owner, as described under the development PC. **ICU and lz4 are unproven**: the extensions page mentions neither. Run the `pg_collation` check from the Cloud Run section, and let the first migration settle lz4. Serverless RDS instances do not support 18 (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/create-a-serverless-apsaradb-rds-for-postgresql-instance), so create an ordinary one |
| 5 | Secrets in the namespace. An environment variable can be "Reference the Secret", one key or all keys (https://www.alibabacloud.com/help/en/sae/configure-environment-variables-2-0). A Secret can also be mounted at a container path, which "mounts all keys from the secret to the specified path" (https://www.alibabacloud.com/help/en/sae/inject-secret). **So the three file settings work here.** The page does not say how the mounted files are named, so look in the mount path once before pointing the settings at it. A change takes a redeploy, and "After you redeploy an application, the application is restarted" (same page) |
| 6 | Through a CLB with an HTTPS listener, whose backend protocol is "Fixed to **HTTP**". `X-Forwarded-For` is "Enabled by default on Layer 7 listeners and cannot be disabled". `X-Forwarded-Proto` is a header you select, "Retrieve Listener Protocol" (https://www.alibabacloud.com/help/en/slb/classic-load-balancer/user-guide/add-an-https-listener-1). **Select it**, or the app builds `http://` URLs. Whether CLB replaces an `X-Forwarded-Proto` the caller sent is not stated there. Bind an internet-facing CLB, with container port 8080 (https://www.alibabacloud.com/help/en/sae/sae-2-bind-clb-for-application). That page also says "If you use a custom domain name over HTTPS, it must be **filed with Alibaba Cloud**" |
| 7 | Yes for SAE: "Indonesia (Jakarta)", region ID `ap-southeast-5` (https://www.alibabacloud.com/help/en/sae/latest/regions-supported-by-sae). RDS for PostgreSQL lists Jakarta in its table of default time zones by region (https://www.alibabacloud.com/help/en/rds/apsaradb-rds-for-postgresql/time-zones). No page read here says whether version 18 is on sale there, so check the console's version list when you create the instance |

**Deploy.** Create a Personal Edition registry in `ap-southeast-5`, then log in, tag and push with
the endpoint its console shows
(https://www.alibabacloud.com/help/en/acr/user-guide/use-a-container-registry-personal-edition-instance-to-push-and-pull-images):

```bash
REGISTRY=crpi-xxxx.ap-southeast-5.personal.cr.aliyuncs.com   # your instance's endpoint
IMAGE=$REGISTRY/your-namespace/movie-ticket:1
docker login --username=your-registry-username "$REGISTRY"
docker build -f src/main/docker/Dockerfile.jvm -t "$IMAGE" .
docker push "$IMAGE"
```

Create the RDS for PostgreSQL 18 instance in the same VPC as the SAE namespace, with a
privileged account and a database named `booking`. Create a Secret in the namespace holding the
owner's password. Then create the application in the SAE console (deploy page): image deployment
from **My Alibaba Cloud Images**, one instance or more, and these environment variables:
`DATABASE_HOST` (the instance's internal endpoint), `DATABASE_SSLMODE=require`, `DATABASE_USER`
and `DATABASE_MIGRATION_USER` (the privileged account), and `DATABASE_PASSWORD` and
`DATABASE_MIGRATION_PASSWORD` as references to the Secret. Add the NAT Gateway, turn idle mode
off (row 1), and bind the CLB with `X-Forwarded-Proto` selected (row 6). Once the first start has migrated, give `app_runtime`
its password, add it to the Secret, point `DATABASE_USER` and `DATABASE_PASSWORD` at it, and
redeploy.

## Operating in Indonesia

This is the paperwork the code assumes. It is a list of what to read, not legal advice.

- **Registering as a private-scope electronic system operator.** Peraturan
  Pemerintah Nomor 71 Tahun 2019 tentang Penyelenggaraan Sistem dan Transaksi Elektronik, and
  Peraturan Menteri Komunikasi dan Informatika Nomor 5 Tahun 2020 tentang Penyelenggara Sistem
  Elektronik Lingkup Privat. Each operator registers before going live, and the number it receives
  belongs in `tenant.pse_registration_no`.
- **Personal data and consent.** Undang-Undang Nomor 27 Tahun 2022 tentang Pelindungan Data
  Pribadi. Every processing needs a legal basis, and `consent_record.legal_basis` stores which
  one. Consent must be provable, which is why the age tick stores a hash of the exact sentence
  shown (`consent_record.policy_text_hash`).
- **The assistant's host abroad.** The same law governs transfer of personal data outside
  Indonesia. A `/chat` message goes to the model's host, so `BOOKING_ASSISTANT_HOST` must name
  that host and its country. `/chat`'s notice shows it to the customer before the first message.
- **Film age classification.** Undang-Undang Nomor 33 Tahun 2009 tentang Perfilman, and Peraturan
  Pemerintah Nomor 18 Tahun 2014 tentang Lembaga Sensor Film. The ratings are the Lembaga Sensor
  Film's (`film.classification`: `SU`, `13+`, `17+`, `21+`). A restricted showing asks for the
  customer's attestation tick on every hold. Checking age at the door stays a staff job.
- **The language of a perjanjian.** Undang-Undang Nomor 24 Tahun 2009 tentang Bendera, Bahasa, dan
  Lambang Negara, serta Lagu Kebangsaan. A perjanjian with an Indonesian party is written in Bahasa
  Indonesia. A second language may sit beside it, but it does not replace it.

## Questions an operator should answer from the code

For anyone who runs movie-ticket for cinemas and asks them to pay, and for a cinema's developer
deciding whether to trust that person. The claims in this file's first paragraph are only as good
as the code behind them, and a competent developer will ask to see it.

How to use it: read the files under "Read first" in an editor, then answer each question aloud
with a `path:line`. "I don't know, it's in `<file>`" is an acceptable answer. A slogan is
not, because the next question will be about the code. Each question says where its answer
lives and never what it is. Line numbers are for this commit; find the symbol if they
have drifted.

"Break it" turns a claim into an experiment. Make the change, **write down which test fails and
with what message before you run it**, run one test class, then `git restore` the file. A wrong
prediction is not a failure. A change that no test catches is a finding: a gap in the suite.

```bash
./mvnw test -DskipJsTests -Dtest='SeatMapCsvTest'      # one class; see "Tests" above for the rest
cd src/test/js && npm install && npm test              # the three JS checks alone (needs Node)
```

All paths are under `src/main/java/com/protek/booking/` unless they start with `src/`.

### The claims and where the database enforces them

**Read first:** `src/main/resources/db/migration/V1__init_schema.sql`, only the parts that
`grep -n -E 'EXCLUDE|FORCE ROW|CREATE POLICY|app_runtime'` finds; the "On a development PC" and
"Against your own PostgreSQL 18" sections above.

**Check yourself:**

1. Read the `EXCLUDE` aloud (V1:485-487, in `CREATE TABLE reservation`, V1:441). Which column is
   compared with `=`, which with `&&`, what is `period`, and which rows does its `WHERE` admit?
2. Admission is the column `admitted_at` (V1:482), not a `status` value. Read the comment at V1:480
   and the `WHERE` of question 1 together: what would an `'admitted'` status do?
3. Which role does the app run as (V1:1001-1006, README "Against your own PostgreSQL 18")? Why does
   a policy bind that role, given who owns the tables (V1:886 is the pattern)? Which setting do the
   policies read (V1:888), and where does the app set it (`db/Db.kt:107`, `setTenantContext`)?
4. What happens to a query with no tenant set at all? Read the comment at V1:869 before answering.

### Nobody gets the same seat twice

**Read first:** `service/BookingService.kt`: the table at 53-70, the retry comment at 155-179,
`holdTransaction` (199), `holdInternal` (308), `refuseIfDayIsFull` (1057). Then
`repository/ResourcePoolSessionRepository.kt:171` (`findByIdForUpdate`),
`repository/ResourceUnitRepository.kt:116` (`findBookableIds`),
`repository/CustomerRepository.kt:84` (`findByIdForUpdate`),
`repository/ReservationRepository.kt:29` (`insertHold`), `db/DataAccessException.kt:25-44`,
`db/Db.kt:50` and `:107`, `service/CurrentTenant.kt:75`, `service/PurchaseCap.kt:30`. Find every
caller of the hold path: `web/customer/SeatMapResource.kt:135`, `web/api/AppApiResource.kt:626`,
`web/admin/CounterSaleResource.kt:95`. In `src/test/java/com/protek/booking/concurrency/`:
`SeatBookingConcurrencyDifferentialTest`, `SessionCancellationRaceDifferentialTest`,
`DailyPurchaseCapConcurrencyDifferentialTest`; for each, work out what its "protection removed"
arm does and what each arm asserts.

**Check yourself:**

1. From the hold request to the commit: each statement in order, the lock each takes (the
   showing's, the seats', the customer's), and the line where the constraint gives its answer.
   Start at `holdInternal` (BookingService.kt:308-485).
2. Why does the showing lock come first (343), and what do eight simultaneous holds do without it?
   What is `40P01` (`DataAccessException.kt:35-44`), and why is it retried (BookingService.kt:199-210)
   when `23P01` is not (457-460)?
3. Why is there no availability pre-check? Where does `ReservationRepository.kt:16-18` say so, and
   why does the showing lock (343) or `findBookableIds` (372) not count as one? Compare
   BookingService.kt:968.
4. Where does a request's tenant come from (`CurrentTenant.kt:75`, `:125`,
   `security/AppUserAugmentor.kt:71`)? Why can no caller choose it? What does `is_local => true`
   (`Db.kt:109-111`) buy over a session-level setting when connections are pooled?
5. A customer ticks a seat and someone else takes it before the next refresh. What does htmx do
   with the tick? Read `src/main/resources/templates/customer/seatmapFragment.html:207-227`
   and step 0 of `src/test/js/seatmap-selection.test.mjs:77-89`. Why is that markup and not JS?

**Break it:**

1. Run the three differential tests unchanged. Predict what each unprotected arm observes.
2. Drop `FOR UPDATE` from the SQL in `ResourcePoolSessionRepository.kt:173`. Predict which
   classes fail, then run `SeatBookingConcurrencyTest`, `SessionCancellationRaceTest` and
   `SessionCancellationRaceDifferentialTest`, one at a time. A differential test that brings its own
   unlocked read may not notice.
3. In `Db.kt:109`, change the third argument of one `set_config` from `true` to `false`. Predict the
   result, then run `RowLevelSecurityTest` and `TenantResolutionSecurityTest`.
4. Give the taken-seat `<span>` (seatmapFragment.html, the `{#else}` branch after line 226) an
   `id`. Predict which JS step fails, then run the JS checks.

**A skeptical developer asks:**

- "Why not run everything `SERIALIZABLE` and retry?" (BookingService.kt:56-73)
- "We'd put a unique index on (showing, seat). Why do you need `EXCLUDE`?" (V1:485-487)
- "Two phones a hundred milliseconds apart isn't concurrency. What have you actually shown?"
- "The counter sells F7 and the website sells F7 a second later. Show me." (BookingService.kt:276,
  `holdForCounterSale`)
- "What does your app connect as? Could a bug in one query read another cinema's bookings?"
- "Which of the cases in that table is not an Adya anomaly, and why does it matter?"

### What the customer and the staff see: seat map, import, counter, door

**Read first:** `web/customer/SeatMapResource.kt` (`seatmap` 81, `fragment` 109, `hold` 123) and
`web/customer/BookingResource.kt:53`; `src/main/resources/META-INF/resources/js/seatmap-selection.js`
(header 38-44, `beforeRequest` 426, `visibilitychange` 433); `service/CalendarPricing.kt:45`
(`priceFor`) as far as the map calls it. Import: `service/SeatMapCsv.kt:137`, `SeatMapPlan.kt:139`,
`SeatMapDiff.kt:67`, `web/admin/HallAdminResource.kt` (`planSeatsFromCsv` 259, `applySeats` 291),
`service/CatalogService.kt` (`importSeatsFromCsv` 478, `applyRelayout` 544). Counter:
`web/admin/CounterSaleResource.kt` (`seatPicker` 84, `hold` 90), `web/admin/CounterPaymentResource.kt:46`,
`payment/PaymentService.kt` (`settleCash` 613). Ticket: `web/TemplateExtensions.kt:203`
(`admissionBarcode`), `admission/Code128.kt:111` (`toSvg`). Door: `service/AdmissionService.kt:167`
(`scan`), `repository/ReservationRepository.kt:297` (`admit`). Tests: `SeatMapCsvTest`,
`SeatMapDiffTest`, `SeatMapPlanTest` (no Docker), `SeatMapRelayoutTest`, `CashSettlementTest`,
`Code128Test`, `AdmissionScanTest`, `AdmissionRescanConcurrencyTest`.

**Check yourself:**

1. A customer taps a free seat. Which endpoint and template answer, what does the 3 s poll send
   (seatmapFragment.html:199-201) and get back, and what happens to the tick if someone else takes
   the seat first?
2. Why does the poll stop on a hidden tab, how does it restart, and why could the gate not stay in
   `hx-trigger` (seatmap-selection.js:38-44 and the Content-Security-Policy, `web/CanonicalAuthorityFilter.kt:104`)?
3. What are the CSV's columns, what does the parser refuse and with which message
   (`SeatMapCsv.kt:160-237`, `SeatMapCsvTest`)? What does a relayout of a hall with confirmed
   bookings do to a seat the new plan drops (`CatalogService.kt:530-551`)?
4. A walk-in sale, from an empty counter screen to a printed ticket: the service calls, the hold,
   the cash settlement, and what the barcode encodes.
5. Why does one ticket admit only once without any pre-check, and why is admission a column and
   not a status (V1:480-482, `ReservationRepository.kt:279-301`)?

**Break it:**

1. Make the relayout delete a dropped seat instead of setting `is_active = false`
   (`ResourceUnitRepository.kt:146`). Predict the result, then run `SeatMapRelayoutTest`.
2. Remove `AND admitted_at IS NULL` from the admission UPDATE (`ReservationRepository.kt:301`).
   Predict the result, then run `AdmissionScanTest` and `AdmissionRescanConcurrencyTest`.
3. Pick a seat-label style your cinema really uses (a skipped letter, a numbered row, a gap). Break
   `SeatMapCsv.rowOf` (319) or `number` (342) for it, predict the result, run `SeatMapCsvTest`.

**Hands-on:** in dev mode ("Dev mode" above, a throwaway database), import one real hall, then sell
one walk-in seat on `/admin/counter`.

### Money, holds and holidays

**Read first:** `payment/PaymentService.kt` (`createQrisCharge` 112, `applyNotification` 209,
`applySnapNotification` 236, `applyVerifiedStatus` 299, `applyLateSettlement` 463,
`OrderAlreadyPaidException` 174), `payment/MidtransSignatureVerifier.kt:51`,
`web/payment/PaymentWebhookResource.kt:50`, `payment/PaymentReconciliationService.kt` (`reconcile` 76,
`reconcileOne` 117), `web/admin/PaymentAttentionResource.kt:52`, `service/ReservationLifecycleService.kt`
(header 33, `sweep` 70). After commit: `service/PushNotificationService.kt:36`,
`service/SeatAvailabilityNotifier.kt:50`. Pricing: `service/CalendarPricing.kt:45`, `RatePlan.kt:57`,
`repository/PublicHolidayRepository.kt:36`, `web/admin/HolidayAdminResource.kt:72`. Tests:
`PaymentWebhookDifferentialTest`, `PaymentWebhookEndpointTest`, `ReservationLifecycleDifferentialTest`,
`BookingFlowDifferentialTest`, `CalendarPricingTest`.

**Check yourself:**

1. QRIS from the tap to "paid": the calls made, the rows written, who tells whom, and what the
   customer's page polls.
2. The webhook: who can call it, what the signature covers (`MidtransSignatureVerifier.kt:51`), and
   what happens to a forged, a replayed and a late notification. Where does `OrderAlreadyPaidException`
   come in (`PaymentService.kt:134`)?
3. A missed webhook: what does reconciliation do, how often (`PaymentReconciliationService.kt:75`),
   and what lands in "payments needing attention" (`PaymentService.kt:718-748`)?
4. A hold nobody pays: when does it let the seat go, and what does the letting go
   (`ReservationLifecycleService.kt:70-120`)? Does an expired hold not yet swept still block the
   seat? Read the `WHERE` at V1:487 again.
5. Push: why does it fire after commit and fire-and-forget (`BookingService.kt:473-486`), and what
   does the customer lose when it fails?
6. The price of a seat on a holiday, on the day before one and on an ordinary Tuesday: which code
   decides (`PublicHolidayRepository.kt:36-42`, `CalendarPricing.kt:45`), and where does an admin
   change it?
7. Which payment gateway runs in a given build (the Payments block of the env file above, `payment/snap/` and
   `payment/HttpMidtransGateway.kt`), and what does the code do while its credentials are unset
   (`SnapMidtransGateway.kt:125`, `PaymentService.kt:98`)?

**Break it:**

1. Make the signature check always pass (`MidtransSignatureVerifier.kt:51`). Predict the result,
   then run `PaymentWebhookDifferentialTest` and `PaymentWebhookEndpointTest`.
2. Send a push inside the transaction, before the commit, and make it throw
   (`BookingService.kt:473`). Predict what the customer sees and which test fails, if any.
3. Shift the day-before-a-holiday rule by one day (`PublicHolidayRepository.kt:42`). Predict the
   result, then run `CalendarPricingTest`.

**A skeptical developer asks:** "Our public-holiday prices start the day before. Show me where that
is, and how my staff change it for next year's holidays." And: "What if Midtrans calls your webhook
twice?"

### Who can do what, and what the pages promise

**Read first:** `security/Roles.kt:19`, `AppUserAugmentor.kt:71`, `CurrentActor.kt:29`,
`IdentityActorContext.kt:24`, `StaffNav.kt:34`, `web/customer/AuthResource.kt`;
`web/CanonicalAuthorityFilter.kt` (`addSecurityHeaders` 104, `CSP_HEADER` 138) and
`src/test/java/com/protek/booking/web/CspReadyTemplatesTest.kt:39`; `service/AgeClassification.kt`
(`Gate.consentPurpose` 121, `gateFor` 179), `AgeAdvisoryService.kt:48`,
`repository/ConsentRecordRepository.kt:39`, `i18n/Msg.kt:1618`, `i18n/TemplateLocale.kt:32`;
if you will show `/chat`: `agent/AssistantTools.kt` (header 32, `propose_seats` 176) and
`web/customer/ChatResource.kt:78`.

**Check yourself:**

1. Customer, counter staff, admin, platform admin: where is each role checked
   (`@RolesAllowed` in `web/platform/PlatformAdminResource.kt:60`, `web/admin/StaffAdminResource.kt:42`,
   `web/admin/PaymentAttentionResource.kt:55`), and what stops a counter account seeing another
   cinema? `AdminAuthorizationTest` and `RowLevelSecurityTest` are the evidence.
2. What does the CSP allow, and why is each host there? What breaks if a template gains an inline
   script, and which test says so?
3. The age attestation: what does the customer tick, what is stored (`ConsentRecordRepository.kt:39`),
   why is editing the Indonesian sentence a data change, and why does `consentPurpose` carry the
   language (`AgeClassification.kt:121`)?
4. Why does the URL path outrank the cookie for language (`i18n/TemplateLocale.kt`,
   `i18n/LanguageResource.kt:19-25`)?
5. `/chat`: what can it do and what can it not, and why does it have no hold tool
   (`AssistantTools.kt:32`, `:176`)?

**Break it:** add an inline `<script>` to a template under `src/main/resources/templates/customer/`.
Predict which test fails and with what message, then run `CspReadyTemplatesTest`.

### Onboarding a cinema, its staff and its money

**Read first:** `service/TenantProvisioningService.kt` (`provision` 130, `validate` 237),
`web/platform/PlatformAdminResource.kt` (`provision` 97, `recordPseRegistration` 129),
`service/DefaultTenant.kt:42`, `service/StaffAdminService.kt` (`invite` 75),
`service/BillingService.kt` (`monthlyAmountFor` 70, `generateMonthlyPeriods` 110),
`repository/TenantBillingPeriodRepository.kt`, `PlatformAgreementRepository.kt`,
`TenantAgreementAcceptanceRepository.kt`, `payment/snap/` (six files, 1,118 lines;
`SnapMidtransGateway.kt:127`, `SnapNotificationVerifier.kt:36`, `SnapSignatures.kt`), and the README's
"Operating in Indonesia" section. Tests: `TenantProvisioningTest`, `BillingCycleTest`.

**Check yourself:**

1. A new cinema as a tenant, step by step: what the platform admin does, which rows appear, how a
   request ends up in that tenant (`CurrentTenant.kt:75`), and what the PSE registration the code
   records has to do with any of it.
2. Its first counter staff account: who creates it, with which role, and what it can reach
   (`StaffAdminService.kt:75`, `StaffAdminResource.kt:42`).
3. What does the code record about what a tenant pays (`BillingService.kt:70-110`), and what can it
   not know about a price agreed on paper?
4. What does `payment/snap/` do that the sandbox gateway does not, and what must be true before the
   first real payment (`SnapCredentials.kt`, `SnapMidtransGateway.kt:125`)?

**Break it:** delete the `complaintPhone` check in `TenantProvisioningService.validate`
(`TenantProvisioningService.kt:254`). Predict which test fails and with what message, then run
`TenantProvisioningTest`.

## Other readers

Everything above is for developers and operators. Four pages on the author's site, in Bahasa
Indonesia, serve the other readers. They link back here, to the code at a fixed commit.

- Ticket buyers: [Kursi Anda, uang Anda](https://dawud-tan.github.io/pembeli-tiket.html) says who is
  responsible when an online cinema purchase goes wrong, the buyer's rights, which evidence to keep,
  and where to complain, cheapest first.
- Cinema owners: [Sebelum menandatangani](https://dawud-tan.github.io/pengelola-bioskop.html) lists
  nine checks before signing with an operator of this system, how to run each yourself, and what a
  reasonable answer looks like.
- Cost: [Titik impas](https://dawud-tan.github.io/on-premise-vs-cloud.html) compares a server at the
  cinema, the operator's server and five clouds over 3 and 5 years, with a calculator.
- Anyone: [Agen Elektronik, Agen AI, dan Anomali Adya](https://dawud-tan.github.io/) explains what an
  electronic agent is, what an AI agent is made of, and the isolation anomalies this repository's
  `EXCLUDE` constraint prevents.
