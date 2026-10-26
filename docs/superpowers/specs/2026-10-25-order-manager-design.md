# OrderManager — Design Spec

**Date:** 2026-10-25
**Status:** Approved in brainstorming, pending written-spec review

## 1. Purpose and context

A small, end-to-end order management MVP built as a **hiring assessment** for a 3PL logistics company. The audience is the company's technical team, who will read the code and watch a short demo.

The app must visibly demonstrate:

1. Efficient frontend and user functions
2. Efficient code in specific functions
3. Database setup and data-loss prevention
4. Security and encryption
5. Time used to build (traceable, honest)

**Success criteria**

- `docker compose up` starts the whole app from a clean machine with only Docker installed.
- Each of the five criteria maps to concrete code plus a test or measurement a reviewer can run.
- Scope stays MVP: one deployable, two roles, one order lifecycle.

**Out of scope:** multi-tenant client accounts, carrier integrations, label printing, returns, multi-warehouse, REST API for third parties, React or other SPA frontend, TLS termination inside the app.

## 2. Stack and architecture

- **Java 21, Spring Boot 3**, Maven
- **Thymeleaf + HTMX** server-rendered UI (HTMX served from the app, no CDN)
- **PostgreSQL 16**, **Flyway** migrations, Spring Data JPA + `JdbcTemplate` for batch paths
- **Docker Compose**: `app` (multi-stage build, Maven runs inside the build stage) and `db` (named volume `pgdata`, not published to the host)
- Tests: JUnit 5, Testcontainers (PostgreSQL), Spring Security Test

**Architecture: modular monolith, package-by-feature**

```
com.ordermanager
├── order/       OrderController, OrderService, OrderStateMachine, Order, OrderLine, OrderRepository
├── inventory/   Sku, SkuRepository, StockService
├── importer/    CsvImportController, CsvImportService, CsvRowValidator, ImportBatch
├── dashboard/   DashboardController, KpiQueryService
├── security/    SecurityConfig, AppUser, UserService, LoginAttemptService, EncryptedStringConverter, CryptoService
├── audit/       AuditLog, AuditService
├── admin/       AdminController (users, SKUs, stock adjustments)
└── common/      error handling, request timing filter, masking utilities
```

Each package owns its entities, repository, service, and controller. Cross-package calls go through services only (e.g. `OrderService` → `StockService`, `AuditService`), never through another package's repository.

## 3. Data model

All schema changes are Flyway migrations (`V1__init.sql`, ...). Hibernate `ddl-auto=validate`.

| Table | Columns |
|---|---|
| `app_user` | id, username (unique), password_hash, role (`ADMIN`/`OPERATOR`), enabled, failed_attempts, locked_until |
| `sku` | id, code (unique), name, on_hand, reserved, version |
| `customer_order` | id, order_no (unique), external_ref (unique, nullable), status, ship_to_name, ship_to_address_enc, ship_to_phone_enc, created_at, shipped_at, delivered_at, created_by, version, deleted_at |
| `order_line` | id, order_id (FK), sku_id (FK), qty |
| `audit_log` | id, entity, entity_id, action, from_status, to_status, actor, at, details (jsonb) |
| `import_batch` | id, filename, file_sha256 (unique), rows_ok, rows_failed, status, errors (jsonb), duration_ms, created_by, created_at |

**Constraints:** `CHECK (on_hand >= 0)`, `CHECK (reserved >= 0 AND reserved <= on_hand)`, `CHECK (qty > 0)`, `order_line UNIQUE (order_id, sku_id)`.

**Indexes:** `customer_order (status, created_at, id)`, `customer_order (created_at, id)` for keyset paging, `order_line (order_id)`, `sku (code)` (via unique), `audit_log (entity, entity_id)`.

## 4. Data-loss prevention

1. **Transactions.** Every state transition updates order, stock, and audit log in one `@Transactional` unit.
2. **Overselling impossible.** `StockService.reserve` locks all SKU rows for an order in one `SELECT ... FOR UPDATE` ordered by id (prevents deadlocks). DB check constraints are the final guard.
3. **No lost updates.** `@Version` on `Order` and `Sku`. On conflict the UI shows "This order changed — reload" instead of overwriting.
4. **Idempotency.** Unique `external_ref` per order; unique `file_sha256` per import, so re-uploading the same CSV is rejected with a clear message.
5. **Soft delete.** Orders are never hard-deleted. Cancel is a status; `deleted_at` is an admin-only archive flag and archived orders are filtered from lists.
6. **Audit trail.** Every create, transition, stock adjustment, and import writes an `audit_log` row with actor and timestamp.
7. **Backups.** `scripts/backup.sh` runs `pg_dump` inside the db container into `backups/ordermanager-<timestamp>.sql.gz`; `scripts/restore.sh <file>` restores it. README documents a backup → wipe → restore drill.

## 5. Order lifecycle

Statuses: `NEW → ALLOCATED → PICKED → PACKED → SHIPPED → DELIVERED`, plus `CANCELLED` from `NEW`, `ALLOCATED`, `PICKED`, or `PACKED`.

`OrderStateMachine` holds the allowed transitions in an `EnumMap<OrderStatus, Set<OrderStatus>>`. Invalid transitions throw `InvalidTransitionException` (shown in the UI as an inline error).

| Action | Effect |
|---|---|
| allocate | Reserve all lines or none. If any SKU is short, order stays `NEW` and the error lists short SKUs and quantities. |
| pick, pack | Status change + audit only. |
| ship | `on_hand -= qty`, `reserved -= qty` for each line; set `shipped_at`. |
| deliver | Set `delivered_at`. |
| cancel | If stock is reserved (`ALLOCATED`/`PICKED`/`PACKED`), `reserved -= qty`. |
| bulk allocate | Processes `NEW` orders oldest first, one transaction per order; returns counts allocated / short. |

`order_no` format: `ORD-` + zero-padded value from a PostgreSQL sequence.

## 6. Efficient functions (each backed by a test or measurement)

### 6.1 CSV import — `CsvImportService`

CSV format (one row per order line; rows sharing `external_ref` form one order):

```
external_ref,ship_to_name,ship_to_address,ship_to_phone,sku_code,qty
```

- Streaming parse (row by row); memory stays flat regardless of file size.
- Hash file (SHA-256) first; reject duplicates.
- Collect distinct SKU codes and resolve them in **one** query (`WHERE code = ANY(?)`).
- Validate rows; group valid rows by `external_ref`. An order with any invalid line is rejected whole; other orders import.
- Insert orders and lines with `JdbcTemplate.batchUpdate` in batches of 500 (`reWriteBatchedInserts=true`), inside one transaction per batch. Addresses and phones are encrypted before insert.
- Errors reported with line number and reason; summary stored in `import_batch`.
- Limits: 5 MB, 20,000 rows, `.csv` only; file is never written to disk.
- **Benchmark test:** import 10,000 orders against Testcontainers PostgreSQL; record elapsed time in README.

### 6.2 Allocation — `StockService.reserve`

One locking query for all SKUs of the order, sorted by id; check and update in memory; flush once. **Concurrency test:** 20 threads try to allocate orders competing for 5 units; exactly 5 units are reserved, `reserved` never exceeds `on_hand`.

### 6.3 Queries

- Order list uses **keyset pagination** on `(created_at, id)` (page size 50), never `OFFSET`.
- Order detail loads lines with `JOIN FETCH`; list view uses a projection with a line-count subquery. **Query-count test** via Hibernate statistics asserts constant query count regardless of row count.
- Dashboard KPIs use SQL aggregates only: one `GROUP BY status`, one `AVG(shipped_at - created_at)` over the last 30 days, one count of SKUs where `on_hand - reserved = 0`.

## 7. Security and encryption

**Authentication**
- Spring Security form login, session cookie `HttpOnly`, `SameSite=Lax`, `Secure` when behind HTTPS.
- BCrypt cost 12.
- Lockout: 5 failed attempts → locked for 15 minutes (`failed_attempts`, `locked_until`).
- No default credentials. On first start, if no users exist, an `ADMIN` is created from `APP_ADMIN_USERNAME` / `APP_ADMIN_PASSWORD` env vars; startup fails if they are missing. `.env` is git-ignored; `.env.example` is committed.

**Authorization**
- `ADMIN`: everything, including `/admin/**` (users, SKUs, stock adjustments).
- `OPERATOR`: orders, import, dashboard.
- URL rules plus `@PreAuthorize` on service methods. Tests assert `OPERATOR` gets 403 on admin endpoints and anonymous users are redirected to login.

**Encryption at rest**
- `ship_to_address` and `ship_to_phone` encrypted with **AES-256-GCM** via JPA `AttributeConverter` (and the same `CryptoService` in the JDBC import path).
- Random 12-byte IV per value; stored as `v1:` + Base64(IV ‖ ciphertext ‖ tag). The version prefix allows key rotation later.
- Key from `APP_ENC_KEY` env var (Base64, 32 bytes); startup fails if missing or wrong length.
- Tests: raw column contains no plaintext; tampered ciphertext fails decryption; same plaintext encrypts to different ciphertexts.

**Encryption in transit:** app serves HTTP locally. README documents placing a TLS reverse proxy in front for production. Stated honestly as a limitation.

**Web hardening**
- CSRF on all forms; HTMX sends the token via `hx-headers`.
- Thymeleaf auto-escaping.
- Headers: CSP `default-src 'self'` (no inline script), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`.
- Only parameterized SQL.
- Error page shows a generic message and a reference ID; details are logged server-side with addresses and phones masked.

**Container:** non-root user, `eclipse-temurin:21-jre` slim base image, PostgreSQL port not published to the host.

## 8. User interface

Thymeleaf + HTMX + one small CSS file. All JS served locally.

1. **Login**
2. **Orders** — filter by status, search by order number, keyset "Load more". Row action buttons swap only that row via HTMX. "Bulk allocate" button.
3. **Order detail** — lines, decrypted ship-to details, audit timeline, action buttons.
4. **New order** — form with add/remove line rows; inline validation errors.
5. **Import** — upload CSV, see summary (OK / failed, errors per line, duration). Sample CSV download.
6. **Dashboard** — KPI tiles (count per status, average time to ship, stock-outs) and one inline-SVG bar chart of orders by status.
7. **Admin** — users (create, enable/disable, reset password) and SKUs (create, adjust `on_hand`, with audit).

A request-timing filter adds server render time to the page footer (e.g. "rendered in 12 ms") so speed is visible in the demo.

## 9. Error handling

- Domain exceptions (`InvalidTransitionException`, `InsufficientStockException`, `OptimisticLockingFailureException`, `DuplicateImportException`) are mapped by a `@ControllerAdvice` to inline HTMX fragments (status 409/422) or flash messages.
- Validation via Bean Validation on form objects.
- Unexpected errors → generic error page with reference ID, full detail in logs.

## 10. Testing

- **Unit:** state machine (every status pair), `CryptoService`/converter, `CsvRowValidator`.
- **Integration (Testcontainers PostgreSQL):** allocation concurrency, optimistic-lock conflict, import happy path + partial failure + duplicate file, query-count check, role-based access, lockout.
- **Benchmark:** 10,000-order import; result recorded in README.
- **Seed data:** Spring profile `demo` creates 50 SKUs and 1,000 orders spread across statuses.

## 11. Build time evidence

- Small, timestamped commits per phase.
- `BUILD_LOG.md`: start/end time and duration per phase, plus notes.
- README summary table of phases and hours.
- README states the app was built with AI assistance using Claude Code.

| # | Phase | Estimate |
|---|---|---|
| 1 | Skeleton: Maven, Dockerfile, Compose, Flyway V1, health check | 1h |
| 2 | Security: users, login, roles, lockout, headers | 1h |
| 3 | SKUs, stock, encryption | 1h |
| 4 | Orders, state machine, allocation, audit | 2h |
| 5 | CSV import + benchmark | 1.5h |
| 6 | Dashboard + order list paging | 1h |
| 7 | UI polish, seed data, backup scripts, README | 1h |
|   | **Total** | **~8–9h** |

## 12. Repository

- Local: `APPS/OrderManager` (own git repo, branch `main`)
- Remote: `https://github.com/swrodrigues/OrderManager.git`
- Pushes happen only after the user confirms.
