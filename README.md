# Invoicely

Invoicing for freelancers: clients, invoices with line items, payments, and a
dashboard of who owes what — with a strict invoice lifecycle
(`DRAFT → SENT → OVERDUE/PAID`) enforced server-side.

Built to demonstrate production-shaped backend work in **Java 25 / Spring Boot 4**:
JWT auth (Spring Security), PostgreSQL with Flyway migrations, Testcontainers
integration tests, and a minimal designed React UI in Phase 2.

> **Status:** implementation in progress (Task 1, skeleton). See
> [docs/Invoice_Product_Scope.md](docs/Invoice_Product_Scope.md),
> [docs/Invoice_Tech_Scope.md](docs/Invoice_Tech_Scope.md), and
> [docs/Invoice_Design_Direction.md](docs/Invoice_Design_Direction.md).

## Running it locally

Needs Docker (PostgreSQL, and Testcontainers in the tests) and a JDK 25 on
`JAVA_HOME`. Maven itself comes from the wrapper.

```powershell
docker compose up -d      # PostgreSQL 16 on localhost:5432
.\mvnw verify             # build + tests (starts its own throwaway Postgres)
.\mvnw spring-boot:run    # http://localhost:8080
```

## Project knowledge

Domain concepts live in [`knowledge/`](knowledge/index.md) (OKF house standard,
validated in CI). Directory index: [FILE-MAP.md](FILE-MAP.md).
