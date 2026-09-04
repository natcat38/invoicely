# Invoice API — Project Plan

A Spring Boot REST API for managing clients, invoices, and payments. Built by hand as a learning + portfolio project; Claude assists only with documentation and commit messages.

## Purpose

Demonstrate production-shaped backend competence in the exact stack Singapore enterprise job ads list: **Java 25, Spring Boot 4, Spring Security (JWT), Spring Data JPA, PostgreSQL, tests, Docker**. One well-finished service with auth, migrations, and CI beats several tutorial follow-alongs.

The domain: users register, create clients, issue invoices with line items, record payments, and see invoice status change (DRAFT → SENT → PAID / OVERDUE). Small enough to finish, rich enough to force real design decisions (ownership rules, status transitions, money handling, reporting queries).

## Why this domain sells

- **Relational modeling**: User → Client → Invoice → LineItem / Payment is a genuine multi-table schema with constraints, not a to-do list.
- **Business rules**: status machine, "can't edit a SENT invoice", totals derived from line items, overdue detection — logic worth testing.
- **Auth that matters**: every resource is user-owned, so authorization (not just authentication) is exercised on every endpoint.
- **Enterprise smell**: invoicing is what actual Spring shops build.

## Build order and sources

Build in vertical slices — each phase ends with something runnable and committed.

### Phase 1 — Skeleton + first entity (CRUD on Clients, no auth yet)
- Spring Initializr (start.spring.io): web, data-jpa, postgresql, validation, flyway
- Official guide: [Building a RESTful Web Service](https://spring.io/guides/gs/rest-service/)
- Official guide: [Accessing Data with JPA](https://spring.io/guides/gs/accessing-data-jpa/)
- Run Postgres via `docker compose` from day one (skip H2 except in tests)

### Phase 2 — Schema migrations + full domain model
- Flyway docs: https://documentation.red-gate.com/fd/quickstart-how-flyway-works-184127223.html
- Baeldung: [Database Migrations with Flyway](https://www.baeldung.com/database-migrations-with-flyway)
- Model Invoice, LineItem, Payment; use `BigDecimal` for money, enum for status
- Vlad Mihalcea on JPA relationships (read before mapping @OneToMany): https://vladmihalcea.com/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/

### Phase 3 — Validation, DTOs, error handling
- Baeldung: [Validation in Spring Boot](https://www.baeldung.com/spring-boot-bean-validation)
- Baeldung: [@RestControllerAdvice / global exception handling](https://www.baeldung.com/exception-handling-for-rest-with-spring)
- Use records as DTOs; never expose entities from controllers. Return RFC-7807 Problem Details (built into Spring 6: `spring.mvc.problemdetails.enabled=true`)

### Phase 4 — Spring Security + JWT
- Official guide first (session-based, to understand the filter chain): https://spring.io/guides/gs/securing-web/
- Then JWT: Spring's own OAuth2 Resource Server approach (cleaner than hand-rolled filters) — Baeldung: https://www.baeldung.com/spring-security-oauth-jwt or Dan Vega's "Spring Security JWT" video (YouTube, uses spring-security-oauth2-resource-server)
- Endpoints: `POST /auth/register`, `POST /auth/login`; BCrypt passwords; ownership checks in service layer (a user only sees their own clients/invoices)

### Phase 5 — Business logic
- Invoice status transitions enforced in the service layer; reject illegal transitions with 409
- Totals computed from line items; overdue detection via `@Scheduled` job (Baeldung: [Spring @Scheduled](https://www.baeldung.com/spring-scheduled-tasks))
- A summary/report endpoint (revenue per month, outstanding balance per client) using a JPQL/native aggregate query — shows you can write SQL

### Phase 6 — Tests
- Unit tests for services (JUnit 5 + Mockito)
- Integration tests with **Testcontainers** (real Postgres) — this is a strong resume keyword: https://testcontainers.com/guides/testing-spring-boot-rest-api-using-testcontainers/
- `@WebMvcTest` + `@SpringBootTest` docs: https://spring.io/guides/gs/testing-web/

### Phase 7 — Ship polish
- OpenAPI/Swagger via springdoc-openapi: https://springdoc.org/
- Dockerfile (multi-stage) + docker-compose for app + db
- GitHub Actions CI running the test suite
- README with an architecture diagram, run instructions, and example requests

## Definition of done

- All endpoints authenticated, ownership enforced, illegal state transitions rejected
- Flyway owns the schema; app starts clean on an empty database
- Testcontainers integration suite green in CI
- `docker compose up` gives a working API + Swagger UI
