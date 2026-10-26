# OrderManager

A small, end-to-end order management MVP for a 3PL warehouse: create or bulk-import orders, allocate stock,
pick, pack, ship and deliver them, and watch the numbers on a dashboard. Built as a hiring assessment to show
five things, each backed by code and a test you can run:

1. an efficient frontend and user flow
2. efficient code in the hot paths
3. a safe database setup with data-loss prevention
4. security and encryption
5. how long it took to build

**Stack:** Java 27 (OpenJDK, Temurin) · Spring Boot 4.1 · Spring Security 7 · PostgreSQL 18 · Flyway · Thymeleaf + HTMX ·
Docker Compose · JUnit 5 + Testcontainers.

Built with AI assistance using Claude Code.

---

## Try it in your browser (no install)

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://codespaces.new/swrodrigues/OrderManager?quickstart=1)

1. Click the button above (or on GitHub: **Code → Codespaces → Create codespace on main**). A free GitHub
   account is enough; it uses your personal Codespaces allowance.
2. The codespace builds and starts the app and database by itself. The first start takes a few minutes
   (progress in the **Terminal** panel); then the app opens in a new browser tab. If it doesn't, open the
   **Ports** tab and click the globe icon next to port **8080**.
3. Log in with **`admin` / `reviewer-demo-2026`**. Demo data (50 SKUs, 1,000 orders) is created on first start.

The codespace and its URL are private to you, served over HTTPS, with secrets generated for that codespace
only. The code is right there too: run `scripts/test.sh` in its terminal for the full test suite. Stop the
codespace when done (it also stops by itself after inactivity) so it does not use up your allowance.

## Quick start

You need only Docker. Maven runs inside the build container.

```bash
cp .env.example .env
```

Edit `.env`: set `POSTGRES_PASSWORD`, `APP_ADMIN_PASSWORD` (12+ characters) and an encryption key:

```bash
openssl rand -base64 32
```

Then:

```bash
docker compose up --build
```

Open <http://localhost:8080> and log in with `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD`.
With `SPRING_PROFILES_ACTIVE=demo` (the default in `.env.example`) an empty database is filled with 50 SKUs and
1,000 orders in every status (takes ~20 s after start; the app is usable meanwhile).

## 5-minute demo script

1. **Dashboard:** open orders, average time to ship over 30 days, stock-outs, orders by status. Every tile
   links to the filtered order list. Note the footer: *rendered in N ms*.
2. **Orders:** filter by status, type an order number. Only the table rows reload (HTMX). Click
   *Load more*: keyset paging, so every page is an index range scan and page 20 is as fast as page 1.
3. **Bulk allocate NEW orders:** oldest first, one transaction per order. Orders without enough stock stay NEW
   and are counted as short.
4. **Row actions:** Allocate → Pick → Pack → Ship → Deliver. Each click swaps one table row (~1 KB). Clicked by
   mistake? The changed row shows **↶ Undo** (also on the order page): it reverts that one step, puts stock back,
   and is audited. Allowed for 5 minutes, for the same user or an admin, and only if nothing changed since.
5. **Conflict:** open the same order in two tabs, click *Allocate* in both. The second tab shows
   *"This order changed — reload"*. Stock is reserved once.
6. **Import:** download the sample CSV, upload it, then upload it again (rejected as a duplicate). Upload a file
   with a bad SKU to see per-line errors while the good orders still import.
7. **Stock (admin):** adjust a SKU's on-hand with a reason, then open an order to see the audit timeline.

## How each criterion is met

| Criterion | What to look at | Proof you can run |
|---|---|---|
| **Efficient frontend** | Server-rendered pages + HTMX partial updates; no JS framework, no CDN; ~50 KB of JS total ([`static/js`](src/main/resources/static/js)). Row actions, filters and "Load more" fetch HTML fragments only. Render time is shown in every page footer. | `OrderControllerIT` (HTMX requests return rows, not pages) |
| **Efficient code: import** | [`CsvImportService`](src/main/java/com/ordermanager/importer/CsvImportService.java): streaming parse + SHA-256 in one pass, all SKUs resolved in **one** query, all existing refs in **one** query, JDBC batch inserts (driver-rewritten to multi-row `INSERT`), one transaction per 500 orders. Strict UTF-8; an order with any unreadable or invalid line is rejected whole. | `CsvImportBenchmarkIT`: **10,000 orders / 20,000 rows in 1.7–4.1 s** (across runs) against real PostgreSQL in Docker on a laptop |
| **Efficient code: allocation** | [`StockService.reserve`](src/main/java/com/ordermanager/inventory/StockService.java): one `SELECT … FOR UPDATE` for all SKUs of the order, **in id order** (no deadlocks), all-or-nothing. | `StockServiceIT.twentyThreadsCannotOversellFiveUnits`, `oppositeLineOrderDoesNotDeadlock` |
| **Efficient code: queries** | [`OrderQueryService`](src/main/java/com/ordermanager/order/OrderQueryService.java): keyset pagination with a row-value comparison `(created_at, id) < (?, ?)` that PostgreSQL uses as an index range, line counts in the same statement (no N+1). Dashboard = 3 SQL aggregates ([`KpiQueryService`](src/main/java/com/ordermanager/dashboard/KpiQueryService.java)). State machine is an `EnumMap` ([`OrderStateMachine`](src/main/java/com/ordermanager/order/OrderStateMachine.java)). | `OrderQueryServiceIT.queryCountIsConstant` (exactly 1 statement per page for 10 or 100 orders); `OrderQueryServiceIT.nextPageQueryUsesTheIndexAsARange` (EXPLAIN of the generated SQL on 20,000 rows shows an `Index Cond`); `OrderStateMachineTest` (all 49 status pairs); `OrderServiceIT.concurrentAllocationsOfDifferentOrdersSharingASkuAllSucceed` |
| **Database & loss prevention** | Flyway-only schema ([`V1__init.sql`](src/main/resources/db/migration/V1__init.sql)), `ddl-auto=validate`; check constraints (`0 <= reserved <= on_hand`, `qty > 0`); unique `external_ref` and file hash; `@Version` optimistic locking; every state change + stock change + audit row in one transaction; soft delete; audit log; backup/restore scripts. | `OrderServiceIT.staleVersionIsRejectedAndStockReservedOnce`, `CsvImportServiceIT.sameFileTwiceIsRejected`, `StockServiceIT.reserveIsAllOrNothing`; backup drill below |
| **Security & encryption** | Ship-to address and phone encrypted with **AES-256-GCM** (random IV, auth tag, versioned format) via a JPA converter ([`CryptoService`](src/main/java/com/ordermanager/security/CryptoService.java)); key only from env. BCrypt(12) passwords, 12-char minimum, lockout after 5 failures (counted with one atomic `UPDATE`, so parallel attempts cannot slip past it), no default credentials. Disabling a user or resetting their password ends their open sessions. Uploaded CSVs stay in memory, never on disk. Roles enforced on URLs **and** service methods. CSRF on every form and HTMX call, strict CSP (no inline script/style), `X-Frame-Options: DENY`, nosniff, `Referrer-Policy`. Parameterised SQL only. Generic error pages with a reference id. Non-root container; database not published to the host. | `CryptoServiceTest` (tamper detection, no plaintext at rest), `SecurityIT` (lockout, headers, 403, expired session on HTMX and form POSTs with a stale CSRF token), `LoginAttemptServiceIT` (parallel failures, disabled user's session ends), `UserServiceIT`, `OrderControllerIT.unexpectedErrorShowsRefNotDetails` |
| **Time to build** | [`BUILD_LOG.md`](BUILD_LOG.md) has real start/end times per task, recorded as each task was finished (development happened task by task in small commits; this repository publishes the result as one v1.0.0 commit). | See the table below |

## Build time

From [`BUILD_LOG.md`](BUILD_LOG.md) (2026-10-25 and 26, local time):

| Phase | Time |
|---|---|
| Brainstorming → design spec committed | until 12:13 |
| Implementation plan committed | 12:22 |
| Skeleton, schema, Docker (incl. waiting for Docker Desktop to be repaired) | 12:25–12:37 |
| Encryption, security, stock, orders, list query | 12:38–12:56 |
| Orders UI (with manual browser check) | 12:57–13:10 |
| CSV import + import page | 13:11–13:20 |
| Dashboard, admin pages | 13:21–13:28 |
| Demo data, backup drill, README, final verification → first complete version | 13:28–13:52 |
| Independent code review, then fix pass (12 findings, each test-first) | review 13:53–14:05, fixes 14:08–14:28 |
| Container image hardening, Spring Boot 4, PostgreSQL 18 (next day) | 26 Oct, until 10:16 |
| Java 27 | 26 Oct, 10:24–10:39 |

About 3,000 lines of production Java and 2,500 lines of tests, 209 tests in 21 classes.

## Independent review and fixes

An independent review of the first complete version found 2 critical and 10 important issues. Each was reproduced by a failing test
first, then fixed:

- **Load more** sent the filter values twice in a real browser (htmx attribute inheritance) and returned nothing.
- **Expired session:** a POST failed the CSRF check with a silent 403; it now redirects to the login page.
- **CSV import:** an unreadable row no longer lets the rest of its order import without it; Windows-1252 files and
  broken quoting are rejected with a clear message instead of being garbled or giving a 500.
- **Errors during HTMX requests** (400/403/500) are now shown instead of discarded.
- **Uploads** are kept in memory instead of being spooled to a temp file.
- **Concurrency:** allocating different orders that share a SKU no longer gives a false "This order changed".
- **Keyset paging** now really uses the index as a range (verified with EXPLAIN).
- **Order numbers** keep working past ORD-999999 (migration V2).
- **Lockout** counts parallel failures atomically; **disabled users** lose their open sessions.

Known minor items left for later: line numbers in import errors count CSV records rather than physical lines; a
malformed `after` cursor gives a 500 instead of a 400; no per-order audit row for imported orders; `restore.sh`
does not run in a single transaction; CSP could add `base-uri` / `object-src`.

## Data safety: backup and restore drill

```bash
scripts/backup.sh
```

writes `backups/ordermanager-<timestamp>.dump` (`pg_dump` custom format, taken online).

```bash
scripts/restore.sh backups/ordermanager-<timestamp>.dump
```

asks you to type `RESTORE`, stops the app, restores with `pg_restore --clean`, and starts the app again.
Verified drill: 1,000 orders / 1,986 lines / 3,922 audit rows → backup → `delete from order_line; delete from
customer_order;` → restore → 1,000 / 1,986 / 3,922.

Data lives in the named volume `pgdata18`; `docker compose down` keeps it, `docker compose down -v` deletes it.

**Upgrading an older install (PostgreSQL 16 → 18):** a major PostgreSQL version cannot open the old data files, so
this version uses a new volume and the old one (`pgdata`) is left untouched. Move the data with a dump and restore:
run `scripts/backup.sh` *before* updating, then `docker compose up -d --build`, then
`scripts/restore.sh backups/<that file>`. Verified: 1,005 orders, 1,993 lines, 3,923 audit rows, 2 users and
50 SKUs arrived intact.

## Security notes and honest limits

- **HTTPS:** the app speaks plain HTTP on port 8080 locally. In production put a TLS-terminating reverse proxy
  (nginx, Traefik, a cloud load balancer) in front and set `APP_COOKIE_SECURE=true`.
- **Key management:** `APP_ENC_KEY` comes from the environment. In production it belongs in a secrets manager.
  The `v1:` prefix on every ciphertext leaves room for key rotation (not implemented).
- **Import memory** is bounded by the upload limits (5 MB, 20,000 rows), not constant: rows are grouped per order
  in memory before validation.
- **Sessions** are in memory, so restarting the app logs everyone out.
- **Demo seeding** goes through the real services (~25k statements) and takes ~20 s on first start.

## Container image scanning

Both images scan clean with two independent scanners, [Grype](https://github.com/anchore/grype) and
[Trivy](https://trivy.dev) (the same CVE sources Docker Desktop / Docker Scout uses):

```bash
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock anchore/grype:v0.120.1 docker:ordermanager-app
```

```bash
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.75.0 image ordermanager-db
```

| Image | First build | After first cleanup | Now | How |
|---|---|---|---|---|
| `ordermanager-app` | 91 | 28 | **0** | Spring Boot 4.1 (Spring Framework 7) with Tomcat/Jackson patch pins; runtime image built `FROM scratch`: a `jlink`-trimmed Java 27 runtime (22 modules), the musl C library, the app. No shell, no package manager, no OS packages ([`Dockerfile`](Dockerfile)). 129 MB instead of 273 MB |
| `ordermanager-db` | 62 | 3 | **0** | PostgreSQL 18 on Chainguard's minimal image, pinned by digest; runs as the `postgres` user, never root ([`docker/postgres/Dockerfile`](docker/postgres/Dockerfile)) |

The 3 left after the first cleanup were Alpine's `busybox`, which has no Alpine fix yet; the 26 extra app findings
(busybox, coreutils, gnupg) were OS packages Java never uses. Removing the OS layer removes them.

To refresh the database image, pull `cgr.dev/chainguard/postgres:latest`, rescan, and put the new digest in
`docker/postgres/Dockerfile` and `IntegrationTest.POSTGRES_IMAGE` (tests run against the same image).

## Running the tests

The app targets Java 27. Without a local JDK 27, run the suite in a JDK 27 container (Testcontainers inside it
starts PostgreSQL through your Docker):

```bash
scripts/test.sh
```

With JDK 27 installed locally, `./mvnw test` works too. Either way Docker must be running (Testcontainers starts
the same PostgreSQL 18 image as production). About 3 minutes; the CSV benchmark prints a `BENCHMARK …` line.

**Java version:** Java 27 is a feature release, supported until Java 28 ships (March 2027); the current
long-term-support release is Java 25. To go back to an LTS, change `java.version` in `pom.xml` and the two
`FROM` lines in `Dockerfile`.

## Project layout

```
src/main/java/com/ordermanager/
  order/      lifecycle, state machine, list query, controller
  inventory/  SKUs and stock reservation
  importer/   CSV import
  dashboard/  KPIs
  security/   login, users, lockout, encryption, security config
  audit/      audit trail
  admin/      admin pages, demo data
  common/     error handling, formatting, request timing
docs/superpowers/specs/  design spec
docs/superpowers/plans/  implementation plan
```
