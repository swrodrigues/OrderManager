# OrderManager Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Dockerised order-management MVP for a 3PL hiring assessment that visibly demonstrates efficient UI, efficient code, data-loss prevention, security/encryption, and traceable build time.

**Architecture:** One Spring Boot 3 app (modular monolith, package-by-feature under `com.ordermanager`) serving a Thymeleaf + HTMX UI, backed by PostgreSQL 16 with Flyway migrations. `docker compose up` runs `app` + `db`. Hot paths (CSV import, allocation, list queries) use explicit SQL techniques — JDBC batching, ordered row locks, keyset paging — each proven by a test.

**Tech Stack:** Java 21, Spring Boot 3.5.0, Maven (wrapper), Spring Security 6, Spring Data JPA (Hibernate 6), JdbcTemplate, Flyway, PostgreSQL 16, Thymeleaf, HTMX 2.0.4 (vendored), Apache Commons CSV 1.12.0, JUnit 5, Testcontainers, Docker Compose.

**Spec:** `docs/superpowers/specs/2026-10-25-order-manager-design.md`

## Global Constraints

- Base package `com.ordermanager`; packages exactly: `order`, `inventory`, `importer`, `dashboard`, `security`, `audit`, `admin`, `common`. Cross-package access only through services, never another package's repository.
- No Lombok. DTOs/commands are Java `record`s. Entities have explicit getters/setters; entity `toString()` must never include ship-to address or phone.
- Schema changes only via Flyway (`src/main/resources/db/migration`). `spring.jpa.hibernate.ddl-auto=validate`, `spring.jpa.open-in-view=false`.
- All SQL parameterised. No string concatenation of user input into SQL (dynamic JPQL may append fixed clauses only).
- All timestamps `timestamptz` / `Instant`; "now" comes from an injected `java.time.Clock` bean.
- Templates: no inline `<script>`, no `style="..."` attributes, no `hx-on` (CSP forbids them). JS lives in `static/js/app.js`.
- Passwords: BCrypt cost 12; minimum length 12 characters everywhere a password is set.
- Secrets only from env vars: `APP_ENC_KEY` (Base64, 32 bytes), `APP_ADMIN_USERNAME`, `APP_ADMIN_PASSWORD`, `POSTGRES_PASSWORD`. `.env` is git-ignored; `.env.example` is committed.
- Run tests with `./mvnw` from Git Bash in the repo root. Docker Desktop must be running (Testcontainers).
- Integration tests extend `IntegrationTest` (Task 1) and use profile `test`.
- Every commit message ends with a blank line then `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push without the user's confirmation.
- **Build log:** the step before each task's commit appends one row to `BUILD_LOG.md`: `| <task #> | <task name> | <spec phase #> | <start, local ISO time> | <end> | <minutes> | <notes> |`. Record start time when the task begins. Real times only.

## Review Focus

1. **CSV saved from Excel on a Dutch/EU Windows machine** — UTF-8 BOM, CRLF line endings, browser content type `application/vnd.ms-excel`, possibly `;` delimiter. BOM/CRLF/content type must import normally; a `;` file must fail with a clear "expected comma-separated header" message, not a 500. → tests in Task 8 and Task 9.
2. **Upload over 5 MB** — must show a friendly error on the import page, not a Whitelabel/500 page. → test in Task 9.
3. **Stale page or double-click on a row action** (Allocate pressed twice, or the order changed in another tab) — second request gets HTTP 409 "This order changed — reload", stock is reserved exactly once. → tests in Task 5 and Task 7.
4. **Session expired while the page is open** — an HTMX click must redirect the whole browser to `/login`, not swap the login page into a table row. → test in Task 3.
5. **Non-ASCII ship-to data** (`Rue de l'Église 12, Sint-Truiden`, `Zoë Müller`) — must survive encryption, CSV import, and rendering unchanged. → tests in Task 2 and Task 8.

---

## File Structure

```
OrderManager/
├── pom.xml, mvnw, mvnw.cmd, .mvn/wrapper/maven-wrapper.properties
├── Dockerfile, docker-compose.yml, .dockerignore, .env.example, .gitignore, .gitattributes
├── BUILD_LOG.md, README.md
├── scripts/backup.sh, scripts/restore.sh
└── src/
    ├── main/java/com/ordermanager/
    │   ├── OrderManagerApplication.java
    │   ├── common/     AppProperties, AppConfig (Clock), NotFoundException, Masking,
    │   │               GlobalErrorHandler, RequestTiming, RequestTimingFilter
    │   ├── security/   CryptoService, CryptoConfig, EncryptedStringConverter, Role, AppUser,
    │   │               AppUserRepository, AppUserDetailsService, UserService, LoginAttemptService,
    │   │               AdminBootstrap, SecurityConfig, HtmxAwareEntryPoint, LoginController
    │   ├── audit/      AuditLog, AuditLogRepository, AuditService
    │   ├── inventory/  Sku, SkuRepository, StockService, Shortage, InsufficientStockException
    │   ├── order/      OrderStatus, OrderStateMachine, InvalidTransitionException, CustomerOrder,
    │   │               OrderLine, OrderRepository, NewOrderCommand, OrderService, BulkAllocateResult,
    │   │               OrderRow, OrderPage, OrderFilter, Cursor, OrderQueryService, OrderController,
    │   │               NewOrderForm
    │   ├── importer/   CsvImportService, CsvRowValidator, CsvRow, RowError, ImportResult,
    │   │               DuplicateImportException, ImportRejectedException, CsvImportController
    │   ├── dashboard/  Kpis, KpiQueryService, DashboardController
    │   └── admin/      AdminController, DemoDataSeeder
    ├── main/resources/
    │   ├── application.yml
    │   ├── db/migration/V1__init.sql
    │   ├── static/css/app.css, static/js/htmx.min.js, static/js/app.js, static/sample-orders.csv
    │   └── templates/ layout.html, login.html, error.html, fragments/alert.html,
    │                  orders/{list,rows,detail,new}.html, import/{index,result}.html,
    │                  dashboard/index.html, admin/{users,skus}.html
    └── test/
        ├── java/com/ordermanager/ IntegrationTest.java, TestData.java, + per-package tests
        └── resources/application-test.yml
```

---

### Task 1: Project skeleton, schema, Docker

**Files:**
- Create: `pom.xml`, Maven wrapper files, `.gitignore`, `.gitattributes`, `.dockerignore`, `Dockerfile`, `docker-compose.yml`, `.env.example`, `BUILD_LOG.md`
- Create: `src/main/java/com/ordermanager/OrderManagerApplication.java`, `common/AppProperties.java`, `common/AppConfig.java`
- Create: `src/main/resources/application.yml`, `src/main/resources/db/migration/V1__init.sql`
- Test: `src/test/java/com/ordermanager/IntegrationTest.java`, `TestData.java`, `SchemaIT.java`, `src/test/resources/application-test.yml`

**Interfaces:**
- Produces: `record AppProperties(String encKey, String adminUsername, String adminPassword)` bound to prefix `app` (env `APP_ENC_KEY` etc.), enabled via `@ConfigurationPropertiesScan`.
- Produces: `@Bean Clock clock()` returning `Clock.systemUTC()` in `AppConfig`.
- Produces: `abstract class IntegrationTest` — `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, protected `MockMvc mvc`, `JdbcTemplate jdbc`, `TestData data`; static `PostgreSQLContainer<?>` (`postgres:16-alpine`, `.withUrlParam("reWriteBatchedInserts","true")`) started in a static initializer, annotated `@ServiceConnection`.
- Produces: `@Component TestData` (test scope) with `void truncateAll()` running `TRUNCATE order_line, customer_order, sku, audit_log, import_batch RESTART IDENTITY CASCADE` (does not touch `app_user`). Later tasks add helpers here.

- [ ] **Step 1: Generate the Maven wrapper and write `pom.xml`**

Run (Git Bash): `docker run --rm -v "$PWD":/w -w /w maven:3.9.9-eclipse-temurin-21 mvn -q -N wrapper:wrapper -Dmaven=3.9.9`
Parent `spring-boot-starter-parent:3.5.0`, `java.version` 21. Dependencies now: `spring-boot-starter-web`, `-data-jpa`, `-validation`, `-actuator`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime); test: `spring-boot-starter-test`, `spring-boot-testcontainers`, `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter`. Configure surefire to include `**/*Test.java` and `**/*IT.java`.

`.gitattributes`: `* text=auto eol=lf` and `*.cmd text eol=crlf`. `.gitignore`: `target/`, `.env`, `backups/`, `.idea/`, `*.iml`.

- [ ] **Step 2: Write `V1__init.sql` (exact schema from spec §3)**

```sql
CREATE SEQUENCE order_no_seq;

CREATE TABLE app_user (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','OPERATOR')),
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  failed_attempts INT NOT NULL DEFAULT 0,
  locked_until TIMESTAMPTZ
);

CREATE TABLE sku (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  code VARCHAR(40) NOT NULL UNIQUE,
  name VARCHAR(120) NOT NULL,
  on_hand INT NOT NULL CHECK (on_hand >= 0),
  reserved INT NOT NULL DEFAULT 0 CHECK (reserved >= 0 AND reserved <= on_hand),
  version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE customer_order (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  order_no VARCHAR(20) NOT NULL UNIQUE DEFAULT ('ORD-' || lpad(nextval('order_no_seq')::text, 6, '0')),
  external_ref VARCHAR(64) UNIQUE,
  status VARCHAR(20) NOT NULL CHECK (status IN ('NEW','ALLOCATED','PICKED','PACKED','SHIPPED','DELIVERED','CANCELLED')),
  ship_to_name VARCHAR(120) NOT NULL,
  ship_to_address_enc TEXT NOT NULL,
  ship_to_phone_enc TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  shipped_at TIMESTAMPTZ,
  delivered_at TIMESTAMPTZ,
  created_by VARCHAR(50) NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  deleted_at TIMESTAMPTZ
);
CREATE INDEX ix_order_status_created ON customer_order (status, created_at, id);
CREATE INDEX ix_order_created ON customer_order (created_at, id);

CREATE TABLE order_line (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  order_id BIGINT NOT NULL REFERENCES customer_order(id),
  sku_id BIGINT NOT NULL REFERENCES sku(id),
  qty INT NOT NULL CHECK (qty > 0),
  UNIQUE (order_id, sku_id)
);
CREATE INDEX ix_order_line_order ON order_line (order_id);

CREATE TABLE audit_log (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  entity VARCHAR(40) NOT NULL,
  entity_id BIGINT NOT NULL,
  action VARCHAR(40) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20),
  actor VARCHAR(50) NOT NULL,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  details JSONB
);
CREATE INDEX ix_audit_entity ON audit_log (entity, entity_id);

CREATE TABLE import_batch (
  id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  filename VARCHAR(255) NOT NULL,
  file_sha256 CHAR(64) NOT NULL UNIQUE,
  rows_ok INT NOT NULL DEFAULT 0,
  rows_failed INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL CHECK (status IN ('PROCESSING','COMPLETED','FAILED')),
  errors JSONB,
  duration_ms BIGINT,
  created_by VARCHAR(50) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 3: Write `application.yml` and `application-test.yml`**

`application.yml`: datasource from env (`SPRING_DATASOURCE_URL` etc.); `spring.jpa.hibernate.ddl-auto: validate`; `spring.jpa.open-in-view: false`; `spring.jpa.properties.hibernate.jdbc.batch_size: 500`; `management.endpoints.web.exposure.include: health`; `management.endpoint.health.show-details: never`; `server.error.whitelabel.enabled: false`; `app.enc-key: ${APP_ENC_KEY:}`, `app.admin-username: ${APP_ADMIN_USERNAME:}`, `app.admin-password: ${APP_ADMIN_PASSWORD:}`.
`application-test.yml`: `app.enc-key: AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=`, `app.admin-username: admin`, `app.admin-password: test-admin-password-123`, `spring.jpa.properties.hibernate.generate_statistics: true`.

- [ ] **Step 4: Write the failing test `SchemaIT`**

```java
class SchemaIT extends IntegrationTest {
  @Test void flywayCreatesAllTables() {
    List<String> tables = jdbc.queryForList(
      "select table_name from information_schema.tables where table_schema='public'", String.class);
    assertThat(tables).contains("app_user","sku","customer_order","order_line","audit_log","import_batch");
  }
  @Test void orderNoDefaultsFromSequence() {
    jdbc.update("insert into customer_order(status,ship_to_name,ship_to_address_enc,created_by) values ('NEW','x','x','t')");
    assertThat(jdbc.queryForObject("select order_no from customer_order", String.class)).matches("ORD-\\d{6}");
  }
  @Test void healthIsUp() throws Exception {
    mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
  }
}
```
(`@BeforeEach data.truncateAll()`.)

- [ ] **Step 5: Run it, expect failure**

Run: `./mvnw -q test -Dtest=SchemaIT`
Expected: FAIL (no application class / no migration yet, or compile error).

- [ ] **Step 6: Add `OrderManagerApplication`, `AppProperties`, `AppConfig`; run again**

Run: `./mvnw -q test -Dtest=SchemaIT`
Expected: `Tests run: 3, Failures: 0, Errors: 0`

- [ ] **Step 7: Write Docker files and verify the stack starts**

`Dockerfile`: stage `build` FROM `maven:3.9.9-eclipse-temurin-21` — copy `pom.xml`, `mvn -B -q dependency:go-offline`, copy `src`, `mvn -B -q -DskipTests package`. Stage `run` FROM `eclipse-temurin:21-jre-alpine` — `addgroup -S app && adduser -S app -G app`, copy jar to `/app/app.jar`, `USER app`, `EXPOSE 8080`, `ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]`.
`docker-compose.yml`: service `db` (`postgres:16-alpine`, env `POSTGRES_DB=ordermanager`, `POSTGRES_USER=ordermanager`, `POSTGRES_PASSWORD=${POSTGRES_PASSWORD}`, volume `pgdata:/var/lib/postgresql/data`, healthcheck `pg_isready -U ordermanager`, **no `ports:`**); service `app` (build `.`, `ports: ["8080:8080"]`, `env_file: .env`, `SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/ordermanager?reWriteBatchedInserts=true`, username/password, `depends_on: db: condition: service_healthy`, `restart: unless-stopped`). Named volume `pgdata`.
`.env.example`: the four secrets with placeholder values, `SPRING_PROFILES_ACTIVE=demo`, `APP_COOKIE_SECURE=false`, and a comment: generate the key with `openssl rand -base64 32`.

Run: `cp .env.example .env` (fill real values locally), then `docker compose up -d --build && sleep 30 && curl -s localhost:8080/actuator/health`
Expected: `{"status":"UP"}`. Then `docker compose down`.

- [ ] **Step 8: Create `BUILD_LOG.md` with header and first row**

Header: `| Task | Name | Spec phase | Start | End | Minutes | Notes |` and separator; append this task's row.

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "feat: project skeleton, schema, docker compose"
```

---

### Task 2: Field encryption

**Files:**
- Create: `security/CryptoService.java`, `security/CryptoConfig.java`, `security/EncryptedStringConverter.java`, `common/Masking.java`
- Test: `security/CryptoServiceTest.java`, `common/MaskingTest.java`

**Interfaces:**
- Consumes: `AppProperties.encKey()`.
- Produces: `CryptoService(String base64Key)` with `String encrypt(String plaintext)` and `String decrypt(String stored)`; both return `null` for `null`. Constructor throws `IllegalStateException("APP_ENC_KEY must be Base64 of exactly 32 bytes")` on bad/missing key. `CryptoConfig` exposes it as a bean.
- Produces: `@Component @Converter EncryptedStringConverter implements AttributeConverter<String,String>` (constructor-injected `CryptoService`; Spring Boot's Hibernate bean container wires it). Used via `@Convert(converter = EncryptedStringConverter.class)`.
- Produces: `Masking.mask(String s)` → `null` stays `null`; length ≤ 4 → `"****"`; else first 2 chars + `"****"` + last 2 chars.

- [ ] **Step 1: Write failing tests**

```java
class CryptoServiceTest {
  static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
  CryptoService crypto = new CryptoService(KEY);

  @Test void roundTripsUnicode() {
    String s = "Rue de l'Église 12, Sint-Truiden — Zoë Müller";
    assertThat(crypto.decrypt(crypto.encrypt(s))).isEqualTo(s);
  }
  @Test void storedFormatIsVersionedAndHidesPlaintext() {
    String enc = crypto.encrypt("Kerkstraat 1");
    assertThat(enc).startsWith("v1:").doesNotContain("Kerkstraat");
  }
  @Test void samePlaintextGivesDifferentCiphertext() {
    assertThat(crypto.encrypt("x")).isNotEqualTo(crypto.encrypt("x"));
  }
  @Test void tamperedCiphertextIsRejected() {
    String enc = crypto.encrypt("secret");
    byte[] raw = Base64.getDecoder().decode(enc.substring(3));
    raw[raw.length - 1] ^= 1;
    String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
    assertThatThrownBy(() -> crypto.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
  }
  @Test void rejectsWrongKeyLength() {
    assertThatThrownBy(() -> new CryptoService(Base64.getEncoder().encodeToString(new byte[16])))
      .hasMessageContaining("exactly 32 bytes");
  }
  @Test void nullPassesThrough() { assertThat(crypto.encrypt(null)).isNull(); assertThat(crypto.decrypt(null)).isNull(); }
}
```
`MaskingTest`: `mask("Kerkstraat 1")` = `"Ke**** 1"`; `mask("abcd")` = `"****"`; `mask(null)` = `null`.

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest='CryptoServiceTest,MaskingTest'`
Expected: compile failure (classes missing).

- [ ] **Step 3: Implement**

AES/GCM/NoPadding, 256-bit key, `SecureRandom` 12-byte IV, 128-bit tag, UTF-8. Stored = `"v1:" + Base64(iv ‖ ciphertext+tag)`. Unknown prefix → `IllegalArgumentException`; any `GeneralSecurityException` on decrypt → `IllegalStateException("Decryption failed")`. Reuse one `SecureRandom`; create a `Cipher` per call (not thread-safe).

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest='CryptoServiceTest,MaskingTest'`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: AES-256-GCM field encryption and log masking"
```

---

### Task 3: Users, login, roles, security headers, base layout

**Files:**
- Modify: `pom.xml` (add `spring-boot-starter-security`, `spring-boot-starter-thymeleaf`, `thymeleaf-extras-springsecurity6`; test `spring-security-test`)
- Create: `security/Role.java`, `AppUser.java`, `AppUserRepository.java`, `AppUserDetailsService.java`, `UserService.java`, `LoginAttemptService.java`, `AdminBootstrap.java`, `SecurityConfig.java`, `HtmxAwareEntryPoint.java`, `LoginController.java`
- Create: `templates/layout.html`, `templates/login.html`, `templates/fragments/alert.html`, `static/css/app.css`, `static/js/htmx.min.js`, `static/js/app.js`
- Test: `security/SecurityIT.java`, `security/UserServiceIT.java`

**Interfaces:**
- Produces: `enum Role { ADMIN, OPERATOR }`.
- Produces: `UserService`: `AppUser createUser(String username, String rawPassword, Role role)` (throws `IllegalArgumentException` if password < 12 chars or username taken), `void setEnabled(long id, boolean enabled, String actingUsername)` (throws `IllegalArgumentException("You cannot disable your own account")` when disabling self), `void resetPassword(long id, String rawPassword)`, `List<AppUser> listUsers()`. All `@PreAuthorize("hasRole('ADMIN')")` except as called by `AdminBootstrap`.
- Produces: `LoginAttemptService.onFailure(String username)` / `onSuccess(String username)`; constants `MAX_ATTEMPTS = 5`, `LOCK_DURATION = Duration.ofMinutes(15)`. Wired via `@EventListener` on `AuthenticationFailureBadCredentialsEvent` / `AuthenticationSuccessEvent`. Unknown usernames are ignored.
- Produces: `PasswordEncoder` bean = `new BCryptPasswordEncoder(12)`.
- Produces (test): `TestData.user(String username, String rawPassword, Role role)` inserting directly via `AppUserRepository` + `PasswordEncoder` (bypasses `@PreAuthorize`).
- Produces: layout fragment `layout :: page(title, content)` used as `th:replace="~{layout :: page(~{::title}, ~{::main})}"`; nav shows Orders, New order, Import, Dashboard, and Admin (ADMIN only, via `sec:authorize`); `<div id="flash">` for messages; footer placeholder for render time (filled in Task 7).
- Produces: fragment `fragments/alert :: alert(kind, message)` (`kind` ∈ `ok|error`).

- [ ] **Step 1: Write failing tests (`SecurityIT`)**

```java
@Test void anonymousIsRedirectedToLogin() throws Exception {
  mvc.perform(get("/orders")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
}
@Test void htmxRequestWithoutSessionGets401WithHxRedirect() throws Exception {
  mvc.perform(post("/orders/1/transition").header("HX-Request","true").with(csrf()))
     .andExpect(status().isUnauthorized()).andExpect(header().string("HX-Redirect","/login"));
}
@Test @WithMockUser(roles="OPERATOR") void operatorCannotOpenAdmin() throws Exception {
  mvc.perform(get("/admin/users")).andExpect(status().isForbidden());
}
@Test void bootstrapAdminCanLogIn() throws Exception {
  mvc.perform(formLogin("/login").user("admin").password("test-admin-password-123"))
     .andExpect(authenticated().withRoles("ADMIN"));
}
@Test void fiveFailuresLockTheAccount() throws Exception {
  data.user("lockme", "correct-horse-battery", Role.OPERATOR);
  for (int i = 0; i < 5; i++) mvc.perform(formLogin("/login").user("lockme").password("wrong-password-x"));
  mvc.perform(formLogin("/login").user("lockme").password("correct-horse-battery")).andExpect(unauthenticated());
  jdbc.update("update app_user set locked_until = now() - interval '1 minute' where username='lockme'");
  mvc.perform(formLogin("/login").user("lockme").password("correct-horse-battery")).andExpect(authenticated());
}
@Test void securityHeadersArePresent() throws Exception {
  mvc.perform(get("/login"))
     .andExpect(header().string("Content-Security-Policy",
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'"))
     .andExpect(header().string("X-Frame-Options","DENY"))
     .andExpect(header().string("X-Content-Type-Options","nosniff"))
     .andExpect(header().string("Referrer-Policy","same-origin"));
}
@Test void loginErrorIsGeneric() throws Exception {
  mvc.perform(get("/login").param("error","")).andExpect(content().string(containsString("Invalid username or password, or account temporarily locked.")));
}
```
`UserServiceIT`: `passwordsAreBcryptCost12` (stored hash starts with `$2a$12$`), `rejectsShortPassword` (11 chars → `IllegalArgumentException`), `adminCannotDisableSelf`.

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest='SecurityIT,UserServiceIT'`
Expected: FAIL / compile errors.

- [ ] **Step 3: Implement security package**

`SecurityConfig`: `/login`, `/css/**`, `/js/**`, `/actuator/health` permitAll; `/admin/**` hasRole ADMIN; everything else authenticated; `formLogin` with `loginPage("/login")`, `defaultSuccessUrl("/orders", true)`; logout POST `/logout`; `exceptionHandling.authenticationEntryPoint(new HtmxAwareEntryPoint())`; headers per test above; `@EnableMethodSecurity`. `HtmxAwareEntryPoint`: if header `HX-Request: true` → 401 + `HX-Redirect: /login`, else redirect to `/login`.
`AppUserDetailsService`: maps to `User.withUsername(..).password(hash).roles(role.name()).disabled(!enabled).accountLocked(lockedUntil != null && lockedUntil.isAfter(clock.instant()))`.
`AdminBootstrap implements ApplicationRunner`: if `app_user` empty, require `adminUsername`/`adminPassword` non-blank (else throw `IllegalStateException("APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD must be set on first start")`) and create the ADMIN.
Session cookie in `application.yml`: `server.servlet.session.cookie.http-only: true`, `same-site: lax`, `secure: ${APP_COOKIE_SECURE:false}`, `server.servlet.session.timeout: 30m`.

- [ ] **Step 4: Templates and static assets**

Download HTMX once: `curl -sSfL https://unpkg.com/htmx.org@2.0.4/dist/htmx.min.js -o src/main/resources/static/js/htmx.min.js`.
`layout.html` head: `<meta name="htmx-config" content='{"includeIndicatorStyles":false,"responseHandling":[{"code":"204","swap":false},{"code":"[23]..","swap":true},{"code":"(409|422)","swap":true,"error":false},{"code":"[45]..","swap":false,"error":true}]}'>`; `<body>` carries `hx-headers` JSON `{"X-CSRF-TOKEN": "<_csrf.token>"}` via `th:attr`. Scripts: `htmx.min.js`, `app.js` (empty for now). Login page: username, password, CSRF hidden input (from `th:action`), generic error text from test.

- [ ] **Step 5: Run, expect pass**

Run: `./mvnw -q test -Dtest='SecurityIT,UserServiceIT'`
Expected: `Failures: 0, Errors: 0`. (`/orders` 302 and `/admin/users` 403 work before those controllers exist because security runs first.)

- [ ] **Step 6: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: users, login lockout, roles, security headers, base layout"
```

---

### Task 4: Audit trail and inventory stock service

**Files:**
- Create: `audit/AuditLog.java`, `audit/AuditLogRepository.java`, `audit/AuditService.java`
- Create: `inventory/Sku.java`, `inventory/SkuRepository.java`, `inventory/StockService.java`, `inventory/Shortage.java`, `inventory/InsufficientStockException.java`
- Modify: `TestData.java` (add `long sku(String code, int onHand)` inserting via JDBC and returning id)
- Test: `inventory/StockServiceIT.java`

**Interfaces:**
- Produces: `AuditService.record(String entity, long entityId, String action, String fromStatus, String toStatus, Map<String,Object> details)` — actor = current `Authentication` name or `"system"`; joins the caller's transaction (`Propagation.MANDATORY`). `List<AuditLog> history(String entity, long entityId)` ordered by `at, id`. `details` mapped with `@JdbcTypeCode(SqlTypes.JSON)`.
- Produces: `Sku` entity (`id, code, name, onHand, reserved, @Version version`; `int available()`).
- Produces: `SkuRepository`: `@Lock(PESSIMISTIC_WRITE) @Query("select s from Sku s where s.id in :ids order by s.id") List<Sku> lockAllByIdIn(Collection<Long> ids)`; `List<Sku> findByCodeIn(Collection<String> codes)`; `Optional<Sku> findByCode(String code)`.
- Produces: `StockService` (all `@Transactional(propagation = MANDATORY)` except `createSku`/`adjustOnHand`/`listSkus`):
  - `void reserve(Map<Long,Integer> qtyBySkuId)` — locks, checks all, throws `InsufficientStockException(List<Shortage>)` before changing anything, else `reserved += qty`.
  - `void release(Map<Long,Integer> qtyBySkuId)` — `reserved -= qty`.
  - `void consume(Map<Long,Integer> qtyBySkuId)` — `onHand -= qty; reserved -= qty`.
  - `Sku createSku(String code, String name, int onHand)` and `Sku adjustOnHand(long skuId, int delta, String reason)` (audit action `STOCK_ADJUST`, details `{delta, reason}`; throws `IllegalArgumentException` if result < reserved or reason blank) — both `@Transactional` and `@PreAuthorize("hasRole('ADMIN')")`.
  - `List<Sku> listSkus()` (ordered by code) and `Map<String,Sku> findByCodes(Collection<String> codes)` (one query) — read-only, any authenticated user.
- Produces: `record Shortage(String skuCode, int requested, int available)`; `InsufficientStockException.getShortages()`.

- [ ] **Step 1: Write failing tests**

```java
@Test void reserveIsAllOrNothing() {          // run inside TransactionTemplate
  long a = data.sku("A", 5), b = data.sku("B", 1);
  assertThatThrownBy(() -> tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 2, b, 3))))
    .isInstanceOf(InsufficientStockException.class)
    .satisfies(e -> assertThat(((InsufficientStockException) e).getShortages())
        .containsExactly(new Shortage("B", 3, 1)));
  assertThat(reserved(a)).isZero();
}
@Test void twentyThreadsCannotOversellFiveUnits() throws Exception {
  long a = data.sku("A", 5);
  // 20 threads, CountDownLatch start gate, each: tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 1)))
  // count successes and InsufficientStockException
  assertThat(successes).isEqualTo(5); assertThat(failures).isEqualTo(15);
  assertThat(reserved(a)).isEqualTo(5);
}
@Test void oppositeLineOrderDoesNotDeadlock() throws Exception {
  long a = data.sku("A", 1000), b = data.sku("B", 1000);
  // 10 threads x 20 iterations; half reserve Map.of(a,1,b,1) built as LinkedHashMap a→b, half b→a
  // expect no exception at all; reserved(a) == reserved(b) == 200
}
@Test void consumeReducesOnHandAndReserved() { /* reserve 2 of 5, consume 2 → on_hand 3, reserved 0 */ }
@Test @WithMockUser(roles="ADMIN") void adjustWritesAudit() { /* adjustOnHand(+10,"cycle count") → audit row STOCK_ADJUST with details.delta=10 */ }
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=StockServiceIT`
Expected: compile errors.

- [ ] **Step 3: Implement audit and inventory**

`reserve` iterates locked SKUs (already id-ordered by the query) — the lock order is what prevents deadlocks; do not iterate the input map to lock.

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest=StockServiceIT`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: audit trail and lock-ordered stock reservation"
```

---

### Task 5: Order domain — state machine, entities, OrderService

**Files:**
- Create: `order/OrderStatus.java`, `OrderStateMachine.java`, `InvalidTransitionException.java`, `CustomerOrder.java`, `OrderLine.java`, `OrderRepository.java`, `NewOrderCommand.java`, `OrderService.java`, `BulkAllocateResult.java`; `common/NotFoundException.java`
- Modify: `TestData.java` (add `long order(String externalRef, Map<String,Integer> qtyBySkuCode)` that calls `OrderService.create` as user `"test"`)
- Test: `order/OrderStateMachineTest.java`, `order/OrderServiceIT.java`

**Interfaces:**
- Consumes: `StockService.reserve/release/consume/findByCodes`, `AuditService.record`, `EncryptedStringConverter`.
- Produces: `enum OrderStatus { NEW, ALLOCATED, PICKED, PACKED, SHIPPED, DELIVERED, CANCELLED }`.
- Produces: `OrderStateMachine` (static or `@Component`): `boolean canTransition(OrderStatus from, OrderStatus to)`, `void check(OrderStatus from, OrderStatus to)` (throws `InvalidTransitionException`), `Set<OrderStatus> next(OrderStatus from)` (for UI buttons; `EnumSet`, ordered by enum).
- Produces: `CustomerOrder` entity mapped to `customer_order` (`orderNo` is `@Generated(event = EventType.INSERT) @Column(insertable=false, updatable=false)`; `shipToAddress`/`shipToPhone` use `@Convert(converter = EncryptedStringConverter.class)` on columns `ship_to_address_enc`/`ship_to_phone_enc`; `@Version long version`; `@OneToMany(mappedBy="order", cascade=ALL, orphanRemoval=true) List<OrderLine> lines`). `Map<Long,Integer> qtyBySkuId()`.
- Produces: `OrderLine` (`@ManyToOne(fetch=LAZY) CustomerOrder order`, `@ManyToOne(fetch=LAZY) Sku sku`, `int qty`).
- Produces: `OrderRepository`: `@Query("select distinct o from CustomerOrder o left join fetch o.lines l left join fetch l.sku where o.id = :id and o.deletedAt is null") Optional<CustomerOrder> findDetail(long id)`; `@Query("select o.id, o.version from CustomerOrder o where o.status = 'NEW' and o.deletedAt is null order by o.createdAt, o.id") List<Object[]> findNewIdsOldestFirst()`.
- Produces: `record NewOrderCommand(String externalRef, String shipToName, String shipToAddress, String shipToPhone, List<Line> lines) { record Line(String skuCode, int qty) {} }` with Bean Validation (`shipToName` `@NotBlank @Size(max=120)`, `shipToAddress` `@NotBlank @Size(max=500)`, `shipToPhone` `@Size(max=40)`, `lines` `@NotEmpty`, `qty` `@Min(1) @Max(100000)`).
- Produces: `OrderService`:
  - `CustomerOrder create(NewOrderCommand cmd)` — unknown SKU code → `IllegalArgumentException("Unknown SKU: X")`; duplicate SKU in lines → `IllegalArgumentException("Duplicate SKU in order: X")`; status `NEW`; audit `CREATE`.
  - `CustomerOrder transition(long orderId, OrderStatus target, long expectedVersion)` — loads with `findDetail` (`NotFoundException` if absent); if `order.getVersion() != expectedVersion` → `throw new ObjectOptimisticLockingFailureException(CustomerOrder.class, orderId)`; `stateMachine.check`; side effects: `ALLOCATED` → `reserve`; `SHIPPED` → `consume`, `shippedAt = now`; `DELIVERED` → `deliveredAt = now`; `CANCELLED` from `ALLOCATED|PICKED|PACKED` → `release`; audit `TRANSITION` with from/to; flush before return so the new version is visible.
  - `BulkAllocateResult bulkAllocate()` — not transactional itself; per order a `TransactionTemplate` calling `transition(id, ALLOCATED, version)`; counts `InsufficientStockException` as short. `record BulkAllocateResult(int allocated, int shortOfStock)`.
  - `CustomerOrder get(long id)` → `findDetail` or `NotFoundException`.

- [ ] **Step 1: Write failing tests**

```java
class OrderStateMachineTest {
  static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
    NEW, Set.of(ALLOCATED, CANCELLED), ALLOCATED, Set.of(PICKED, CANCELLED),
    PICKED, Set.of(PACKED, CANCELLED), PACKED, Set.of(SHIPPED, CANCELLED),
    SHIPPED, Set.of(DELIVERED), DELIVERED, Set.of(), CANCELLED, Set.of());
  static Stream<Arguments> allPairs() { /* every (from, to) in OrderStatus x OrderStatus */ }
  @ParameterizedTest @MethodSource("allPairs")
  void matchesSpec(OrderStatus from, OrderStatus to) {
    assertThat(OrderStateMachine.canTransition(from, to)).isEqualTo(ALLOWED.get(from).contains(to));
  }
}
```
`OrderServiceIT` (with `@WithMockUser(username="op", roles="OPERATOR")`):
```java
@Test void createEncryptsShipToAtRest() {
  long id = service.create(cmd("EXT-1", "Rue de l'Église 12", "A", 2)).getId();
  String raw = jdbc.queryForObject("select ship_to_address_enc from customer_order where id=?", String.class, id);
  assertThat(raw).startsWith("v1:").doesNotContain("Église");
  assertThat(service.get(id).getShipToAddress()).isEqualTo("Rue de l'Église 12");
}
@Test void fullLifecycleMovesStock() { /* sku A on_hand 10; order qty 3: allocate→reserved 3; pick, pack, ship→on_hand 7, reserved 0, shippedAt set; deliver→deliveredAt set; audit history has CREATE + 5 TRANSITION rows */ }
@Test void allocateShortLeavesOrderNew() { /* on_hand 1, qty 3 → InsufficientStockException; status NEW; reserved 0 */ }
@Test void cancelAfterAllocateReleasesStock() { /* reserved back to 0 */ }
@Test void invalidTransitionRejected() { /* NEW → SHIPPED → InvalidTransitionException */ }
@Test void staleVersionIsRejectedAndStockReservedOnce() {
  var o = service.get(orderId); long v = o.getVersion();
  service.transition(orderId, ALLOCATED, v);
  assertThatThrownBy(() -> service.transition(orderId, ALLOCATED, v))
    .isInstanceOf(ObjectOptimisticLockingFailureException.class);
  assertThat(reserved("A")).isEqualTo(3);
}
@Test void bulkAllocateOldestFirst() { /* on_hand 5; three NEW orders qty 2 each → allocated 2, shortOfStock 1; the newest stays NEW */ }
@Test void duplicateExternalRefRejected() { /* second create "EXT-1" → DataIntegrityViolationException */ }
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest='OrderStateMachineTest,OrderServiceIT'`
Expected: compile errors.

- [ ] **Step 3: Implement**

Transitions stored as `EnumMap<OrderStatus, EnumSet<OrderStatus>>`, values per the test's `ALLOWED` map.

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest='OrderStateMachineTest,OrderServiceIT'`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: order lifecycle state machine with stock side effects and optimistic locking"
```

---

### Task 6: Order list query (keyset paging, no N+1)

**Files:**
- Create: `order/OrderRow.java`, `OrderPage.java`, `OrderFilter.java`, `Cursor.java`, `OrderQueryService.java`
- Test: `order/OrderQueryServiceIT.java`

**Interfaces:**
- Produces: `record OrderFilter(OrderStatus status, String orderNoPrefix)` (both nullable; blank prefix treated as null).
- Produces: `record Cursor(Instant createdAt, long id)` with `String encode()` (`<epochMicros>_<id>`) and `static Cursor decode(String s)` (`null`/blank → `null`; malformed → `IllegalArgumentException`).
- Produces: `record OrderRow(long id, String orderNo, OrderStatus status, String shipToName, long lineCount, Instant createdAt, long version)`.
- Produces: `record OrderPage(List<OrderRow> rows, Cursor next)` (`next` null when no more).
- Produces: `OrderQueryService.page(OrderFilter filter, Cursor after, int size) : OrderPage` — `PAGE_SIZE = 50` constant; newest first.

- [ ] **Step 1: Write failing tests**

```java
@Test void keysetPagesCoverAllRowsWithoutOverlap() {
  // create 120 orders; page(filter none, null, 50) x3
  // assert sizes 50, 50, 20; third next == null; union of ids has 120 distinct; strictly descending (createdAt, id)
}
@Test void filtersByStatusAndPrefix() { /* 3 NEW, 2 ALLOCATED → status ALLOCATED gives 2; prefix of one orderNo gives that one */ }
@Test void queryCountIsConstant() {
  Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
  // create 10 orders with 3 lines each
  stats.clear(); query.page(new OrderFilter(null, null), null, 50);
  long small = stats.getPrepareStatementCount();
  // create 90 more
  stats.clear(); query.page(new OrderFilter(null, null), null, 50);
  assertThat(stats.getPrepareStatementCount()).isEqualTo(small).isEqualTo(1);
}
@Test void lineCountIsCorrect() { /* order with 3 lines → lineCount 3 */ }
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=OrderQueryServiceIT`
Expected: compile errors.

- [ ] **Step 3: Implement**

A single JPQL constructor-expression query: `select new com.ordermanager.order.OrderRow(o.id, o.orderNo, o.status, o.shipToName, (select count(l) from OrderLine l where l.order = o), o.createdAt, o.version) from CustomerOrder o where o.deletedAt is null`, then append fixed clauses only for present filters (`and o.status = :status`, `and o.orderNo like :prefix` with value `prefix + "%"`) and for the cursor (`and (o.createdAt < :c or (o.createdAt = :c and o.id < :id))`), `order by o.createdAt desc, o.id desc`, `setMaxResults(size + 1)`; the extra row decides `next`. Cursor timestamps truncated to microseconds (PostgreSQL precision).

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest=OrderQueryServiceIT`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: keyset-paged order list with constant query count"
```

---

### Task 7: Orders UI, error handling, render timing

**Files:**
- Create: `order/OrderController.java`, `order/NewOrderForm.java`, `common/GlobalErrorHandler.java`, `common/RequestTiming.java`, `common/RequestTimingFilter.java`
- Create: `templates/orders/list.html`, `orders/rows.html`, `orders/detail.html`, `orders/new.html`, `templates/error.html`
- Modify: `templates/layout.html` (footer render time), `static/js/app.js` (add/remove line rows), `static/css/app.css`
- Test: `order/OrderControllerIT.java`

**Interfaces:**
- Consumes: `OrderService`, `OrderQueryService`, `OrderStateMachine.next`, `AuditService.history`, `StockService.listSkus` (for the SKU dropdown).
- Routes:
  - `GET /` → redirect `/orders`.
  - `GET /orders?status=&q=&after=` → `orders/list` (full page); with `HX-Request` → `orders/rows :: rows` (rows + new "Load more" button).
  - `GET /orders/new` → `orders/new`; `POST /orders` (form `NewOrderForm` → `NewOrderCommand`) → redirect `/orders/{id}`; validation/`IllegalArgumentException` → re-render form with errors (422).
  - `GET /orders/{id}` → `orders/detail` (decrypted ship-to, lines, audit timeline, action buttons from `next(status)`).
  - `POST /orders/{id}/transition` params `to`, `version`, `view` (`row`|`detail`) → `orders/rows :: row` or `orders/detail :: panel`.
  - `POST /orders/bulk-allocate` → `fragments/alert :: alert('ok', 'Allocated X, short of stock Y')` + header `HX-Trigger: orders-changed` (the table re-fetches on `orders-changed from:body`).
- `GlobalErrorHandler` (`@ControllerAdvice`): for `HX-Request` requests returns `fragments/alert :: alert('error', msg)` with headers `HX-Retarget: #flash`, `HX-Reswap: innerHTML`:
  - `ObjectOptimisticLockingFailureException` → 409, `"This order changed — reload"`
  - `InvalidTransitionException` → 409, `"Order cannot move from X to Y"`
  - `InsufficientStockException` → 422, `"Not enough stock: SKU-A (need 3, have 1), ..."`
  - `NotFoundException` → 404 page.
  - Any other exception → `error.html` (500) with `ref` = first 8 chars of a random UUID; `log.error("ref={}", ref, ex)`. Non-HTMX requests get `error.html` for all of these with the message.
- `RequestTimingFilter` stores `System.nanoTime()` in request attribute `om.start`; `@Component("timing") RequestTiming.elapsedMs()` reads it; footer: `rendered in <span th:text="${@timing.elapsedMs()}">0</span> ms`.

- [ ] **Step 1: Write failing tests**

```java
@WithMockUser(username="op", roles="OPERATOR")
class OrderControllerIT extends IntegrationTest {
  @Test void listRendersAndHtmxReturnsRowsOnly() { /* GET /orders → contains "<html"; with HX-Request → no "<html", contains order no */ }
  @Test void transitionReturnsUpdatedRow() {
    // POST /orders/{id}/transition to=ALLOCATED version=0 view=row, HX-Request, csrf
    // → 200, body contains "ALLOCATED" and does not contain "<html"
  }
  @Test void staleVersionGives409Message() {
    // allocate once with version 0, then repeat with version 0
    // → 409, header HX-Retarget "#flash", body contains "This order changed — reload"
  }
  @Test void shortStockGives422WithSkuList() { /* → 422, body contains "Not enough stock: A (need 3, have 1)" */ }
  @Test void createOrderRedirectsToDetail() { /* POST /orders with fields + lines[0].skuCode/qty → 3xx /orders/{id} */ }
  @Test void createOrderValidationErrorRerendersForm() { /* blank shipToName → 422, contains field error */ }
  @Test void detailShowsDecryptedAddressAndAudit() { /* contains "Rue de l'Église 12" and "CREATE" */ }
  @Test void footerShowsRenderTime() { /* GET /orders → body matches "rendered in \\d+ ms" */ }
  @Test void unexpectedErrorShowsRefNotStackTrace() { /* GET /orders/abc (type mismatch) → body contains "Reference", not "Exception" */ }
}
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=OrderControllerIT`
Expected: FAIL (404s / missing templates).

- [ ] **Step 3: Implement controller, handler, filter, templates**

Row buttons: `hx-post="/orders/{id}/transition"` with `hx-vals` JSON `{to, version, view:"row"}`, `hx-target="closest tr"`, `hx-swap="outerHTML"`, `hx-disabled-elt="this"`. Status filter and search: `hx-get="/orders"`, `hx-trigger="change, keyup changed delay:300ms"`, `hx-target="#order-rows"`. New-order line rows: `app.js` clones a `<template>` row and handles remove via event delegation; field names `lines[i].skuCode`, `lines[i].qty` re-indexed on submit.

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest=OrderControllerIT`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: Manual check in browser**

Run: `docker compose up -d --build`, log in as admin, create an order, step it through to DELIVERED, open the same order in two tabs and allocate in both. Expected: second tab shows "This order changed — reload"; no CSP errors in the browser console.

- [ ] **Step 6: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: orders UI with HTMX row updates and conflict handling"
```

---

### Task 8: CSV import service

**Files:**
- Modify: `pom.xml` (add `org.apache.commons:commons-csv:1.12.0`)
- Create: `importer/CsvRow.java`, `RowError.java`, `CsvRowValidator.java`, `ImportResult.java`, `DuplicateImportException.java`, `ImportRejectedException.java`, `CsvImportService.java`
- Test: `importer/CsvRowValidatorTest.java`, `importer/CsvImportServiceIT.java`, `importer/CsvImportBenchmarkIT.java`

**Interfaces:**
- Consumes: `CryptoService.encrypt`, `StockService.findByCodes`, `AuditService.record`, `JdbcTemplate`.
- Produces: header constant `CsvImportService.HEADER = List.of("external_ref","ship_to_name","ship_to_address","ship_to_phone","sku_code","qty")`; `MAX_ROWS = 20_000`; `BATCH_SIZE = 500`.
- Produces: `record CsvRow(long lineNo, String externalRef, String shipToName, String shipToAddress, String shipToPhone, String skuCode, String qtyText)`; `record RowError(long lineNo, String externalRef, String message)`.
- Produces: `CsvRowValidator.validate(CsvRow row, Set<String> knownSkuCodes) : List<String>` — rules: `external_ref` required, ≤ 64; `ship_to_name` required, ≤ 120; `ship_to_address` required, ≤ 500; `ship_to_phone` optional, matches `[+0-9 ()-]{5,40}`; `sku_code` must be in `knownSkuCodes` (`"Unknown SKU: X"`); `qty` integer 1–100000.
- Produces: `CsvImportService.importCsv(String filename, InputStream in, String username) : ImportResult`; `record ImportResult(long batchId, int ordersImported, int ordersRejected, int rowsOk, int rowsFailed, List<RowError> errors, long durationMs)`. Throws `ImportRejectedException(String message)` for whole-file problems and `DuplicateImportException` for a repeated file hash.

- [ ] **Step 1: Write failing tests**

`CsvRowValidatorTest`: one test per rule above with exact messages (`"external_ref is required"`, `"qty must be a whole number between 1 and 100000"`, `"Unknown SKU: ZZZ"`, `"ship_to_phone is not a valid phone number"`).
`CsvImportServiceIT`:
```java
@Test void importsValidOrdersAndReportsBadOnes() {
  // SKUs A, B exist. CSV: EXT-1 two lines (A,B); EXT-2 one line with sku ZZZ; EXT-3 one line qty 0
  ImportResult r = service.importCsv("t.csv", csv(...), "op");
  assertThat(r.ordersImported()).isEqualTo(1); assertThat(r.ordersRejected()).isEqualTo(2);
  assertThat(r.rowsOk()).isEqualTo(2); assertThat(r.rowsFailed()).isEqualTo(2);
  assertThat(r.errors()).extracting(RowError::lineNo).containsExactly(4L, 5L);
  // EXT-1 has 2 order_line rows, status NEW, address column starts with "v1:"
}
@Test void oneBadLineRejectsWholeOrder() { /* EXT-1 line A ok, line ZZZ bad → EXT-1 not imported, rowsFailed 2 */ }
@Test void sameFileTwiceIsRejected() { /* second call → DuplicateImportException; customer_order count unchanged */ }
@Test void existingExternalRefIsRejected() { /* EXT-1 created earlier → error "external_ref EXT-1 already exists" */ }
@Test void conflictingShipToWithinOrderIsRejected() { /* two EXT-1 rows, different ship_to_name → error */ }
@Test void excelFileWithBomAndCrlfImports() { /* bytes EF BB BF + header + rows joined by "\r\n" → imported */ }
@Test void semicolonFileGivesHeaderError() {
  assertThatThrownBy(() -> service.importCsv("t.csv", csv("external_ref;ship_to_name;..."), "op"))
    .isInstanceOf(ImportRejectedException.class)
    .hasMessageContaining("expected comma-separated header: external_ref,ship_to_name,ship_to_address,ship_to_phone,sku_code,qty");
}
@Test void tooManyRowsRejected() { /* 20,001 data rows → ImportRejectedException "max 20000 rows" */ }
@Test void emptyFileRejected() { /* "" → ImportRejectedException "File is empty" */ }
@Test void unicodeShipToSurvives() { /* "Zoë Müller", "Rue de l'Église 12" → read back via OrderService.get */ }
@Test void writesImportBatchAndOneAuditRow() { /* import_batch status COMPLETED, duration_ms > 0; one audit_log row action IMPORT */ }
```
`CsvImportBenchmarkIT`: 50 SKUs; generate 10,000 orders × 2 lines (20,000 rows); time `importCsv`; assert `ordersImported == 10_000`, `order_line` count 20,000, and duration < 30 s; print `BENCHMARK import 10000 orders / 20000 rows: <ms> ms` to stdout.

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest='CsvRowValidatorTest,CsvImportServiceIT'`
Expected: compile errors.

- [ ] **Step 3: Implement**

Algorithm (one method per step, all in `CsvImportService`):
1. Wrap input: `DigestInputStream(SHA-256)` → `BOMInputStream` → `InputStreamReader(UTF-8)`; parse with `CSVFormat.DEFAULT` (comma, CRLF/LF tolerant), streaming record by record.
2. Header must equal `HEADER` (trimmed, case-insensitive) else `ImportRejectedException("expected comma-separated header: " + String.join(",", HEADER))`. No records at all → `"File is empty"`. Over `MAX_ROWS` → `"File has more than 20000 rows (max 20000 rows)"`.
3. Collect rows grouped by `externalRef` in a `LinkedHashMap` (memory bounded by the 5 MB / 20,000-row caps).
4. Finish the digest; insert `import_batch` (status `PROCESSING`) — unique-violation on `file_sha256` → `DuplicateImportException`.
5. One query for SKUs: `StockService.findByCodes(distinct codes)`. One query for existing refs: `select external_ref from customer_order where external_ref = any(?)` (bind `String[]`).
6. Validate per row; reject an order if any row fails, if its ref already exists, if ship-to fields differ between its rows, or if a SKU repeats within it.
7. For valid orders, in chunks of `BATCH_SIZE` orders, each chunk in its own `TransactionTemplate`: pre-allocate ids with `select nextval(pg_get_serial_sequence('customer_order','id')) from generate_series(1, ?)`; `jdbc.batchUpdate` insert orders (id, external_ref, status `NEW`, ship_to_name, encrypted address/phone, created_by); `jdbc.batchUpdate` insert lines.
8. Update `import_batch` (counts, `errors` JSON capped at first 200, `COMPLETED`, duration); one audit row (`entity "import_batch"`, action `IMPORT`, details counts).

- [ ] **Step 4: Run, expect pass (including the benchmark)**

Run: `./mvnw -q test -Dtest='CsvRowValidatorTest,CsvImportServiceIT,CsvImportBenchmarkIT'`
Expected: `Failures: 0, Errors: 0` and a `BENCHMARK ...` line; copy the ms value into the BUILD_LOG notes.

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: streaming CSV import with JDBC batching and per-order validation"
```

---

### Task 9: Import page

**Files:**
- Create: `importer/CsvImportController.java`, `templates/import/index.html`, `templates/import/result.html`, `static/sample-orders.csv`
- Modify: `application.yml` (`spring.servlet.multipart.max-file-size: 5MB`, `max-request-size: 6MB`), `common/GlobalErrorHandler.java` (map `MaxUploadSizeExceededException`, `ImportRejectedException`, `DuplicateImportException`)
- Test: `importer/CsvImportControllerIT.java`, `importer/CsvImportUploadLimitIT.java`

**Interfaces:**
- Consumes: `CsvImportService.importCsv`, `ImportResult`.
- Routes: `GET /import` → `import/index`; `POST /import` (multipart `file`, `hx-post`, `hx-encoding="multipart/form-data"`, target `#import-result`) → `import/result :: result`; `GET /sample-orders.csv` served as static.
- Accepted uploads: filename ends with `.csv` (case-insensitive) and content type in `text/csv`, `application/vnd.ms-excel`, `text/plain`, `application/octet-stream`; else 422 `"Please upload a .csv file"`.
- Error mapping: `MaxUploadSizeExceededException` → 422 `"File is larger than 5 MB"`; `ImportRejectedException` → 422 with its message; `DuplicateImportException` → 409 `"This file was already imported"`. For non-HTMX posts, re-render `import/index` with the message.

- [ ] **Step 1: Write failing tests**

```java
@WithMockUser(username="op", roles="OPERATOR")
@Test void uploadShowsSummary() { /* multipart valid file → 200, contains "1 orders imported" and "Line 4" */ }
@Test void excelContentTypeAccepted() { /* contentType application/vnd.ms-excel → 200 */ }
@Test void nonCsvRejected() { /* "orders.xlsx" → 422 "Please upload a .csv file" */ }
@Test void oversizeFileShowsFriendlyError() { /* 6 MB file → 422 body contains "File is larger than 5 MB", no "Whitelabel" */ }
@Test void duplicateFileGives409() { /* upload twice → 409 "This file was already imported" */ }
```
`MockMvc` does not enforce multipart limits, so `oversizeFileShowsFriendlyError` lives in a separate class `CsvImportUploadLimitIT` using `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `TestRestTemplate`: log in through `POST /login` (fetch the login page first for the CSRF token and session cookie), then post the 6 MB file.

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest='CsvImportControllerIT,CsvImportUploadLimitIT'`
Expected: FAIL (404).

- [ ] **Step 3: Implement controller and templates**

Result fragment: counts, duration (`took 812 ms`), table of first 200 errors (line, external_ref, message), link to orders list filtered `status=NEW`. Sample CSV: 5 orders, 7 rows, using SKUs that the demo seeder creates (`SKU-001`…).

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest='CsvImportControllerIT,CsvImportUploadLimitIT'`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: import page with upload limits and error summary"
```

---

### Task 10: Dashboard

**Files:**
- Create: `dashboard/Kpis.java`, `dashboard/KpiQueryService.java`, `dashboard/DashboardController.java`, `templates/dashboard/index.html`
- Test: `dashboard/KpiQueryServiceIT.java`, `dashboard/DashboardControllerIT.java`

**Interfaces:**
- Produces: `record Kpis(Map<OrderStatus, Long> countByStatus, Duration avgTimeToShip, long stockOuts)` — `countByStatus` is an `EnumMap` with every status present (zero-filled); `avgTimeToShip` null when no shipped orders in the window.
- Produces: `KpiQueryService.kpis() : Kpis` using exactly three SQL queries via `JdbcTemplate`:
  1. `select status, count(*) from customer_order where deleted_at is null group by status`
  2. `select extract(epoch from avg(shipped_at - created_at)) from customer_order where shipped_at >= now() - interval '30 days'`
  3. `select count(*) from sku where on_hand - reserved = 0`
- Route: `GET /dashboard` → tiles + inline SVG bar chart (one `<rect>` per status; heights computed in the controller as `round(count / max * 160)`; colours via CSS classes, no `style` attributes).

- [ ] **Step 1: Write failing tests**

```java
@Test void countsZeroFilled() { /* 2 NEW, 1 SHIPPED → NEW=2, SHIPPED=1, PICKED=0 */ }
@Test void avgTimeToShipLast30Days() {
  // two shipped orders, created_at/shipped_at set by jdbc: 2h and 4h → PT3H; one shipped 40 days ago with 100h ignored
}
@Test void stockOutsCountsFullyReservedOrEmpty() { /* SKU on_hand 0; SKU on_hand 2 reserved 2; SKU on_hand 5 → 2 */ }
```
`DashboardControllerIT`: `GET /dashboard` as operator → 200, contains `"Avg time to ship"` and `<svg`.

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest='KpiQueryServiceIT,DashboardControllerIT'`
Expected: compile errors.

- [ ] **Step 3: Implement**

Format `avgTimeToShip` as `"3h 0m"` (or `"—"` when null) in the controller.

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest='KpiQueryServiceIT,DashboardControllerIT'`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: KPI dashboard from SQL aggregates"
```

---

### Task 11: Admin pages

**Files:**
- Create: `admin/AdminController.java`, `templates/admin/users.html`, `templates/admin/skus.html`
- Test: `admin/AdminControllerIT.java`

**Interfaces:**
- Consumes: `UserService.createUser/setEnabled/resetPassword/listUsers`, `StockService.createSku/adjustOnHand/listSkus`.
- Routes (all ADMIN): `GET /admin/users`, `POST /admin/users` (username, password, role), `POST /admin/users/{id}/enabled` (`enabled`), `POST /admin/users/{id}/password` (`password`); `GET /admin/skus`, `POST /admin/skus` (code, name, onHand), `POST /admin/skus/{id}/adjust` (delta, reason — reason required). Mutations redirect back with a flash message; `IllegalArgumentException` → re-render with the message (422).

- [ ] **Step 1: Write failing tests**

```java
@WithMockUser(username="admin", roles="ADMIN")
@Test void createOperatorAndListIt() { /* POST → 3xx; GET contains username */ }
@Test void shortPasswordShowsError() { /* 422 "at least 12 characters" */ }
@Test void cannotDisableSelf() { /* 422 "You cannot disable your own account" */ }
@Test void adjustBelowReservedRejected() { /* sku on_hand 5 reserved 3; delta -3 → 422 */ }
@Test void adjustRequiresReason() { /* blank reason → 422 */ }
@Test @WithMockUser(roles="OPERATOR") void operatorForbidden() { /* POST /admin/skus → 403 */ }
```

- [ ] **Step 2: Run, expect failure**

Run: `./mvnw -q test -Dtest=AdminControllerIT`
Expected: FAIL (404).

- [ ] **Step 3: Implement controller and templates**

- [ ] **Step 4: Run, expect pass**

Run: `./mvnw -q test -Dtest=AdminControllerIT`
Expected: `Failures: 0, Errors: 0`

- [ ] **Step 5: BUILD_LOG row, commit**

```bash
git add -A && git commit -m "feat: admin pages for users and stock"
```

---

### Task 12: Demo data, backups, README, final verification

**Files:**
- Create: `admin/DemoDataSeeder.java`, `scripts/backup.sh`, `scripts/restore.sh`, `README.md`
- Test: `admin/DemoDataSeederIT.java`

**Interfaces:**
- Consumes: `StockService.createSku`, `OrderService.create/transition`, `JdbcTemplate`.
- Produces: `DemoDataSeeder implements ApplicationRunner`, `@Profile("demo")`, runs only when `sku` table is empty, as a system `Authentication` (`"system"`, ADMIN). Creates 50 SKUs `SKU-001`…`SKU-050` (on_hand 200–1000, fixed `Random(42)`), 1,000 orders (1–3 lines, qty 1–5) moved to statuses roughly NEW 15%, ALLOCATED 15%, PICKED 10%, PACKED 10%, SHIPPED 15%, DELIVERED 30%, CANCELLED 5%, then backdates `created_at` over the last 30 days and sets `shipped_at = created_at + 2–48h` for shipped/delivered via one `jdbc.batchUpdate`.

- [ ] **Step 1: Write failing test**

```java
@ActiveProfiles({"test","demo"})
class DemoDataSeederIT extends IntegrationTest {
  @Test void seedsOnceWithConsistentStock() {
    assertThat(count("sku")).isEqualTo(50); assertThat(count("customer_order")).isEqualTo(1000);
    assertThat(jdbc.queryForObject("select count(*) from sku where reserved > on_hand", Long.class)).isZero();
    // reserved per SKU equals sum of qty of its ALLOCATED/PICKED/PACKED lines
    seeder.run(null);
    assertThat(count("customer_order")).isEqualTo(1000);
  }
}
```
(This class must not call `truncateAll()` in `@BeforeEach`.)

- [ ] **Step 2: Run, expect failure; implement; run, expect pass**

Run: `./mvnw -q test -Dtest=DemoDataSeederIT`
Expected after implementing: `Failures: 0, Errors: 0`

- [ ] **Step 3: Write backup/restore scripts**

`backup.sh`: `mkdir -p backups && docker compose exec -T db pg_dump -U ordermanager -Fc ordermanager > backups/ordermanager-$(date +%Y%m%d-%H%M%S).dump`, prints the path. `restore.sh <file>`: requires an argument, asks `Type RESTORE to overwrite the database:`, then `docker compose exec -T db pg_restore -U ordermanager -d ordermanager --clean --if-exists < "$1"`. Both `set -euo pipefail`, LF endings.

- [ ] **Step 4: Verify the backup → wipe → restore drill**

Run: `docker compose up -d --build`, wait for health, `scripts/backup.sh`, then `docker compose exec -T db psql -U ordermanager -c "delete from order_line; delete from customer_order;"`, then `scripts/restore.sh backups/<file>` and `docker compose exec -T db psql -U ordermanager -tc "select count(*) from customer_order"`.
Expected: count returns to 1000.

- [ ] **Step 5: Write `README.md`**

Sections: what it is; quick start (`cp .env.example .env`, generate key, `docker compose up --build`, open `http://localhost:8080`); demo script (5 minutes: dashboard → bulk allocate → row actions → two-tab conflict → CSV import incl. sample and a bad file → admin stock adjust → audit timeline); **How each criterion is met** — a table mapping the five criteria to files and tests (`CsvImportBenchmarkIT` with the measured number, `StockServiceIT.twentyThreadsCannotOversellFiveUnits`, `OrderQueryServiceIT.queryCountIsConstant`, `CryptoServiceTest`, `SecurityIT`, etc.); data safety and backup drill; security notes including the honest limitation "HTTP only locally — put a TLS reverse proxy in front in production" and that import memory is bounded by the 5 MB / 20,000-row caps; build time table summarised from `BUILD_LOG.md`; the line "Built with AI assistance using Claude Code."; running tests (`./mvnw test`, needs Docker).

- [ ] **Step 6: Full verification**

Run: `./mvnw -q test`
Expected: `BUILD SUCCESS`, zero failures across all test classes.
Run: `docker compose down -v && docker compose up -d --build`, then log in and run through the README demo script.
Expected: every step works; browser console shows no CSP violations.

- [ ] **Step 7: BUILD_LOG final row + totals line, commit**

```bash
git add -A && git commit -m "feat: demo data, backup scripts, README"
```
Then ask the user before `git push -u origin main`.
