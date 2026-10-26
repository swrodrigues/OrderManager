# Build log

Real start/end times per plan task (local time, CET). Spec phases are in `docs/superpowers/specs/2026-10-25-order-manager-design.md` §11.

| Task | Name | Spec phase | Start | End | Minutes | Notes |
|---|---|---|---|---|---|---|
| 1 | Skeleton, schema, Docker | 1 | 2026-10-25T12:25 | 2026-10-25T12:37 | 12 | Includes waiting for Docker Desktop to be repaired on the dev machine (not build work). |
| 2 | Field encryption (AES-256-GCM) | 3 | 2026-10-25T12:38 | 2026-10-25T12:39 | 1 | 11 unit tests |
| 3 | Users, login lockout, roles, security headers, layout | 2 | 2026-10-25T12:40 | 2026-10-25T12:45 | 5 | Bug found by test: lockout listener bypassed @Transactional proxy |
| 4 | Audit trail, lock-ordered stock reservation | 3 | 2026-10-25T12:46 | 2026-10-25T12:49 | 3 | 20-thread oversell + opposite-order deadlock tests green |
| 5 | Order state machine, stock side effects, optimistic locking | 4 | 2026-10-25T12:50 | 2026-10-25T12:53 | 3 | 49 state-pair cases + 12 service ITs |
| 6 | Keyset-paged order list, constant query count | 6 | 2026-10-25T12:54 | 2026-10-25T12:56 | 2 | 1 SQL statement per page regardless of row count |
| 7 | Orders UI, HTMX row updates, error handling, render timing | 4, 7 | 2026-10-25T12:57 | 2026-10-25T13:10 | 13 | Manually verified in browser: create, two-tab conflict, row action, no CSP errors |
| 8 | Streaming CSV import with JDBC batching | 5 | 2026-10-25T13:11 | 2026-10-25T13:15 | 4 | Benchmark: 10,000 orders / 20,000 rows in 4,122 ms (Testcontainers PostgreSQL, laptop) |
| 9 | Import page, upload limits, error summary | 5 | 2026-10-25T13:16 | 2026-10-25T13:20 | 4 | 6 MB upload verified against a real server |
| 10 | KPI dashboard from SQL aggregates | 6 | 2026-10-25T13:21 | 2026-10-25T13:24 | 3 | 3 aggregate queries; inline SVG chart, no inline styles |
| 11 | Admin pages for users and stock | 7 | 2026-10-25T13:25 | 2026-10-25T13:28 | 3 | |
| 12 | Demo data, backup scripts, README, final verification | 7 | 2026-10-25T13:28 | 2026-10-25T13:52 | 24 | Seeder 17–23 s in Compose; backup→wipe→restore drill: 1000/1986/3922 rows restored; 177 tests green; clean `down -v` + rebuild verified in browser |

**Implementation total:** 12:25 → 2026-10-25T13:52 (design spec committed 12:13, plan 12:22). Wall-clock time, AI-assisted (Claude Code), including test runs and Docker builds.

## Review fix pass

| Task | Name | Spec phase | Start | End | Minutes | Notes |
|---|---|---|---|---|---|---|
| R | Fix 2 critical + 10 important review findings, test-first | all | 2026-10-25T14:08 | 2026-10-25T14:28 | 20 | 15 new tests (each watched failing first); 192 tests green; Load more and session expiry verified in a real browser |

## Container images, Spring Boot 4, PostgreSQL 18, Java 27 (26 Oct)

| Task | Name | Spec phase | Start | End | Minutes | Notes |
|---|---|---|---|---|---|---|
| S | Spring Boot 4.1, scratch-based app image, PostgreSQL 18 (Chainguard) | 7 | 2026-10-26T09:33 | 2026-10-26T10:16 | 43 | Grype and Trivy: app 0, db 0 (was 28 / 3). 192 tests green on the new stack; PG16 → PG18 data moved by dump/restore with identical row counts |
| J | App on Java 27 (Temurin 27+35): build, runtime image, tests | 7 | 2026-10-26T10:24 | 2026-10-26T10:39 | 15 | 192 tests green on JDK 27 (run in container via scripts/test.sh); image 129 MB; Grype and Trivy: 0 |
| U | Undo of the last order step (5 min, same user or admin, stock reverted, audited) | 4 | 2026-10-26T11:43 | 2026-10-26T11:53 | 10 | 17 new tests (13 rules + 4 UI), 209 green on JDK 27; verified in the browser |
